package app.lusk.virga.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/**
 * Virga's root theme (BRAND §4, §5, §7, §12).
 *
 * **Brand-first, not brand-erasing (BRAND §4.3).** The complete branded
 * blue/teal schemes ([VirgaLightColorScheme]/[VirgaDarkColorScheme]) are the
 * default. [dynamicColor] now defaults **off** — a user who never opens Settings
 * always sees Virga's identity. The Settings "Match my wallpaper colors" toggle
 * opts into dynamic color.
 *
 * **Dynamic-color-as-tint policy.** When [dynamicColor] is on (Android 12+), we
 * do *not* hand the whole wallpaper palette to the app. We take the dynamic
 * scheme but **keep the brand primary family** (primary/onPrimary/
 * primaryContainer/onPrimaryContainer) so Virga's blue persists; wallpaper
 * accents flow into secondary/tertiary/surfaces. This harmonizes with the
 * device theme without replacing the brand.
 *
 * **Shape + type (BRAND §5, §7).** [VirgaShapes] supplies the brand corner
 * scale; [VirgaTypography] the type voice (see [VirgaDisplayFontFamily] for the
 * optional display typeface).
 *
 * **Expressive + motion (BRAND §12).** The app is wrapped in
 * [MaterialExpressiveTheme] with [MotionScheme.expressive] so components use
 * physics/spring motion (and the Expressive component variants — e.g. the wavy
 * progress indicator — are available). Named motion tokens for nav/list/etc.
 * live in [VirgaMotion]; reduce-motion is honored via [rememberReduceMotion].
 *
 * **Semantic colors (BRAND §4.4, §10).** [LocalVirgaColors] (success/warning/
 * running/info) is provided here; [SyncStatusBadge] and any status surface read
 * it. M3 has no such roles, so this CompositionLocal is the only source.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VirgaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val brandScheme = if (darkTheme) VirgaDarkColorScheme else VirgaLightColorScheme
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val dynamic =
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            // Tint, don't replace: keep the brand primary family, let the
            // wallpaper drive everything else.
            dynamic.copy(
                primary = brandScheme.primary,
                onPrimary = brandScheme.onPrimary,
                primaryContainer = brandScheme.primaryContainer,
                onPrimaryContainer = brandScheme.onPrimaryContainer,
            )
        }
        else -> brandScheme
    }
    val semanticColors = if (darkTheme) VirgaDarkSemanticColors else VirgaLightSemanticColors

    CompositionLocalProvider(LocalVirgaColors provides semanticColors) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            typography = VirgaTypography,
            shapes = VirgaShapes,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}
