package app.lusk.virga.core.designsystem.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing & layout tokens (BRAND §8).
 *
 * The app uses ONLY these step values for padding, gaps, and insets so that
 * rhythm stays consistent across every screen. Reach for the named token
 * (`VirgaSpacing.md`) instead of hard-coding raw `dp` literals.
 *
 * Scale: 4 / 8 / 16 / 24 / 32 dp (xs → xl).
 */
object VirgaSpacing {
    /** 4.dp — hairline gaps, icon-to-label nudges. */
    val xs: Dp = 4.dp

    /** 8.dp — tight intra-component spacing. */
    val sm: Dp = 8.dp

    /** 16.dp — default content padding / list item spacing. */
    val md: Dp = 16.dp

    /** 24.dp — section separation, card padding. */
    val lg: Dp = 24.dp

    /** 32.dp — screen-level gutters, hero breathing room. */
    val xl: Dp = 32.dp
}
