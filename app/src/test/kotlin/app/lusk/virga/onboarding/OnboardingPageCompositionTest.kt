package app.lusk.virga.onboarding

import android.os.Environment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lusk.virga.core.datastore.AppPreferences
import app.lusk.virga.core.datastore.PreferencesRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Stubs [Environment.isExternalStorageManager] so composables that call it on
 * API 30+ don't throw ArrayIndexOutOfBoundsException under Robolectric 4.x.
 * Returning `false` keeps the screen in the "grant needed" state.
 */
@Implements(Environment::class)
class ShadowEnvNoManagerOnboarding : org.robolectric.shadows.ShadowEnvironment() {
    companion object {
        @JvmStatic
        @Implementation
        fun isExternalStorageManager(): Boolean = false
    }
}

/**
 * Robolectric / Compose interaction tests for [OnboardingScreen] covering
 * the API-dependent page composition and the final-page CTA callbacks introduced
 * in D6 (commit 95172c7, feat/0.3.0-onboarding).
 *
 * Two SDK configurations:
 *  - API 34 (TIRAMISU+): 5 pages: welcome → storage → battery → notif → first-remote.
 *  - API 28 (pre-TIRAMISU): 4 pages: welcome → storage → battery → first-remote.
 *
 * Navigation strategy for permission pages:
 *   On each unsatisfied permission page the first "Next" tap launches the system
 *   permission request (Robolectric no-ops it) and marks the page; the second tap
 *   advances the pager. So reaching the final page from welcome costs:
 *     1 tap (welcome) + 2 taps (storage) + 2 taps (battery) [+ 2 taps (notif)] = N taps.
 *
 * [ShadowEnvNoManagerOnboarding] prevents the AIOOBE that Robolectric throws when
 * `isExternalStorageManager()` is called on API 30+.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingPageCompositionTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Minimal PreferencesRepository mock — matches the pattern in OnboardingScreenshotTest.
    private val preferences: PreferencesRepository = mockk(relaxed = true) {
        every { preferences } returns flowOf(AppPreferences())
    }

    private fun viewModel() = OnboardingViewModel(preferences)

    // ── Helper: render OnboardingScreen and wait for initial idle ──────────────

    private fun setContent(
        onFinished: () -> Unit = {},
        onAddFirstRemote: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OnboardingScreen(
                        onFinished = onFinished,
                        onAddFirstRemote = onAddFirstRemote,
                        viewModel = viewModel(),
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * Advances through [count] permission pages starting from the current page.
     * Each permission page requires two taps: one to fire the intent (Robolectric
     * no-ops it), one to advance.
     */
    private fun advanceThroughPermissionPage() {
        composeRule.onNodeWithText("Next").performClick()
        composeRule.waitForIdle()
        // Second tap: intent already fired, now advances.
        composeRule.onNodeWithText("Next").performClick()
        composeRule.waitForIdle()
    }

    private fun advancePage() {
        composeRule.onNodeWithText("Next").performClick()
        composeRule.waitForIdle()
    }

    /**
     * The crash-reporting consent page (github/play flavors — BuildConfig
     * CRASH_REPORTING_AVAILABLE) sits between the last permission page and the
     * final first-remote page. It's an ordinary (non-permission) page, so one
     * Next tap advances it. Absent on fdroid, where this is a no-op.
     */
    private fun advanceCrashConsentIfPresent() {
        if (app.lusk.virga.BuildConfig.CRASH_REPORTING_AVAILABLE) advancePage()
    }

    // ── API 34: notifications page is present (page count = 5) ─────────────────

    @Test
    @Config(sdk = [34], shadows = [ShadowEnvNoManagerOnboarding::class])
    fun `should display notifications rationale title on API 34 after advancing to notif page`() {
        setContent()

        // welcome(0) → storage(1): one tap (welcome is not a permission page)
        advancePage()
        // storage(1) → battery(2): two taps
        advanceThroughPermissionPage()
        // battery(2) → notif(3): two taps
        advanceThroughPermissionPage()

        // Page 3 on API 34 is the notifications page.
        composeRule.onNodeWithText("Stay informed").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], shadows = [ShadowEnvNoManagerOnboarding::class])
    fun `should show five pages on API 34 — final page reached after five advances`() {
        setContent()

        // Navigate to final page: welcome→storage (1), storage→battery (2), battery→notif (2),
        // notif→first-remote (2) = 7 taps total.
        advancePage()
        advanceThroughPermissionPage()
        advanceThroughPermissionPage()
        advanceThroughPermissionPage()
        advanceCrashConsentIfPresent()

        // On the final page the primary button reads "Add your first remote".
        composeRule.onNodeWithText("Add your first remote").assertIsDisplayed()
    }

    // ── API 28: notifications page is absent (page count = 4) ──────────────────

    @Test
    @Config(sdk = [28])
    fun `should not display notifications rationale title on API 28`() {
        setContent()

        // Advance all the way through the pager on API 28:
        // welcome→storage (1), storage→battery (2), battery→first-remote (2) = 5 taps.
        advancePage()
        advanceThroughPermissionPage()
        advanceThroughPermissionPage()
        advanceCrashConsentIfPresent()

        // "Stay informed" is the notif page title — it must never appear.
        composeRule.onNodeWithText("Stay informed").assertDoesNotExist()
        // We are on the final page (first-remote) — verify the CTA is visible.
        composeRule.onNodeWithText("Add your first remote").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [28])
    fun `should reach final page in four advances on API 28 without a notifications page`() {
        setContent()

        advancePage()
        advanceThroughPermissionPage()
        advanceThroughPermissionPage()
        advanceCrashConsentIfPresent()

        // "Add your first cloud account" is the first-remote page title.
        composeRule.onNodeWithText("Add your first cloud account").assertIsDisplayed()
    }

    // ── Final-page CTAs: API 34 ─────────────────────────────────────────────────

    @Test
    @Config(sdk = [34], shadows = [ShadowEnvNoManagerOnboarding::class])
    fun `should invoke onAddFirstRemote when primary CTA tapped on final page on API 34`() {
        var addFirstRemoteInvoked = false

        setContent(onAddFirstRemote = { addFirstRemoteInvoked = true })

        // Navigate to final page (page 4 on API 34).
        advancePage()
        advanceThroughPermissionPage()
        advanceThroughPermissionPage()
        advanceThroughPermissionPage()
        advanceCrashConsentIfPresent()

        composeRule.onNodeWithText("Add your first remote").performClick()
        composeRule.waitForIdle()

        assertThat(addFirstRemoteInvoked).isTrue()
    }

    @Test
    @Config(sdk = [34], shadows = [ShadowEnvNoManagerOnboarding::class])
    fun `should invoke onFinished when Get started tapped on final page on API 34`() {
        var finishedInvoked = false

        setContent(onFinished = { finishedInvoked = true })

        // Navigate to final page (page 4 on API 34).
        advancePage()
        advanceThroughPermissionPage()
        advanceThroughPermissionPage()
        advanceThroughPermissionPage()
        advanceCrashConsentIfPresent()

        composeRule.onNodeWithText("Get started").performClick()
        composeRule.waitForIdle()

        assertThat(finishedInvoked).isTrue()
    }

    // ── Final-page CTAs: API 28 ─────────────────────────────────────────────────

    @Test
    @Config(sdk = [28])
    fun `should invoke onAddFirstRemote when primary CTA tapped on final page on API 28`() {
        var addFirstRemoteInvoked = false

        setContent(onAddFirstRemote = { addFirstRemoteInvoked = true })

        // Navigate to final page (page 3 on API 28).
        advancePage()
        advanceThroughPermissionPage()
        advanceThroughPermissionPage()
        advanceCrashConsentIfPresent()

        composeRule.onNodeWithText("Add your first remote").performClick()
        composeRule.waitForIdle()

        assertThat(addFirstRemoteInvoked).isTrue()
    }

    @Test
    @Config(sdk = [28])
    fun `should invoke onFinished when Get started tapped on final page on API 28`() {
        var finishedInvoked = false

        setContent(onFinished = { finishedInvoked = true })

        // Navigate to final page (page 3 on API 28).
        advancePage()
        advanceThroughPermissionPage()
        advanceThroughPermissionPage()
        advanceCrashConsentIfPresent()

        composeRule.onNodeWithText("Get started").performClick()
        composeRule.waitForIdle()

        assertThat(finishedInvoked).isTrue()
    }

    // ── Welcome-page Skip CTA ───────────────────────────────────────────────────

    @Test
    @Config(sdk = [34], shadows = [ShadowEnvNoManagerOnboarding::class])
    fun `should invoke onFinished when Skip tapped on the welcome page`() {
        var finishedInvoked = false

        setContent(onFinished = { finishedInvoked = true })

        // Welcome is page 0, where the secondary button reads "Skip" (it becomes
        // "Back" on every later page); tapping it completes onboarding immediately.
        composeRule.onNodeWithText("Skip").performClick()
        composeRule.waitForIdle()

        assertThat(finishedInvoked).isTrue()
    }

    // ── Crash-consent page + toggle (github/play — CRASH_REPORTING_AVAILABLE) ────

    @Test
    @Config(sdk = [34], shadows = [ShadowEnvNoManagerOnboarding::class])
    fun `should render crash-consent page and flip the switch when crash reporting is available`() {
        // Absent on fdroid (no Sentry compiled in), so skip there rather than fail.
        assumeTrue(app.lusk.virga.BuildConfig.CRASH_REPORTING_AVAILABLE)

        setContent()

        // welcome→storage (1), storage→battery (2), battery→notif (2), notif→consent (2).
        advancePage()
        advanceThroughPermissionPage()
        advanceThroughPermissionPage()
        advanceThroughPermissionPage()

        // The consent page is now showing; its Switch starts at the flavor default.
        composeRule.onNodeWithText("Help improve Virga").assertIsDisplayed()
        val startedOn = app.lusk.virga.BuildConfig.CRASH_REPORTING_DEFAULT_ON
        val toggle = composeRule.onNode(isToggleable())
        if (startedOn) toggle.assertIsOn() else toggle.assertIsOff()

        // Tapping the Switch exercises the onToggle callback and flips the state.
        toggle.performClick()
        composeRule.waitForIdle()
        if (startedOn) toggle.assertIsOff() else toggle.assertIsOn()
    }
}
