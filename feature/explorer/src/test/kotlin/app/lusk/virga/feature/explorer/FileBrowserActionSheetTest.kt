package app.lusk.virga.feature.explorer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lusk.virga.core.common.model.FileItem
import app.lusk.virga.core.designsystem.theme.VirgaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FileBrowserActionSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val item = FileItem(
        name = "photo.jpg",
        path = "Photos/photo.jpg",
        isDir = false,
        size = 102_400L,
        modTimeEpochMs = null,
    )

    private fun setContent(
        onDismiss: () -> Unit = {},
        onOpen: () -> Unit = {},
        onShare: () -> Unit = {},
        onSave: () -> Unit = {},
        onUpload: () -> Unit = {},
    ) {
        composeRule.setContent {
            VirgaTheme {
                ActionSheetContent(
                    item = item,
                    onDismiss = onDismiss,
                    onOpen = onOpen,
                    onShare = onShare,
                    onSave = onSave,
                    onUpload = onUpload,
                )
            }
        }
    }

    @Test
    fun `renders filename as header`() {
        setContent()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("photo.jpg").assertIsDisplayed()
    }

    @Test
    fun `all four action labels are visible`() {
        setContent()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Open").assertIsDisplayed()
        composeRule.onNodeWithText("Share").assertIsDisplayed()
        composeRule.onNodeWithText("Save to device").assertIsDisplayed()
        composeRule.onNodeWithText("Upload file here").assertIsDisplayed()
    }

    @Test
    fun `clicking Open invokes onOpen and onDismiss`() {
        var openCalled = false
        var dismissCalled = false
        setContent(onDismiss = { dismissCalled = true }, onOpen = { openCalled = true })
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Open").performClick()
        composeRule.waitForIdle()
        assert(openCalled) { "onOpen was not called" }
        assert(dismissCalled) { "onDismiss was not called" }
    }

    @Test
    fun `clicking Share invokes onShare and onDismiss`() {
        var shareCalled = false
        var dismissCalled = false
        setContent(onDismiss = { dismissCalled = true }, onShare = { shareCalled = true })
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Share").performClick()
        composeRule.waitForIdle()
        assert(shareCalled) { "onShare was not called" }
        assert(dismissCalled) { "onDismiss was not called" }
    }

    @Test
    fun `clicking Save invokes onSave and onDismiss`() {
        var saveCalled = false
        var dismissCalled = false
        setContent(onDismiss = { dismissCalled = true }, onSave = { saveCalled = true })
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Save to device").performClick()
        composeRule.waitForIdle()
        assert(saveCalled) { "onSave was not called" }
        assert(dismissCalled) { "onDismiss was not called" }
    }

    @Test
    fun `clicking Upload invokes onUpload and onDismiss`() {
        var uploadCalled = false
        var dismissCalled = false
        setContent(onDismiss = { dismissCalled = true }, onUpload = { uploadCalled = true })
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Upload file here").performClick()
        composeRule.waitForIdle()
        assert(uploadCalled) { "onUpload was not called" }
        assert(dismissCalled) { "onDismiss was not called" }
    }
}
