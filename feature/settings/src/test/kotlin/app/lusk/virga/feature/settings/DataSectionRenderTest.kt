package app.lusk.virga.feature.settings

import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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
 * Render-coverage and interaction tests for [DataSection].
 *
 * Exercises:
 *   - section title "Data & reset" renders
 *   - Clear cache / Clear logs / Reset app buttons render
 *   - tapping each button shows its confirmation dialog
 *   - confirming the cache dialog invokes [onCacheClear] and dismisses
 *   - cancelling the cache dialog does NOT invoke [onCacheClear]
 *   - confirming the logs dialog invokes [onLogsClear]
 *   - cancelling the logs dialog does NOT invoke [onLogsClear]
 *   - confirming the reset dialog invokes [onReset]
 *   - cancelling the reset dialog does NOT invoke [onReset]
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DataSectionRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val roborazziRule = RoborazziRule(
        composeRule = composeRule,
        captureRoot = composeRule.onRoot(),
        options = RoborazziRule.Options(outputDirectoryPath = "src/test/snapshots"),
    )

    // --- static render ---

    @Test
    fun dataSection_showsSectionTitle() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataSection(onCacheClear = {}, onLogsClear = {}, onReset = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Data & reset").assertIsDisplayed()
    }

    @Test
    fun dataSection_showsClearCacheButton() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataSection(onCacheClear = {}, onLogsClear = {}, onReset = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Clear cache").assertIsDisplayed()
    }

    @Test
    fun dataSection_showsClearLogsButton() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataSection(onCacheClear = {}, onLogsClear = {}, onReset = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Clear logs").assertIsDisplayed()
    }

    @Test
    fun dataSection_showsResetAppButton() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataSection(onCacheClear = {}, onLogsClear = {}, onReset = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reset app").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage()
    }

    // --- Clear cache dialog ---

    @Test
    fun dataSection_tapClearCache_showsConfirmDialog() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataSection(onCacheClear = {}, onLogsClear = {}, onReset = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Clear cache").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        // AlertDialog renders in an overlay; assertExists confirms presence in the tree.
        composeRule.onNodeWithText("Clear cache?").assertExists()
        composeRule.onNodeWithText("Confirm").assertExists()
        composeRule.onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun dataSection_clearCache_confirm_invokesCacheClearCallback() {
        var invoked = false
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataSection(onCacheClear = { invoked = true }, onLogsClear = {}, onReset = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Clear cache").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Confirm").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertThat(invoked).isTrue()
    }

    @Test
    fun dataSection_clearCache_confirm_dismissesDialog() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataSection(onCacheClear = {}, onLogsClear = {}, onReset = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Clear cache").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Confirm").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Clear cache?").assertDoesNotExist()
    }

    @Test
    fun dataSection_clearCache_cancel_doesNotInvokeCallback() {
        var invoked = false
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataSection(onCacheClear = { invoked = true }, onLogsClear = {}, onReset = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Clear cache").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertThat(invoked).isFalse()
    }

    @Test
    fun dataSection_clearCache_cancel_dismissesDialog() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataSection(onCacheClear = {}, onLogsClear = {}, onReset = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Clear cache").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Clear cache?").assertDoesNotExist()
    }

    // --- Clear logs dialog ---

    @Test
    fun dataSection_tapClearLogs_showsConfirmDialog() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataSection(onCacheClear = {}, onLogsClear = {}, onReset = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Clear logs").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Clear logs?").assertExists()
        composeRule.onNodeWithText("Confirm").assertExists()
        composeRule.onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun dataSection_clearLogs_confirm_invokesLogsClearCallback() {
        var invoked = false
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataSection(onCacheClear = {}, onLogsClear = { invoked = true }, onReset = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Clear logs").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Confirm").performClick()
        composeRule.waitForIdle()
        assertThat(invoked).isTrue()
    }

    @Test
    fun dataSection_clearLogs_cancel_doesNotInvokeCallback() {
        var invoked = false
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataSection(onCacheClear = {}, onLogsClear = { invoked = true }, onReset = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Clear logs").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()
        assertThat(invoked).isFalse()
    }

    // --- Reset app dialog ---

    @Test
    fun dataSection_tapResetApp_showsDestructiveDialog() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataSection(onCacheClear = {}, onLogsClear = {}, onReset = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reset app").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reset app?").assertExists()
        composeRule.onNodeWithText("Confirm").assertExists()
        composeRule.onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun dataSection_resetApp_confirm_invokesResetCallback() {
        var invoked = false
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataSection(onCacheClear = {}, onLogsClear = {}, onReset = { invoked = true })
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reset app").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Confirm").performClick()
        composeRule.waitForIdle()
        assertThat(invoked).isTrue()
    }

    @Test
    fun dataSection_resetApp_confirm_dismissesDialog() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataSection(onCacheClear = {}, onLogsClear = {}, onReset = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reset app").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Confirm").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reset app?").assertDoesNotExist()
    }

    @Test
    fun dataSection_resetApp_cancel_doesNotInvokeCallback() {
        var invoked = false
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataSection(onCacheClear = {}, onLogsClear = {}, onReset = { invoked = true })
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reset app").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()
        assertThat(invoked).isFalse()
    }

    @Test
    fun dataSection_resetApp_cancel_dismissesDialog() {
        composeRule.setContent {
            VirgaTheme {
                Surface {
                    DataSection(onCacheClear = {}, onLogsClear = {}, onReset = {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reset app").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Reset app?").assertDoesNotExist()
    }
}
