package app.lusk.virga.feature.settings

import android.os.Environment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import app.lusk.virga.core.datastore.AppPreferences
import app.lusk.virga.core.datastore.PreferencesRepository
import app.lusk.virga.core.designsystem.theme.VirgaTheme
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric shadow that stubs the STATIC [Environment.isExternalStorageManager].
 * Robolectric 4.16.1 does not shadow that static method, so `StorageAccessSection`
 * (reached when `storageAccessRelevant = true`) throws AIOOBE at composition. Returning
 * `false` makes the screen render its "grant access" state deterministically.
 */
@Implements(Environment::class)
class ShadowEnvNoManager : org.robolectric.shadows.ShadowEnvironment() {
    companion object {
        @JvmStatic
        @Implementation
        fun isExternalStorageManager(): Boolean = false
    }
}

/**
 * Roborazzi golden-image tests for [SettingsScreen]. Runs entirely on the JVM via
 * Robolectric; captures PNGs and compares against committed goldens at
 * `src/test/snapshots/`.
 *
 * Generate / refresh goldens:
 *   ./gradlew :feature:settings:recordRoborazziDebug
 * Verify against goldens (CI default; fails on visual diff):
 *   ./gradlew :feature:settings:verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav",
    shadows = [ShadowEnvNoManager::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val roborazziRule = RoborazziRule(
        composeRule = composeRule,
        captureRoot = composeRule.onRoot(),
        options = RoborazziRule.Options(
            outputDirectoryPath = "src/test/snapshots",
        ),
    )

    private fun viewModel(): SettingsViewModel {
        val repository: PreferencesRepository = mockk(relaxed = true) {
            every { preferences } returns MutableStateFlow(AppPreferences())
        }
        return SettingsViewModel(repository)
    }

    @Test
    fun settingsScreen_withStorageAccess() {
        composeRule.setContent {
            VirgaTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        crashReportingAvailable = true,
                        storageAccessRelevant = true,
                        viewModel = viewModel(),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage()
    }
    // Note: a storageAccessRelevant=false variant was dropped — StorageAccessSection
    // renders below the capture viewport, so the two produced byte-identical goldens.
    // The =true case above is what exercises the ShadowEnvNoManager path (the section
    // composes off-screen and would otherwise AIOOBE during composition).
}
