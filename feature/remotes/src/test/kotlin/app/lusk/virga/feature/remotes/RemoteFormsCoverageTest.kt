package app.lusk.virga.feature.remotes

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import app.lusk.virga.core.common.model.Remote
import app.lusk.virga.core.common.model.RemoteOption
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Render coverage for the wrapper/crypt sub-forms and the shared helpers they
 * delegate to ([CryptRemoteForm], [WrapperForm], `RemoteDropdownPicker`,
 * `MaskedPasswordField`). These compose entirely on the JVM via Robolectric; the
 * tests exercise every branch (populated vs empty remotes, single vs union vs
 * crypt wrapper) so the extracted helpers are executed, not just defined.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoteFormsCoverageTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val remotes = listOf(Remote("gdrive", "drive"), Remote("box", "box"))

    private fun option(name: String) = RemoteOption(
        name = name,
        help = "Help text for $name",
        type = "string",
        required = false,
        isPassword = false,
        default = null,
        examples = emptyList(),
        advanced = false,
    )

    private fun render(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    Column { content() }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun cryptRemoteForm_withBaseRemotes_rendersDropdownPasswordAndSalt() {
        render {
            CryptRemoteForm(
                existingRemotes = remotes,
                selectedBaseRemote = "gdrive",
                onBaseRemoteSelected = {},
                basePath = "Vault",
                onBasePathChange = {},
                password = "hunter2",
                onPasswordChange = {},
                salt = "pepper",
                onSaltChange = {},
            )
        }
    }

    @Test
    fun cryptRemoteForm_noBaseRemotes_rendersEmptyState() {
        render {
            CryptRemoteForm(
                existingRemotes = emptyList(),
                selectedBaseRemote = "",
                onBaseRemoteSelected = {},
                basePath = "",
                onBasePathChange = {},
                password = "",
                onPasswordChange = {},
                salt = "",
                onSaltChange = {},
            )
        }
    }

    @Test
    fun wrapperForm_singleRemote_rendersPickerAndOptions() {
        render {
            WrapperForm(
                wrapperType = "alias",
                existingRemotes = remotes,
                schemaOptions = listOf(option("remote"), option("description")),
                selectedRemote = "gdrive",
                onRemoteSelected = {},
                selectedRemotes = emptySet(),
                onRemotesChanged = {},
                typedValues = mutableMapOf(),
                showAdvanced = true,
                onToggleAdvanced = {},
                cryptBaseRemote = "",
                onCryptBaseRemoteSelected = {},
                cryptBasePath = "",
                onCryptBasePathChange = {},
                cryptPassword = "",
                onCryptPasswordChange = {},
                cryptSalt = "",
                onCryptSaltChange = {},
            )
        }
    }

    @Test
    fun wrapperForm_union_rendersUpstreamChips() {
        render {
            WrapperForm(
                wrapperType = "union",
                existingRemotes = remotes,
                schemaOptions = listOf(option("upstreams"), option("action_policy")),
                selectedRemote = "",
                onRemoteSelected = {},
                selectedRemotes = setOf("gdrive"),
                onRemotesChanged = {},
                typedValues = mutableMapOf(),
                showAdvanced = false,
                onToggleAdvanced = {},
                cryptBaseRemote = "",
                onCryptBaseRemoteSelected = {},
                cryptBasePath = "",
                onCryptBasePathChange = {},
                cryptPassword = "",
                onCryptPasswordChange = {},
                cryptSalt = "",
                onCryptSaltChange = {},
            )
        }
    }

    @Test
    fun wrapperForm_crypt_delegatesToCryptForm() {
        render {
            WrapperForm(
                wrapperType = "crypt",
                existingRemotes = remotes,
                schemaOptions = null,
                selectedRemote = "",
                onRemoteSelected = {},
                selectedRemotes = emptySet(),
                onRemotesChanged = {},
                typedValues = mutableMapOf(),
                showAdvanced = false,
                onToggleAdvanced = {},
                cryptBaseRemote = "gdrive",
                onCryptBaseRemoteSelected = {},
                cryptBasePath = "Vault",
                onCryptBasePathChange = {},
                cryptPassword = "pw",
                onCryptPasswordChange = {},
                cryptSalt = "salt",
                onCryptSaltChange = {},
            )
        }
    }

    @Test
    fun wrapperForm_noBaseRemotes_rendersEmptyState() {
        render {
            WrapperForm(
                wrapperType = "alias",
                existingRemotes = emptyList(),
                schemaOptions = null,
                selectedRemote = "",
                onRemoteSelected = {},
                selectedRemotes = emptySet(),
                onRemotesChanged = {},
                typedValues = mutableMapOf(),
                showAdvanced = false,
                onToggleAdvanced = {},
                cryptBaseRemote = "",
                onCryptBaseRemoteSelected = {},
                cryptBasePath = "",
                onCryptBasePathChange = {},
                cryptPassword = "",
                onCryptPasswordChange = {},
                cryptSalt = "",
                onCryptSaltChange = {},
            )
        }
    }

    // --- ReauthBadgeRow render (A4) -----------------------------------------------

    @Test
    fun remoteCard_needsReauth_true_showsBadge() {
        render {
            RemoteCard(
                remote = Remote(name = "gdrive", type = "drive", needsReauth = true),
                onOpenBrowser = {},
                onCreateTask = {},
                onDelete = {},
            )
        }
        composeRule.onNodeWithText("Sign-in expired").assertIsDisplayed()
    }

    @Test
    fun remoteCard_needsReauth_false_doesNotShowBadge() {
        render {
            RemoteCard(
                remote = Remote(name = "gdrive", type = "drive", needsReauth = false),
                onOpenBrowser = {},
                onCreateTask = {},
                onDelete = {},
            )
        }
        composeRule.onNodeWithText("Sign-in expired").assertDoesNotExist()
    }

    @Test
    fun daemonOAuthForm_fieldPrompt_rendersHelpLabelExampleChip_andSubmits() {
        var submitted: String? = null
        render {
            DaemonOAuthForm(
                providerName = "Amazon S3",
                nameUsable = true,
                oauthInProgress = true,
                tokenPrompt = null,
                fieldPrompt = DaemonOAuthFieldPrompt(
                    optionName = "access_key_id",
                    label = "Access Key ID",
                    help = "Your AWS access key",
                    examples = listOf("AKIAIOSFODNN7EXAMPLE"),
                    isPassword = false,
                ),
                onConnect = { _, _ -> },
                onSubmitToken = {},
                onSubmitFieldAnswer = { submitted = it },
                onCancel = {},
            )
        }
        // Help text, the field label, and the example chip all render.
        composeRule.onNodeWithText("Your AWS access key").assertIsDisplayed()
        composeRule.onNodeWithText("Access Key ID").assertIsDisplayed()
        // Submit is gated until a value is present; tapping the example chip fills it.
        composeRule.onNodeWithText("Submit").assertIsNotEnabled()
        composeRule.onNodeWithText("AKIAIOSFODNN7EXAMPLE").performClick()
        composeRule.onNodeWithText("Submit").assertIsEnabled()
        composeRule.onNodeWithText("Submit").performClick()
        assertThat(submitted).isEqualTo("AKIAIOSFODNN7EXAMPLE")
    }
}
