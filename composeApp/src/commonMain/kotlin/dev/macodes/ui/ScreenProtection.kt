package dev.macodes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Reference-counted tracker for screens or sections that display sensitive credentials
 * (private keys, passphrases, passwords, certificates, API keys).
 */
@Stable
class ScreenProtectionController {
    var sensitiveCount by mutableIntStateOf(0)
        private set

    fun acquire() {
        sensitiveCount++
    }

    fun release() {
        if (sensitiveCount > 0) sensitiveCount--
    }
}

val LocalScreenProtectionController = compositionLocalOf<ScreenProtectionController?> { null }

/**
 * Marks the current composition scope as sensitive so screen capture / recording is blocked
 * when [dev.macodes.data.ScreenProtectionMode.PARTIAL] or [dev.macodes.data.ScreenProtectionMode.FULL] is active.
 */
@Composable
fun SensitiveScreenProtection(enabled: Boolean = true) {
    val controller = LocalScreenProtectionController.current ?: return
    DisposableEffect(controller, enabled) {
        if (enabled) {
            controller.acquire()
            onDispose { controller.release() }
        } else {
            onDispose {}
        }
    }
}

/** Platform window protection hook (Android `WindowManager.LayoutParams.FLAG_SECURE`). */
@Composable
expect fun ScreenProtectionEffect(active: Boolean)
