package dev.termish.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Semantic color coding for connection status: Ensure consistent color selection across screens for status indicators, banners, and status text (avoid scattered hard-coded values).
 *
 * Cover only the "connection status" semantics; notification channel colors and OS logo brand colors are excluded.
 */
object StatusColors {
    /** Connection / Success. */
    val Connected = Color(0xFF34C759)

    /** In Progress / Warning: Connection, Authentication, Disconnected, Fallback Tooltip. */
    val Warning = Color(0xFFFFA726)

    /** Error / Failure: Disconnect, authentication failure, installation failure. */
    val Error = Color(0xFFEF5350)

    /** Neutral / Unknown: CLOSED / IDLE. */
    val Neutral = Color(0xFF9E9E9E)
}
