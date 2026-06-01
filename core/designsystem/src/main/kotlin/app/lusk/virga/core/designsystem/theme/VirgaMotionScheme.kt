package app.lusk.virga.core.designsystem.theme

import android.provider.Settings
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Named motion tokens for Virga (BRAND §12).
 *
 * Features should reference these instead of hand-rolling `tween(...)` calls, so durations
 * and easings stay consistent across navigation, list reveals, and success feedback.
 *
 * NOTE on Material 3 Expressive: the Theme integrator passes `MotionScheme.expressive()` to
 * `MaterialExpressiveTheme` separately. This object is the *named token layer* that sits on
 * top of that — it does not configure the theme itself.
 */
object VirgaMotion {

    // --- Durations (milliseconds) -------------------------------------------------------------

    /** Top-level navigation transitions (shared-axis style screen changes). */
    const val NavDurationMs = 350

    /** Per-item enter animation when list/grid content appears. Kept short to feel snappy. */
    const val ListItemEnterMs = 220

    /** Success confirmation (e.g. an animated checkmark). Slightly longer for emphasis. */
    const val SuccessTickMs = 450

    // --- Easings ------------------------------------------------------------------------------

    /**
     * Standard easing for navigation: accelerate out, decelerate in. Matches Material's
     * emphasized/standard feel for elements moving across the screen.
     */
    val navEasing: Easing = FastOutSlowInEasing

    // --- Specs --------------------------------------------------------------------------------

    /**
     * Tween for navigation / shared-axis transitions. Generic over the animated type so it can
     * drive offsets, alpha, scale, etc.
     */
    fun <T> navTween(): TweenSpec<T> = tween(durationMillis = NavDurationMs, easing = navEasing)

    /**
     * Tween for list-item enter animations. Uses [LinearOutSlowInEasing] (decelerate) so items
     * settle gently into place as they appear.
     */
    fun <T> listEnterTween(): TweenSpec<T> =
        tween(durationMillis = ListItemEnterMs, easing = LinearOutSlowInEasing)
}

/**
 * Provides the [SharedTransitionScope] that wraps NavDisplay to children that
 * want to apply [SharedTransitionScope.sharedBounds] or [SharedTransitionScope.sharedElement]
 * across Nav3 entries. Provided by VirgaNavHost; null everywhere else.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/**
 * Reads the system "remove animations" / reduced-motion preference.
 *
 * Returns `true` when the OS animator duration scale is set to `0` (Settings > Accessibility >
 * Remove animations, or Developer Options > Animator duration scale = off). Callers should use
 * this to skip or shorten non-essential motion for users who opt out.
 *
 * The value is [remember]ed for the lifetime of the composition; it does not observe live
 * changes to the setting (a config-affecting setting change typically recreates the activity).
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        scale == 0f
    }
}
