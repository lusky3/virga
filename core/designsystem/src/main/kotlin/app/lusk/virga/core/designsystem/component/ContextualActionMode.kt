package app.lusk.virga.core.designsystem.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Shared contextual-action-mode top bar (BRAND §11, UX_VISION §6): one selection
 * UX across Sync tasks, the file browser, and conflicts instead of each surface
 * reinventing it. Shows a count [title], a Close affordance to exit selection,
 * and a caller-supplied [actions] slot for the bulk operations of that surface.
 *
 * Selection state itself stays with each screen/VM; this is the consistent
 * visual + interaction shell. Pair entering selection with [rememberLongPressHaptic].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    title: String,
    onClear: () -> Unit,
    clearContentDescription: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, contentDescription = clearContentDescription)
            }
        },
        actions = actions,
    )
}

/**
 * A ready-to-call haptic for entering selection mode / long-press affordances,
 * so every surface fires the same [HapticFeedbackType.LongPress] tick.
 */
@Composable
fun rememberLongPressHaptic(): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) { { haptics.performHapticFeedback(HapticFeedbackType.LongPress) } }
}
