package app.lusk.virga.feature.remotes

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.lusk.virga.core.rclone.oauth.OAuthProviders
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric Compose render tests for the `ByoKeysSection` component, exercised
 * through the public [CredentialFormSection] entry point with `isCrypt=false` and
 * `usePicker=false` so the BYO path is reached.
 *
 * The three scenarios covered map directly to the bug-fix guard on `onSaveClientSecret`:
 *
 *  1. Save with both client ID and secret → both callbacks fire.
 *  2. Save with client ID only (secret field blank) → `onSaveClientSecret` must NOT
 *     fire — the blank-field guard in [ByoKeysSection] prevents accidental secret wipes.
 *  3. Clear with a stored client ID → both `onClearClientId` and `onClearClientSecret`
 *     fire (paired-secret cleanup).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ByoKeysSectionRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Providers: Google Drive first so it is the default-selected chip and the
    // secret field is visible (showSecretField = selectedId == GoogleDrive.id).
    private val providers = listOf(
        OAuthProviders.GoogleDrive,
        OAuthProviders.OneDrive,
        OAuthProviders.Dropbox,
    )

    // Captured callback invocations — reset per test via fresh lists.
    private val savedIds = mutableListOf<Pair<String, String>>()
    private val savedSecrets = mutableListOf<Pair<String, String>>()
    private val clearedIds = mutableListOf<String>()
    private val clearedSecrets = mutableListOf<String>()

    /**
     * Renders [CredentialFormSection] with the BYO path active:
     *   - `isCrypt = false`, `usePicker = false`, `isOAuthByok = false`
     *   - `oauthProviders` starts with GoogleDrive so it is pre-selected
     *   - `customClientIds` is supplied by the caller (controls whether Clear is shown)
     *
     * All other parameters are innocuous no-ops or empty defaults.
     */
    private fun render(customClientIds: Map<String, String> = emptyMap()) =
        renderWithProviders(providers, customClientIds)

    /**
     * Variant of [render] that lets the caller supply a custom provider list.
     * The first entry in [providerList] becomes the default-selected BYO chip,
     * which controls whether the client-secret field is shown.
     */
    private fun renderWithProviders(
        providerList: List<app.lusk.virga.core.rclone.oauth.OAuthProvider>,
        customClientIds: Map<String, String> = emptyMap(),
    ) {
        savedIds.clear(); savedSecrets.clear()
        clearedIds.clear(); clearedSecrets.clear()

        composeRule.setContent {
            MaterialTheme {
                Surface {
                    Column {
                        CredentialFormSection(
                            name = "my-gdrive",
                            nameUsable = true,
                            type = "drive",
                            onTypeChange = {},
                            isOAuthByok = false,
                            isCrypt = false,
                            usePicker = false,
                            schemaOptions = null,
                            typedValues = mutableMapOf(),
                            showAdvanced = false,
                            onToggleAdvanced = {},
                            params = "",
                            onParamsChange = {},
                            oauthProviders = providerList,
                            customClientIds = customClientIds,
                            existingRemotes = emptyList(),
                            error = null,
                            oauthInProgress = false,
                            daemonOAuthTokenPrompt = null,
                            daemonOAuthFieldPrompt = null,
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
                            onSubmitDaemonOAuthFieldAnswer = {},
                            onCancelDaemonOAuth = {},
                            onSaveClientId = { id, v -> savedIds += id to v },
                            onClearClientId = { id -> clearedIds += id },
                            onSaveClientSecret = { id, v -> savedSecrets += id to v },
                            onClearClientSecret = { id -> clearedSecrets += id },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun expandByoSection() {
        composeRule.onNodeWithText("Use your own keys (recommended for regular use)")
            .performClick()
        composeRule.waitForIdle()
    }

    // -------------------------------------------------------------------------
    // Render smoke: BYO toggle is present before expansion
    // -------------------------------------------------------------------------

    @Test
    fun byoToggle_isDisplayed_beforeExpansion() {
        render()
        composeRule
            .onNodeWithText("Use your own keys (recommended for regular use)")
            .assertIsDisplayed()
    }

    @Test
    fun byoSection_expandsOnToggleClick_andShowsClientIdField() {
        render()
        expandByoSection()
        composeRule.onNodeWithText("Your client ID").assertIsDisplayed()
    }

    @Test
    fun byoSection_expanded_showsClientSecretField_forGoogleDrive() {
        render()
        expandByoSection()
        // Secret field is shown because GoogleDrive is the default-selected chip.
        composeRule
            .onNodeWithText("Your client secret (Google only — enables daemon flow)")
            .assertIsDisplayed()
    }

    // -------------------------------------------------------------------------
    // Scenario 1: Save with both client ID and client secret
    // -------------------------------------------------------------------------

    @Test
    fun saveKey_withIdAndSecret_callsBothSaveCallbacks() {
        render()
        expandByoSection()

        composeRule.onNodeWithText("Your client ID").performTextInput("my-client-id")
        composeRule.onNodeWithText("Your client secret (Google only — enables daemon flow)")
            .performTextInput("test-secret")
        composeRule.onNodeWithText("Save key").performClick()
        composeRule.waitForIdle()

        assertThat(savedIds).hasSize(1)
        assertThat(savedIds[0]).isEqualTo(OAuthProviders.GoogleDrive.id to "my-client-id")
        assertThat(savedSecrets).hasSize(1)
        assertThat(savedSecrets[0]).isEqualTo(OAuthProviders.GoogleDrive.id to "test-secret")
    }

    @Test
    fun saveKey_withIdAndSecret_saveIdReceivesCorrectProviderId() {
        render()
        expandByoSection()

        composeRule.onNodeWithText("Your client ID").performTextInput("my-client-id")
        composeRule.onNodeWithText("Your client secret (Google only — enables daemon flow)")
            .performTextInput("test-secret")
        composeRule.onNodeWithText("Save key").performClick()
        composeRule.waitForIdle()

        assertThat(savedIds[0].first).isEqualTo(OAuthProviders.GoogleDrive.id)
    }

    @Test
    fun saveKey_withIdAndSecret_saveSecretReceivesCorrectProviderId() {
        render()
        expandByoSection()

        composeRule.onNodeWithText("Your client ID").performTextInput("my-client-id")
        composeRule.onNodeWithText("Your client secret (Google only — enables daemon flow)")
            .performTextInput("test-secret")
        composeRule.onNodeWithText("Save key").performClick()
        composeRule.waitForIdle()

        assertThat(savedSecrets[0].first).isEqualTo(OAuthProviders.GoogleDrive.id)
    }

    // -------------------------------------------------------------------------
    // Scenario 2: Save with client ID only — blank secret must NOT fire callback
    // Regression guard for the blank-field guard in ByoKeysSection.
    // -------------------------------------------------------------------------

    @Test
    fun saveKey_withIdOnlyBlankSecret_callsSaveIdButNotSaveSecret() {
        render()
        expandByoSection()

        // Type a client ID but leave the secret field blank.
        composeRule.onNodeWithText("Your client ID").performTextInput("my-client-id")
        // Do NOT touch the secret field — it stays blank (default state).
        composeRule.onNodeWithText("Save key").performClick()
        composeRule.waitForIdle()

        assertThat(savedIds).hasSize(1)
        assertThat(savedIds[0]).isEqualTo(OAuthProviders.GoogleDrive.id to "my-client-id")
        // The blank-guard: onSaveClientSecret must NOT be invoked.
        assertThat(savedSecrets).isEmpty()
    }

    @Test
    fun saveKey_withBlankSecret_saveIdIsStillCalled() {
        render()
        expandByoSection()

        composeRule.onNodeWithText("Your client ID").performTextInput("my-client-id")
        composeRule.onNodeWithText("Save key").performClick()
        composeRule.waitForIdle()

        assertThat(savedIds).isNotEmpty()
    }

    @Test
    fun saveKey_withBlankSecret_saveSecretCallCountIsZero() {
        render()
        expandByoSection()

        composeRule.onNodeWithText("Your client ID").performTextInput("my-client-id")
        composeRule.onNodeWithText("Save key").performClick()
        composeRule.waitForIdle()

        assertThat(savedSecrets).isEmpty()
    }

    // -------------------------------------------------------------------------
    // Scenario 3: Clear — both clearClientId and clearClientSecret fire
    // -------------------------------------------------------------------------

    @Test
    fun clearKey_withStoredClientId_callsBothClearCallbacks() {
        // Pass a customClientIds map so the Clear button is visible.
        render(customClientIds = mapOf(OAuthProviders.GoogleDrive.id to "existing-id"))
        expandByoSection()

        composeRule.onNodeWithText("Clear").performClick()
        composeRule.waitForIdle()

        assertThat(clearedIds).containsExactly(OAuthProviders.GoogleDrive.id)
        assertThat(clearedSecrets).containsExactly(OAuthProviders.GoogleDrive.id)
    }

    @Test
    fun clearKey_callsClearClientIdWithCorrectProvider() {
        render(customClientIds = mapOf(OAuthProviders.GoogleDrive.id to "existing-id"))
        expandByoSection()

        composeRule.onNodeWithText("Clear").performClick()
        composeRule.waitForIdle()

        assertThat(clearedIds).hasSize(1)
        assertThat(clearedIds[0]).isEqualTo(OAuthProviders.GoogleDrive.id)
    }

    @Test
    fun clearKey_callsClearClientSecretWithCorrectProvider() {
        render(customClientIds = mapOf(OAuthProviders.GoogleDrive.id to "existing-id"))
        expandByoSection()

        composeRule.onNodeWithText("Clear").performClick()
        composeRule.waitForIdle()

        assertThat(clearedSecrets).hasSize(1)
        assertThat(clearedSecrets[0]).isEqualTo(OAuthProviders.GoogleDrive.id)
    }

    @Test
    fun clearKey_isNotDisplayed_whenNoStoredClientId() {
        // No stored ID for Google Drive: Clear button must not appear.
        render(customClientIds = emptyMap())
        expandByoSection()

        composeRule.onNodeWithText("Clear").assertDoesNotExist()
    }

    // -------------------------------------------------------------------------
    // Non-Google provider: secret field absent
    //
    // The ByoKeysSection pre-selects providers.first().  Render with OneDrive /
    // Dropbox first to exercise the no-secret-field branch without having to click
    // a FilterChip that shares its label text with the AssistChip row above it
    // (which would produce an ambiguous-node error under the test semantics tree).
    // -------------------------------------------------------------------------

    @Test
    fun defaultProvider_oneDrive_doesNotShowClientSecretField() {
        // OneDrive first → selectedId defaults to onedrive → no secret field.
        renderWithProviders(listOf(OAuthProviders.OneDrive, OAuthProviders.GoogleDrive, OAuthProviders.Dropbox))
        expandByoSection()

        composeRule
            .onNodeWithText("Your client secret (Google only — enables daemon flow)")
            .assertDoesNotExist()
    }

    @Test
    fun defaultProvider_dropbox_doesNotShowClientSecretField() {
        renderWithProviders(listOf(OAuthProviders.Dropbox, OAuthProviders.GoogleDrive, OAuthProviders.OneDrive))
        expandByoSection()

        composeRule
            .onNodeWithText("Your client secret (Google only — enables daemon flow)")
            .assertDoesNotExist()
    }

    @Test
    fun saveKey_forOneDriveAsDefaultProvider_neverFiresSaveSecret() {
        // OneDrive first → no secret field shown → Save must not call onSaveClientSecret.
        renderWithProviders(listOf(OAuthProviders.OneDrive, OAuthProviders.GoogleDrive, OAuthProviders.Dropbox))
        expandByoSection()

        composeRule.onNodeWithText("Your client ID").performTextInput("od-client-id")
        composeRule.onNodeWithText("Save key").performClick()
        composeRule.waitForIdle()

        assertThat(savedIds).hasSize(1)
        assertThat(savedIds[0]).isEqualTo(OAuthProviders.OneDrive.id to "od-client-id")
        assertThat(savedSecrets).isEmpty()
    }
}
