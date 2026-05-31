package app.lusk.virga.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush

/**
 * Hero gradients (BRAND §4.5) — the "atmosphere", used sparingly.
 *
 * The brand mark is a soft downward blue→teal streak. These gradients are the
 * large-surface expression of that idea (hero headers, empty states). Defined
 * ONCE here so every hero surface shares the exact same brush; never re-build a
 * `linearGradient` inline at the call site.
 *
 * Light: VirgaBlue → VirgaTeal.
 * Dark:  VirgaBlueDark → VirgaTeal (deep navy start keeps contrast on dark UI).
 *
 * Brand color vals live in Color.kt in this same package and are referenced
 * directly here.
 */
object VirgaGradients {
    /** Light-theme hero brush: bright brand blue fading into teal. */
    val heroLight: Brush = Brush.linearGradient(listOf(VirgaBlue, VirgaTeal))

    /** Dark-theme hero brush: deep navy fading into teal. */
    val heroDark: Brush = Brush.linearGradient(listOf(VirgaBlueDark, VirgaTeal))

    /**
     * Theme-aware hero brush. Reads the current system dark/light state and
     * returns the matching gradient. `@ReadOnlyComposable` because it only
     * reads composition state and emits nothing.
     */
    @Composable
    @ReadOnlyComposable
    fun hero(): Brush = if (isSystemInDarkTheme()) heroDark else heroLight
}
