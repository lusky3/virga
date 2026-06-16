package app.lusk.virga.feature.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Render-coverage tests for [ScheduleTimesEditor].
 *
 * Exercises both branches:
 *   - Empty scheduleTimes  → single-time stepper + "Add time" text button
 *   - Non-empty scheduleTimes → FilterChip per time + "Add time" chip
 *
 * Also exercises AddTimeChip's editing state by clicking the "Add time" chip
 * in the non-empty branch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScheduleTimesEditorRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- empty-list branch ---------------------------------------------------

    @Test
    fun scheduleTimesEditor_emptyList_showsTimeLabelAndAddTimeButton() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    ScheduleTimesEditor(
                        state = ScheduleTimesState(
                            singleHour = 9,
                            singleMinute = 0,
                            scheduleTimes = emptyList(),
                        ),
                        onSingleTimeChange = { _, _ -> },
                        onAddTime = {},
                        onRemoveTime = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Time").assertIsDisplayed()
        composeRule.onNodeWithText("Add time").assertIsDisplayed()
    }

    @Test
    fun scheduleTimesEditor_emptyList_showsStepperValues() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    ScheduleTimesEditor(
                        state = ScheduleTimesState(
                            singleHour = 14,
                            singleMinute = 30,
                            scheduleTimes = emptyList(),
                        ),
                        onSingleTimeChange = { _, _ -> },
                        onAddTime = {},
                        onRemoveTime = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("14").assertIsDisplayed()
        composeRule.onNodeWithText("30").assertIsDisplayed()
    }

    @Test
    fun scheduleTimesEditor_emptyList_clickAddTimeCalls_onAddTime() {
        var calledWith: Int? = null
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    ScheduleTimesEditor(
                        state = ScheduleTimesState(
                            singleHour = 9,
                            singleMinute = 30,
                            scheduleTimes = emptyList(),
                        ),
                        onSingleTimeChange = { _, _ -> },
                        onAddTime = { calledWith = it },
                        onRemoveTime = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Add time").performClick()
        composeRule.waitForIdle()
        assert(calledWith == 9 * 60 + 30) {
            "Expected onAddTime(${9 * 60 + 30}) but got $calledWith"
        }
    }

    // --- non-empty branch ----------------------------------------------------

    @Test
    fun scheduleTimesEditor_withTimes_showsChipsAndTimeLabelText() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    ScheduleTimesEditor(
                        state = ScheduleTimesState(
                            singleHour = 0,
                            singleMinute = 0,
                            scheduleTimes = listOf(9 * 60, 18 * 60 + 30),
                        ),
                        onSingleTimeChange = { _, _ -> },
                        onAddTime = {},
                        onRemoveTime = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Time").assertIsDisplayed()
        composeRule.onNodeWithText("09:00").assertIsDisplayed()
        composeRule.onNodeWithText("18:30").assertIsDisplayed()
    }

    @Test
    fun scheduleTimesEditor_withTimes_showsAddTimeChip() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    ScheduleTimesEditor(
                        state = ScheduleTimesState(
                            singleHour = 0,
                            singleMinute = 0,
                            scheduleTimes = listOf(8 * 60),
                        ),
                        onSingleTimeChange = { _, _ -> },
                        onAddTime = {},
                        onRemoveTime = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Add time").assertIsDisplayed()
    }

    @Test
    fun scheduleTimesEditor_withTimes_clickAddTimeChip_opensInlineStepper() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    ScheduleTimesEditor(
                        state = ScheduleTimesState(
                            singleHour = 0,
                            singleMinute = 0,
                            scheduleTimes = listOf(8 * 60),
                        ),
                        onSingleTimeChange = { _, _ -> },
                        onAddTime = {},
                        onRemoveTime = {},
                    )
                }
            }
        }
        // Click the "Add time" chip to enter the inline editing state.
        composeRule.onNodeWithText("Add time").performClick()
        composeRule.waitForIdle()
        // The inline stepper should now show the default draft time "09:00".
        composeRule.onNodeWithText("09").assertIsDisplayed()
        composeRule.onNodeWithText("00").assertIsDisplayed()
    }

    @Test
    fun scheduleTimesEditor_withTimes_chipClick_calls_onRemoveTime() {
        var removedIndex: Int? = null
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    ScheduleTimesEditor(
                        state = ScheduleTimesState(
                            singleHour = 0,
                            singleMinute = 0,
                            scheduleTimes = listOf(10 * 60),
                        ),
                        onSingleTimeChange = { _, _ -> },
                        onAddTime = {},
                        onRemoveTime = { removedIndex = it },
                    )
                }
            }
        }
        composeRule.onNodeWithText("10:00").performClick()
        composeRule.waitForIdle()
        assert(removedIndex == 0) { "Expected onRemoveTime(0) but got $removedIndex" }
    }
}
