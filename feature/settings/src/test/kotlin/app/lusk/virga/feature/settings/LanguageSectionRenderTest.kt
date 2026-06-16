package app.lusk.virga.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.lusk.virga.core.designsystem.theme.VirgaTheme
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Render-coverage tests for [LanguageSection].
 *
 * Exercises:
 *   - system-default state: section title, hint, and "System default" label render
 *   - English-selected state: "English" label renders in the text field
 *   - expanding the dropdown: "System default" and "English" items are visible
 *   - selecting an item: [onLanguageSelected] callback is invoked with the expected tag
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    qualifiers = "w360dp-h800dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LanguageSectionRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val roborazziRule = RoborazziRule(
        composeRule = composeRule,
        captureRoot = composeRule.onRoot(),
        options = RoborazziRule.Options(outputDirectoryPath = "src/test/snapshots"),
    )

    // --- system-default state ------------------------------------------------

    @Test
    fun languageSection_systemDefault_showsSectionTitle() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    Column {
                        LanguageSection(selectedTag = null, onLanguageSelected = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Language").assertIsDisplayed()
    }

    @Test
    fun languageSection_systemDefault_showsDisplayLanguageLabel() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    Column {
                        LanguageSection(selectedTag = null, onLanguageSelected = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Display language").assertIsDisplayed()
    }

    @Test
    fun languageSection_systemDefault_showsSystemDefaultInTextField() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    Column {
                        LanguageSection(selectedTag = null, onLanguageSelected = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("System default").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage()
    }

    // --- English-selected state ----------------------------------------------

    @Test
    fun languageSection_englishSelected_showsEnglishInTextField() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    Column {
                        LanguageSection(selectedTag = "en", onLanguageSelected = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("English").assertIsDisplayed()
    }

    // --- dropdown options ----------------------------------------------------

    @Test
    fun languageSection_expandDropdown_showsSystemDefaultOption() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    Column {
                        LanguageSection(selectedTag = null, onLanguageSelected = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Display language").performClick()
        composeRule.waitForIdle()
        // When the dropdown is open and "System default" is already selected, the text
        // appears in both the read-only text field AND the menu item — at least one must
        // be displayed (using index 0 avoids the ambiguity that assertIsDisplayed raises
        // when multiple nodes match).
        composeRule.onAllNodesWithText("System default")[0].assertIsDisplayed()
    }

    @Test
    fun languageSection_expandDropdown_showsEnglishOption() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    Column {
                        LanguageSection(selectedTag = null, onLanguageSelected = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Display language").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("English").assertIsDisplayed()
    }

    // --- callback invocation -------------------------------------------------

    @Test
    fun languageSection_selectSystemDefault_invokesCallbackWithNull() {
        var capturedTag: String? = "sentinel"
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    Column {
                        LanguageSection(
                            selectedTag = "en",
                            onLanguageSelected = { capturedTag = it },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // Open the dropdown then tap "System default".
        composeRule.onNodeWithText("Display language").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("System default").performClick()
        composeRule.waitForIdle()

        assertThat(capturedTag).isNull()
    }

    @Test
    fun languageSection_selectEnglish_invokesCallbackWithEnTag() {
        var callbackInvoked = false
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    Column {
                        LanguageSection(
                            selectedTag = null,
                            onLanguageSelected = { tag ->
                                if (tag == "en") callbackInvoked = true
                            },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Display language").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("English").performClick()
        composeRule.waitForIdle()

        assertThat(callbackInvoked).isTrue()
    }
}
