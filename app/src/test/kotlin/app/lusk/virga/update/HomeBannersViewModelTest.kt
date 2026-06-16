package app.lusk.virga.update

import android.content.Context
import android.content.res.Resources
import app.lusk.virga.BuildConfig
import app.lusk.virga.R
import app.lusk.virga.core.datastore.AppPreferences
import app.lusk.virga.core.datastore.PreferencesRepository
import app.lusk.virga.feature.sync.UpdateBanner
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [HomeBannersViewModel] — the derivation of the Home screen's
 * changelog + update banner state from preferences and the update checker.
 *
 * Lives in src/test (HomeBannersViewModel is in the main source set), so it runs
 * under both :app:testFossDebugUnitTest and :app:testPlayDebugUnitTest.
 *
 * Note on BuildConfig: in unit tests the app module's generated BuildConfig has
 * VERSION_CODE=1 and VERSION_NAME="0.1.0" (the gradle defaults, no env override).
 * Resources are provided via a MockK [Resources] stub that returns the same English
 * strings as the real res/values/strings.xml, so the resource-backed changelog
 * builder works without Robolectric.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeBannersViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val prefsFlow = MutableStateFlow(AppPreferences())
    private val prefs: PreferencesRepository = mockk(relaxed = true) {
        every { preferences } returns prefsFlow
    }
    private val updateChecker: UpdateChecker = mockk(relaxed = true)

    /**
     * Stub [Resources] that serves the English strings for every version entry.
     * Only the 0.1.0 entry matters for most tests (BuildConfig.VERSION_NAME = "0.1.0"),
     * but all three are stubbed so [releaseNotes] can build the full list without crashing.
     */
    private val resources: Resources = mockk {
        every { getString(R.string.release_version_0_3_0) } returns "0.3.0"
        every { getStringArray(R.array.release_notes_0_3_0) } returns arrayOf(
            "Create a new destination folder right from the folder picker",
            "Test a remote's connectivity on demand from its card menu",
            "Importing a config now warns you before it replaces your remotes",
            "Old per-run sync logs are pruned automatically, so they no longer grow without bound",
        )
        every { getString(R.string.release_version_0_2_0) } returns "0.2.0"
        every { getStringArray(R.array.release_notes_0_2_0) } returns arrayOf(
            "Configure any rclone provider — Box, Dropbox, OneDrive, Google Drive, pCloud, and more",
            "Sign in with OAuth, or bring your own credentials",
            "Daemon-mediated OAuth for providers without a bundled sign-in",
            "Add crypt and wrapper remotes (union, alias, and others)",
            "Import and export your rclone config",
            "Backups now continue past unreadable files and report an error summary instead of stopping",
        )
        every { getString(R.string.release_version_0_1_0) } returns "0.1.0"
        every { getStringArray(R.array.release_notes_0_1_0) } returns arrayOf(
            "New Home dashboard with sync status and lifetime stats",
            "Refreshed app icon, provider marks, and Settings",
            "More reliable encrypted-config handling and a keyboard-free launch",
        )
    }

    private val context: Context = mockk {
        every { this@mockk.resources } returns this@HomeBannersViewModelTest.resources
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = HomeBannersViewModel(context, prefs, updateChecker)

    @Test
    fun `changelog shows when current version code exceeds last seen`() = runTest(dispatcher) {
        // Default AppPreferences.lastSeenChangelogVersionCode = 0, BuildConfig.VERSION_CODE = 1.
        coEvery { updateChecker.check() } returns null
        val viewModel = vm()

        viewModel.state.test {
            // Initial value before the combine collector primes.
            assertThat(awaitItem()).isEqualTo(HomeBannersState(changelog = null, update = null))
            advanceUntilIdle()
            val changelog = expectMostRecentItem().changelog
            assertThat(changelog).isNotNull()
            assertThat(changelog?.versionName).isEqualTo(BuildConfig.VERSION_NAME)
            assertThat(changelog?.notes).isNotEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `changelog hidden when last seen is at or above current version code`() = runTest(dispatcher) {
        prefsFlow.value = AppPreferences(lastSeenChangelogVersionCode = BuildConfig.VERSION_CODE)
        coEvery { updateChecker.check() } returns null
        val viewModel = vm()

        viewModel.state.test {
            // Derived state equals the initial (both changelog=null), so the StateFlow
            // conflates to a single emission — read whatever the latest value is.
            advanceUntilIdle()
            assertThat(expectMostRecentItem().changelog).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `available update populates the update banner`() = runTest(dispatcher) {
        coEvery { updateChecker.check() } returns AvailableUpdate("2.0.0")
        val viewModel = vm()

        viewModel.state.test {
            advanceUntilIdle()
            assertThat(expectMostRecentItem().update).isEqualTo(UpdateBanner("2.0.0"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no available update leaves the update banner null`() = runTest(dispatcher) {
        coEvery { updateChecker.check() } returns null
        val viewModel = vm()

        viewModel.state.test {
            advanceUntilIdle()
            assertThat(expectMostRecentItem().update).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dismissUpdate clears the update banner`() = runTest(dispatcher) {
        coEvery { updateChecker.check() } returns AvailableUpdate("2.0.0")
        val viewModel = vm()

        viewModel.state.test {
            advanceUntilIdle()
            assertThat(expectMostRecentItem().update).isEqualTo(UpdateBanner("2.0.0"))

            viewModel.dismissUpdate()
            advanceUntilIdle()
            assertThat(expectMostRecentItem().update).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dismissUpdate does not affect the changelog banner`() = runTest(dispatcher) {
        // Changelog visible (default prefs), update also present.
        coEvery { updateChecker.check() } returns AvailableUpdate("2.0.0")
        val viewModel = vm()

        viewModel.state.test {
            advanceUntilIdle()
            viewModel.dismissUpdate()
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertThat(state.update).isNull()
            assertThat(state.changelog).isNotNull() // untouched by the update dismiss
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dismissChangelog persists the current version code`() = runTest(dispatcher) {
        coEvery { updateChecker.check() } returns null
        val viewModel = vm()

        viewModel.dismissChangelog()
        advanceUntilIdle()

        coVerify(exactly = 1) { prefs.setLastSeenChangelogVersionCode(BuildConfig.VERSION_CODE) }
    }

    @Test
    fun `startUpdate delegates to the update checker`() = runTest(dispatcher) {
        coEvery { updateChecker.check() } returns null
        val activity = mockk<android.app.Activity>(relaxed = true)
        val viewModel = vm()

        viewModel.startUpdate(activity)

        verify(exactly = 1) { updateChecker.startUpdate(activity) }
    }
}
