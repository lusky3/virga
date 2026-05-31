package app.lusk.virga.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Complete Material 3 color schemes for Virga (BRAND §4.2).
//
// These fill EVERY M3 role so the brand identity reads on every surface, not
// just primary/secondary/error. The tonal values follow Material Theme Builder
// output for the three brand seeds:
//   - primary   seed #1E6FD9 (VirgaBlue)
//   - secondary seed #1FA8A0 (VirgaTeal)
//   - tertiary  a harmonious soft indigo/violet that sits between blue and teal
//   - error     seed #BA1A1A (VirgaError)
//
// On-color pairs are chosen so body text clears WCAG-AA (>= 4.5:1) against its
// container. Surface containers ascend in tone for elevation (BRAND §4.6).
// Existing brand vals (VirgaBlueDarkPrimary, VirgaTealDark, ...) live in
// Color.kt in this same package and are referenced directly.

// ---------------------------------------------------------------------------
// LIGHT
// ---------------------------------------------------------------------------
// Anchored on VirgaBlue. Light backgrounds use a near-white blue-tinted neutral
// so the chrome feels brand-adjacent without tinting content.
val VirgaLightColorScheme: ColorScheme = lightColorScheme(
    // Primary — brand blue, white text on it.
    primary = VirgaBlue,                          // #1E6FD9
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF),         // pale blue fill
    onPrimaryContainer = Color(0xFF001A41),       // near-navy text

    // Secondary — brand teal.
    secondary = VirgaTeal,                        // #1FA8A0
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB8F0E9),       // pale teal fill
    onSecondaryContainer = Color(0xFF00201D),     // deep teal-black text

    // Tertiary — harmonious soft indigo/violet bridging blue + teal.
    tertiary = Color(0xFF5A5C9E),                 // muted indigo
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE2DFFF),        // pale lilac fill
    onTertiaryContainer = Color(0xFF161A56),      // deep indigo text

    // Error.
    error = VirgaError,                           // #BA1A1A
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    // Background / surface neutrals (subtle cool tint).
    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1A1B1F),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1A1B1F),
    surfaceVariant = Color(0xFFE0E2EC),           // neutral-variant fill
    onSurfaceVariant = Color(0xFF43474E),         // AA on surfaceVariant

    // Tonal-elevation surface containers (lowest -> highest).
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainer = Color(0xFFF1ECF4),
    surfaceContainerHigh = Color(0xFFEBE6EE),
    surfaceContainerHighest = Color(0xFFE5E0E9),

    // Inverse (e.g. snackbars) + inverse primary accent.
    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF1F0F4),
    inversePrimary = VirgaBlueDarkPrimary,        // #9FBEF7 reads on dark inverse

    // Lines + scrim.
    outline = Color(0xFF73777F),                  // AA divider/border on surface
    outlineVariant = Color(0xFFC3C7CF),           // subtle hairline
    scrim = Color(0xFF000000),
)

// ---------------------------------------------------------------------------
// DARK
// ---------------------------------------------------------------------------
// Reuses the established dark brand anchors from Color.kt where they fit, and
// derives the teal-based secondary from VirgaTealDark.
val VirgaDarkColorScheme: ColorScheme = darkColorScheme(
    // Primary — tonally elevated brand blue (existing dark anchors).
    primary = VirgaBlueDarkPrimary,               // #9FBEF7
    onPrimary = VirgaBlueDarkOnPrimary,           // #003070
    primaryContainer = VirgaBlueDarkContainer,    // #0B4DAB
    onPrimaryContainer = VirgaBlueDarkOnContainer,// #D6E4FF

    // Secondary — teal shifted lighter for dark surfaces.
    secondary = VirgaTealDark,                    // #5FD4CC
    onSecondary = Color(0xFF003733),              // dark teal text on light teal
    secondaryContainer = Color(0xFF00504B),       // deep teal fill
    onSecondaryContainer = Color(0xFFB8F0E9),     // pale teal content

    // Tertiary — lighter indigo to match the light scheme's bridge hue.
    tertiary = Color(0xFFC3C2FF),                 // light indigo
    onTertiary = Color(0xFF2B2C6E),
    tertiaryContainer = Color(0xFF424485),        // mid indigo fill
    onTertiaryContainer = Color(0xFFE2DFFF),      // pale lilac content

    // Error.
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    // Background / surface neutrals (dark, faintly cool).
    background = Color(0xFF1A1B1F),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF1A1B1F),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF43474E),           // neutral-variant fill
    onSurfaceVariant = Color(0xFFC3C7CF),         // AA on surfaceVariant

    // Tonal-elevation surface containers (lowest -> highest).
    surfaceContainerLowest = Color(0xFF0E0E11),
    surfaceContainerLow = Color(0xFF1A1B1F),
    surfaceContainer = Color(0xFF1E1F23),
    surfaceContainerHigh = Color(0xFF292A2E),
    surfaceContainerHighest = Color(0xFF343539),

    // Inverse (e.g. snackbars) + inverse primary accent.
    inverseSurface = Color(0xFFE3E2E6),
    inverseOnSurface = Color(0xFF2F3033),
    inversePrimary = VirgaBlue,                   // #1E6FD9 reads on light inverse

    // Lines + scrim.
    outline = Color(0xFF8D9199),                  // AA divider/border on dark surface
    outlineVariant = Color(0xFF43474E),           // subtle hairline
    scrim = Color(0xFF000000),
)
