package app.lusk.virga.core.designsystem.back

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Lets in-composition overlays (the custom [app.lusk.virga.core.designsystem.component.VirgaBottomSheet])
 * claim the system Back gesture.
 *
 * The app's navigation3 `NavDisplay` owns Back via the `androidx.navigationevent`
 * dispatcher. A `VirgaBottomSheet` renders in the SAME window as `NavDisplay` (unlike
 * a Material3 `ModalBottomSheet`, which lives in a separate dialog window that swallows
 * Back without dismissing under predictive-back), so Back reaches `NavDisplay.onBack`.
 * The host routes `onBack` through [dismissTop] first: an open sheet registers its
 * dismiss callback via [DismissOnBack], so Back closes the sheet before popping the
 * nav back stack.
 */
class OverlayBackRegistry {
    private val dismissers: SnapshotStateList<() -> Unit> = mutableStateListOf()

    /** True while at least one overlay is open. */
    val hasOverlay: Boolean get() = dismissers.isNotEmpty()

    fun register(onDismiss: () -> Unit) = dismissers.add(onDismiss)

    fun unregister(onDismiss: () -> Unit) {
        dismissers.remove(onDismiss)
    }

    /**
     * Dismisses the most-recently-opened overlay (LIFO). Returns true if one was
     * dismissed; false when none are open so the caller falls through to nav back.
     */
    fun dismissTop(): Boolean {
        val top = dismissers.lastOrNull() ?: return false
        top()
        return true
    }
}

/** Provides the active [OverlayBackRegistry]; null when no host installed one. */
val LocalOverlayBackRegistry = staticCompositionLocalOf<OverlayBackRegistry?> { null }

/**
 * Registers [onDismiss] with the host [OverlayBackRegistry] while this composable is
 * in the composition, so the navigation host's Back routes here. No-op when no
 * registry is installed (e.g. isolated previews/tests).
 */
@Composable
fun DismissOnBack(onDismiss: () -> Unit) {
    val registry = LocalOverlayBackRegistry.current ?: return
    val latest by rememberUpdatedState(onDismiss)
    DisposableEffect(registry) {
        val entry: () -> Unit = { latest() }
        registry.register(entry)
        onDispose { registry.unregister(entry) }
    }
}
