package app.lusk.virga.feature.remotes

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric Compose render tests for [BackendSubForm].
 *
 * Coverage:
 *  - S3: dropdown opens and selecting a provider writes [KEY_PROVIDER] into values
 *  - WebDAV: dropdown opens and selecting a vendor writes [KEY_VENDOR] into values
 *  - SFTP: import-key button and initial no-key-loaded state render correctly
 *  - Unknown type: none of the sub-form labels appear
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackendSubFormRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(type: String, values: MutableMap<String, String>) {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    Column {
                        BackendSubForm(type = type, values = values)
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    // ── S3 sub-form ───────────────────────────────────────────────────────────

    @Test
    fun s3SubForm_rendersQuickSetupHeader() {
        render("s3", mutableStateMapOf())
        composeRule.onNodeWithText("Quick setup").assertIsDisplayed()
    }

    @Test
    fun s3SubForm_rendersProviderDropdown() {
        render("s3", mutableStateMapOf())
        composeRule.onNodeWithText("S3 provider").assertIsDisplayed()
    }

    @Test
    fun s3SubForm_selectWasabi_writesProviderToken() {
        val values = mutableStateMapOf<String, String>()
        render("s3", values)

        // Open the dropdown by clicking the prompt text shown in the field.
        composeRule.onNodeWithText("Choose a provider…").performClick()
        composeRule.waitForIdle()

        // Click the Wasabi item in the expanded menu.
        composeRule.onNodeWithText("Wasabi").performClick()
        composeRule.waitForIdle()

        assertThat(values["provider"]).isEqualTo("Wasabi")
    }

    @Test
    fun s3SubForm_selectAws_writesProviderToken() {
        val values = mutableStateMapOf<String, String>()
        render("s3", values)

        composeRule.onNodeWithText("Choose a provider…").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Amazon S3 (AWS)").performClick()
        composeRule.waitForIdle()

        assertThat(values["provider"]).isEqualTo("AWS")
    }

    @Test
    fun s3SubForm_selectMinio_writesProviderToken() {
        val values = mutableStateMapOf<String, String>()
        render("s3", values)

        composeRule.onNodeWithText("Choose a provider…").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("MinIO").performClick()
        composeRule.waitForIdle()

        assertThat(values["provider"]).isEqualTo("Minio")
    }

    @Test
    fun s3SubForm_afterSelection_updatesDisplayedLabel() {
        val values = mutableStateMapOf<String, String>()
        render("s3", values)

        composeRule.onNodeWithText("Choose a provider…").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Wasabi").performClick()
        composeRule.waitForIdle()

        // The field now shows the selected label instead of the prompt.
        composeRule.onNodeWithText("Wasabi").assertIsDisplayed()
    }

    // ── WebDAV sub-form ───────────────────────────────────────────────────────

    @Test
    fun webdavSubForm_rendersQuickSetupHeader() {
        render("webdav", mutableStateMapOf())
        composeRule.onNodeWithText("Quick setup").assertIsDisplayed()
    }

    @Test
    fun webdavSubForm_rendersVendorDropdown() {
        render("webdav", mutableStateMapOf())
        composeRule.onNodeWithText("WebDAV vendor").assertIsDisplayed()
    }

    @Test
    fun webdavSubForm_selectNextcloud_writesVendorToken() {
        val values = mutableStateMapOf<String, String>()
        render("webdav", values)

        composeRule.onNodeWithText("Choose a vendor…").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Nextcloud").performClick()
        composeRule.waitForIdle()

        assertThat(values["vendor"]).isEqualTo("nextcloud")
    }

    @Test
    fun webdavSubForm_selectOwnCloud_writesVendorToken() {
        val values = mutableStateMapOf<String, String>()
        render("webdav", values)

        composeRule.onNodeWithText("Choose a vendor…").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("ownCloud").performClick()
        composeRule.waitForIdle()

        assertThat(values["vendor"]).isEqualTo("owncloud")
    }

    @Test
    fun webdavSubForm_selectSharePoint_writesVendorToken() {
        val values = mutableStateMapOf<String, String>()
        render("webdav", values)

        composeRule.onNodeWithText("Choose a vendor…").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("SharePoint").performClick()
        composeRule.waitForIdle()

        assertThat(values["vendor"]).isEqualTo("sharepoint")
    }

    // ── SFTP sub-form ─────────────────────────────────────────────────────────

    @Test
    fun sftpSubForm_rendersQuickSetupHeader() {
        render("sftp", mutableStateMapOf())
        composeRule.onNodeWithText("Quick setup").assertIsDisplayed()
    }

    @Test
    fun sftpSubForm_rendersImportKeyButton() {
        render("sftp", mutableStateMapOf())
        composeRule.onNodeWithText("Import private key from file").assertIsDisplayed()
    }

    @Test
    fun sftpSubForm_initialState_doesNotShowKeyLoadedStatus() {
        render("sftp", mutableStateMapOf())
        // No file has been picked yet — neither a "Key loaded" nor an error message should appear.
        composeRule.onNodeWithText("Key loaded", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Couldn't read key file", substring = true).assertDoesNotExist()
    }

    @Test
    fun sftpSubForm_valuesMap_initiallyHasNoKeyPem() {
        val values = mutableStateMapOf<String, String>()
        render("sftp", values)
        assertThat(values.containsKey("key_pem")).isFalse()
    }

    // ── Unknown type renders nothing ──────────────────────────────────────────

    @Test
    fun unknownType_doesNotRenderQuickSetupHeader() {
        render("drive", mutableStateMapOf())
        composeRule.onNodeWithText("Quick setup").assertDoesNotExist()
    }

    @Test
    fun unknownType_doesNotRenderProviderDropdown() {
        render("drive", mutableStateMapOf())
        composeRule.onNodeWithText("S3 provider").assertDoesNotExist()
    }

    @Test
    fun unknownType_doesNotRenderImportKeyButton() {
        render("drive", mutableStateMapOf())
        composeRule.onNodeWithText("Import private key from file").assertDoesNotExist()
    }
}
