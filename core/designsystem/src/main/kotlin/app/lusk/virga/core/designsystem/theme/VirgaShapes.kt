package app.lusk.virga.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shape scale (BRAND §7).
 *
 * Maps the Material 3 shape slots to Virga's rounding scale. Wire this into the
 * `MaterialTheme(shapes = VirgaShapes, ...)` call in Theme.kt so components pick
 * up the brand corners automatically.
 *
 * extraSmall 4 / small 8 / medium 12 / large 16 / extraLarge 28 dp.
 */
val VirgaShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Dedicated shape for the hero card / atmospheric gradient surface.
 *
 * Matches the M3 `extraLarge` rounding (28.dp) but is exposed as its own token
 * so hero usages read intentionally and stay decoupled from the shape scale.
 */
val VirgaHeroCardShape = RoundedCornerShape(28.dp)
