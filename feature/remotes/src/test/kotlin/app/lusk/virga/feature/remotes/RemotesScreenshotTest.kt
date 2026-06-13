package app.lusk.virga.feature.remotes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.model.RemoteOption
import app.lusk.virga.core.rclone.PickerEntry
import app.lusk.virga.core.rclone.SetupKind
import app.lusk.virga.core.rclone.oauth.OAuthProviders
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

/**
 * Roborazzi golden-image tests for the Remotes feature. Runs entirely on the JVM
 * via Robolectric; captures PNGs of key Compose screens and compares against
 * committed goldens at `src/test/snapshots/`.
 *
 * Generate / refresh goldens:
 *   ./gradlew :feature:remotes:recordRoborazziDebug
 * Verify against goldens (CI default; fails on visual diff):
 *   ./gradlew :feature:remotes:verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemotesScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val roborazziRule = RoborazziRule(
        composeRule = composeRule,
        captureRoot = composeRule.onRoot(),
        options = RoborazziRule.Options(
            outputDirectoryPath = "src/test/snapshots",
            // Verify mode is the CI default — fails on pixel diff. Switch to
            // RECORD via -Proborazzi.test.record=true to regenerate goldens.
        ),
    )

    /**
     * Builds a relaxed [RemotesViewModel] mock with the three flows the screen
     * collects ([RemotesViewModel.uiState], [RemotesViewModel.launchUrl],
     * [RemotesViewModel.providers]) and the read-only [RemotesViewModel.oauthProviders]
     * stubbed. Imperative methods (fetchQuota, refresh, …) stay relaxed no-ops, so
     * the render is fully deterministic — no real daemon, network, or quota fetch runs.
     */
    private fun fakeViewModel(uiState: RemotesUiState): RemotesViewModel = mockk(relaxed = true) {
        every { this@mockk.uiState } returns MutableStateFlow(uiState)
        every { launchUrl } returns MutableStateFlow(null)
        // null providers → screen keeps the freeform fallback path; deterministic.
        every { providers } returns MutableStateFlow(null)
        every { oauthProviders } returns OAuthProviders.All
        every { pickerEntries() } returns null
    }

    @Test
    fun remotesScreen_populated() {
        val vm = fakeViewModel(
            RemotesUiState(
                remotes = listOf(
                    Remote(name = "gdrive", type = "drive"),
                    Remote(name = "backblaze", type = "b2"),
                ),
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        RemotesScreen(onOpenBrowser = {}, viewModel = vm)
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun remotesScreen_empty() {
        val vm = fakeViewModel(RemotesUiState(remotes = emptyList()))
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        RemotesScreen(onOpenBrowser = {}, viewModel = vm)
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun providerPicker_default() {
        val entries = listOf(
            PickerEntry(type = "drive", description = "Google Drive"),
            PickerEntry(type = "s3", description = "Amazon S3 / compatible"),
            PickerEntry(type = "b2", description = "Backblaze B2"),
            PickerEntry(type = "sftp", description = "SFTP"),
            PickerEntry(type = "crypt", description = "Encrypted (wrapper)"),
        )
        val setupKindFor: (String) -> SetupKind = { type ->
            when (type) {
                "drive" -> SetupKind.OAuth(bundled = true)
                "crypt" -> SetupKind.Wrapper
                else -> SetupKind.Credential
            }
        }
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ProviderPicker(
                            entries = entries,
                            setupKindFor = setupKindFor,
                            onSelect = {},
                        )
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun credentialForm_typedFields() {
        val options = listOf(
            RemoteOption(
                name = "account",
                help = "Account ID or Application Key ID.",
                type = "string",
                required = true,
                isPassword = false,
                default = null,
                examples = emptyList(),
                advanced = false,
            ),
            RemoteOption(
                name = "key",
                help = "Application Key.",
                type = "string",
                required = true,
                isPassword = true,
                default = null,
                examples = emptyList(),
                advanced = false,
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        CredentialFormSection(
                            name = "backblaze",
                            nameUsable = true,
                            type = "b2",
                            onTypeChange = {},
                            isOAuthByok = false,
                            isCrypt = false,
                            usePicker = true,
                            schemaOptions = options,
                            typedValues = mutableMapOf(
                                "account" to "0012ab34cd56",
                                "key" to "K001xxxxxxxxxxxxxxxx",
                            ),
                            showAdvanced = false,
                            onToggleAdvanced = {},
                            params = "",
                            onParamsChange = {},
                            oauthProviders = OAuthProviders.All,
                            customClientIds = emptyMap(),
                            existingRemotes = emptyList(),
                            error = null,
                            oauthInProgress = false,
                            daemonOAuthTokenPrompt = null,
                            cryptBaseRemote = "",
                            onCryptBaseRemoteSelected = {},
                            cryptBasePath = "",
                            onCryptBasePathChange = {},
                            cryptPassword = "",
                            onCryptPasswordChange = {},
                            cryptSalt = "",
                            onCryptSaltChange = {},
                            onEnsureProviders = {},
                            onBack = {},
                            onDismiss = {},
                            onOAuth = { _, _ -> },
                            onManualConfirm = { _, _, _ -> },
                            onCryptConfirm = { _, _, _, _, _ -> },
                            onDaemonOAuth = { _, _, _, _ -> },
                            onSubmitDaemonOAuthToken = {},
                            onCancelDaemonOAuth = {},
                            onSaveClientId = { _, _ -> },
                            onClearClientId = {},
                        )
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage()
    }
}
