package app.lusk.virga.core.designsystem.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * First-class semantic / status colors that Material 3 does not model natively
 * (BRAND §4.4). Material 3 ships primary / secondary / tertiary / error roles
 * only — there is no standard "success", "warning", "running", or "info" role.
 * Virga surfaces sync state heavily (a remote can be idle, running, failed,
 * stale, or healthy) so these are needed everywhere status is communicated.
 *
 * Each role follows the M3 tonal pattern: a vivid [base] for icons / accents,
 * an [on<Role>] color for content drawn on top of that base, a low-chroma
 * [<role>Container] for filled chips / banners, and an [on<Role>Container] for
 * text/icons inside the container. Container/on-container pairs are tuned for
 * WCAG-AA contrast (>= 4.5:1 for body text, >= 3:1 for large text/icons).
 *
 * Provide an instance via [LocalVirgaColors] inside the app theme; read it with
 * `LocalVirgaColors.current`.
 */
data class VirgaSemanticColors(
    // Success — completed sync, healthy remote, verified state.
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    // Warning — recoverable issues, stale data, attention-needed (non-fatal).
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    // Running — in-progress transfer / active sync (uses the Virga teal accent).
    val running: Color,
    val onRunning: Color,
    val runningContainer: Color,
    val onRunningContainer: Color,
    // Info — neutral informational notices (uses the Virga blue accent).
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
)

/**
 * Light-scheme semantic palette.
 *
 * Bases: success = [VirgaSuccess] (#2E7D32), warning = #B26A00 (amber 800-ish),
 * running = [VirgaTeal] (#1FA8A0), info = [VirgaBlue] (#1E6FD9). Containers are
 * pale tints of each hue; on-container colors are deep, low-lightness shades of
 * the same hue so dark text reads AA on the tint.
 */
val VirgaLightSemanticColors = VirgaSemanticColors(
    // Success — dark green base carries white content at AA.
    success = VirgaSuccess,
    onSuccess = Color.White,
    successContainer = Color(0xFFB7F0BC),
    onSuccessContainer = Color(0xFF002106),
    // Warning — vivid amber needs dark content for AA; container is pale amber.
    warning = Color(0xFFB26A00),
    onWarning = Color.White,
    warningContainer = Color(0xFFFFDDB3),
    onWarningContainer = Color(0xFF2A1700),
    // Running — teal base with white content; pale teal container.
    running = VirgaTeal,
    onRunning = Color.White,
    runningContainer = Color(0xFFB6ECE7),
    onRunningContainer = Color(0xFF00201E),
    // Info — Virga blue base with white content; pale blue container.
    info = VirgaBlue,
    onInfo = Color.White,
    infoContainer = Color(0xFFD6E4FF),
    onInfoContainer = Color(0xFF001A40),
)

/**
 * Dark-scheme semantic palette.
 *
 * Bases: success = [VirgaSuccessDark] (#81C784), warning = #FFB868,
 * running = [VirgaTealDark] (#5FD4CC), info = [VirgaBlueDarkPrimary] (#9FBEF7).
 * On dark backgrounds bases are light, so on-<role> colors are dark; containers
 * are deep desaturated shades with light on-container text for AA.
 */
val VirgaDarkSemanticColors = VirgaSemanticColors(
    // Success — light green base; dark content on it, deep-green container.
    success = VirgaSuccessDark,
    onSuccess = Color(0xFF00390C),
    successContainer = Color(0xFF1B5E20),
    onSuccessContainer = Color(0xFFB7F0BC),
    // Warning — warm amber base; dark content, deep-brown container.
    warning = Color(0xFFFFB868),
    onWarning = Color(0xFF4A2800),
    warningContainer = Color(0xFF6A3D00),
    onWarningContainer = Color(0xFFFFDDB3),
    // Running — light teal base; dark content, deep-teal container.
    running = VirgaTealDark,
    onRunning = Color(0xFF003733),
    runningContainer = Color(0xFF00504B),
    onRunningContainer = Color(0xFFB6ECE7),
    // Info — light blue base reusing the brand dark-primary tone.
    info = VirgaBlueDarkPrimary,
    onInfo = VirgaBlueDarkOnPrimary,
    infoContainer = VirgaBlueDarkContainer,
    onInfoContainer = VirgaBlueDarkOnContainer,
)

/**
 * CompositionLocal carrying the active [VirgaSemanticColors]. Static because the
 * value changes only on light/dark (theme) switches, not per-recomposition.
 * Reads will throw if the tree is not wrapped in the Virga theme — this is
 * intentional: a missing provider is a wiring bug, not a silent fallback.
 */
val LocalVirgaColors = staticCompositionLocalOf<VirgaSemanticColors> {
    error("VirgaSemanticColors not provided — wrap content in VirgaTheme")
}
