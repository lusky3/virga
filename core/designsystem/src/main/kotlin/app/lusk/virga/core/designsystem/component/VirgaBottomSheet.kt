package app.lusk.virga.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lusk.virga.core.designsystem.back.DismissOnBack
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

/**
 * A modal bottom sheet rendered **in the current window** (not a separate dialog
 * window like Material3's `ModalBottomSheet`). That distinction is the whole point:
 * the app's navigation3 `NavDisplay` owns Back via the navigationevent dispatcher,
 * and a separate dialog window swallows Back without dismissing under predictive-back.
 * Rendering in-window lets Back reach `NavDisplay.onBack`, which routes through the
 * [OverlayBackRegistry][app.lusk.virga.core.designsystem.back.OverlayBackRegistry]
 * (via [DismissOnBack]) to close this sheet.
 *
 * Dismisses on: Back, scrim tap, and the close affordance the [content] provides.
 * Compose it conditionally (`if (visible) VirgaBottomSheet(...) { ... }`); it animates
 * in on first composition and animates out before invoking [onDismiss].
 *
 * @param scrimDescription accessibility label for the scrim "tap to dismiss" target.
 */
@Composable
fun VirgaBottomSheet(
    onDismiss: () -> Unit,
    scrimDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Drives enter (false→true on first composition) and exit (true→false on dismiss)
    // animations. onDismiss fires only once the exit transition has fully settled, so
    // the caller can keep this composed until the slide-out finishes.
    val transition = remember { MutableTransitionState(initialState = false).apply { targetState = true } }
    val animateOut = { transition.targetState = false }

    LaunchedEffect(transition.currentState, transition.targetState) {
        if (!transition.currentState && !transition.targetState) onDismiss()
    }

    // Back closes the sheet (animated) rather than popping the nav stack.
    DismissOnBack(onDismiss = animateOut)

    Box(modifier.fillMaxSize()) {
        // Scrim — tapping it (outside the sheet) dismisses, mirroring a modal sheet.
        AnimatedVisibility(visibleState = transition, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = animateOut,
                    )
                    .semantics { contentDescription = scrimDescription },
            )
        }

        AnimatedVisibility(
            visibleState = transition,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // imePadding lifts the sheet above the keyboard (the activity uses
                // enableEdgeToEdge, so IME insets aren't auto-consumed); navigationBarsPadding
                // keeps content clear of the gesture bar.
                Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
                    DragHandle()
                    content()
                }
            }
        }
    }
}

/** Visual grab handle centered at the top of the sheet (decorative). */
@Composable
private fun DragHandle() {
    Box(Modifier.fillMaxWidth().padding(vertical = VirgaSpacing.sm), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(50),
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(width = 32.dp, height = 4.dp),
        ) {}
    }
}
