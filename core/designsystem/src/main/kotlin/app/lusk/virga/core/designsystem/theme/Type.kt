package app.lusk.virga.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The single display-typeface hook (BRAND §5). Display + Headline styles use this
 * family; Body/Label stay on the platform default for legibility at small sizes.
 *
 * Currently `null` → the platform default is used (a `null` fontFamily on a
 * [TextStyle] is the system default, so the scale is unchanged until a face is
 * dropped in). To adopt a real display typeface, add a **foss-flavor-safe**
 * (OFL/Apache) variable font to `core/designsystem/src/main/res/font/` and point
 * this at it, e.g. `FontFamily(Font(R.font.virga_display))`. A downloadable
 * Google Font provider is intentionally NOT used: it requires Google Play
 * Services, which the F-Droid/foss flavor must not depend on.
 */
val VirgaDisplayFontFamily: FontFamily? = null

/**
 * Intentional M3 type scale for Virga (2026).
 *
 * Design intent:
 *  - Display/Headline: tight tracking, heavy weight — prominent run titles and
 *    empty-state headings; uses [VirgaDisplayFontFamily] when one is set.
 *  - Title: medium weight with slightly relaxed line height — section labels and
 *    list-item primaries feel spacious without being airy.
 *  - Body: slightly expanded letter-spacing at bodySmall for dense chip/label
 *    text; bodyLarge/Medium are workhorse sizes left close to spec.
 *  - Label: all-caps feel avoided — instead moderately-bold SmallCaps-adjacent
 *    weights keep navigation labels and button copy crisp.
 *
 * Display/Headline route through [VirgaDisplayFontFamily] (the single font hook);
 * everything else is FontWeight + tracking only.
 */
val VirgaTypography = Typography(
    // --- Display ---
    displayLarge = TextStyle(
        fontFamily = VirgaDisplayFontFamily,
        fontWeight = FontWeight.W300,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = VirgaDisplayFontFamily,
        fontWeight = FontWeight.W300,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = VirgaDisplayFontFamily,
        fontWeight = FontWeight.W400,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
    ),
    // --- Headline ---
    headlineLarge = TextStyle(
        fontFamily = VirgaDisplayFontFamily,
        fontWeight = FontWeight.W600,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = VirgaDisplayFontFamily,
        fontWeight = FontWeight.W600,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = VirgaDisplayFontFamily,
        fontWeight = FontWeight.W500,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    ),
    // --- Title ---
    titleLarge = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.W500,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.W500,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    // --- Body ---
    bodyLarge = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    // --- Label ---
    labelLarge = TextStyle(
        fontWeight = FontWeight.W500,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.W500,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.W500,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)
