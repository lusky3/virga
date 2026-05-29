package app.lusk.virga.ui.theme

import androidx.compose.ui.graphics.Color

// Brand seed palette — used as the fallback when dynamic color is unavailable
// (pre-Android 12) or disabled by the user.

// Light-theme primary / secondary
val VirgaBlue = Color(0xFF1E6FD9)
val VirgaTeal = Color(0xFF1FA8A0)
val VirgaError = Color(0xFFBA1A1A)

// Dark-theme primary (lighter variant for contrast on dark surfaces)
val VirgaBlueDark = Color(0xFF0B3C7A)          // kept — deep brand navy
val VirgaBlueDarkPrimary = Color(0xFF9FBEF7)   // M3-style tonally-elevated blue for dark bg
val VirgaBlueDarkOnPrimary = Color(0xFF003070) // text/icon on DarkPrimary surface
val VirgaBlueDarkContainer = Color(0xFF0B4DAB) // container behind icons in dark mode
val VirgaBlueDarkOnContainer = Color(0xFFD6E4FF) // content inside that container
val VirgaTealDark = Color(0xFF5FD4CC)          // secondary tonal shift for dark mode

// Semantic success color used in history/status chips
val VirgaSuccess = Color(0xFF2E7D32)           // light-theme success green
val VirgaSuccessDark = Color(0xFF81C784)       // accessible green on dark surfaces
