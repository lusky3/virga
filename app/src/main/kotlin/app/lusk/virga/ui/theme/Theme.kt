package app.lusk.virga.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = VirgaBlue,
    secondary = VirgaTeal,
    error = VirgaError,
)

private val DarkColors = darkColorScheme(
    primary = VirgaBlueDarkPrimary,
    onPrimary = VirgaBlueDarkOnPrimary,
    primaryContainer = VirgaBlueDarkContainer,
    onPrimaryContainer = VirgaBlueDarkOnContainer,
    secondary = VirgaTealDark,
    error = VirgaError,
)

/**
 * App theme. Uses Material You dynamic color on Android 12+ when [dynamicColor]
 * is enabled; otherwise the brand palette.
 */
@Composable
fun VirgaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = VirgaTypography,
        content = content,
    )
}
