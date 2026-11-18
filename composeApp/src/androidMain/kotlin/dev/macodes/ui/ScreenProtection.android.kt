package dev.macodes.ui

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import dev.macodes.AppContext

@Composable
actual fun ScreenProtectionEffect(active: Boolean) {
    val window = AppContext.currentActivity?.window
    DisposableEffect(window, active) {
        if (window != null) {
            if (active) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        onDispose {
            if (active && window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}
