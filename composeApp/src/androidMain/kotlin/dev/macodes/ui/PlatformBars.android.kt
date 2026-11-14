package dev.macodes.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

@Composable
actual fun PlatformStatusBarIcons(lightIcons: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // Light background → dark icons (isAppearanceLightStatusBars=true); dark background → light icons
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !lightIcons
        }
    }
}

/** Fullscreen immersive video: The system status bar is hidden or resumed (video fills the entire screen). */
@Composable
actual fun PlatformImmersiveMode(immersive: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(view, immersive) {
            val controller =
                (view.context as? Activity)?.window?.let { window ->
                    WindowCompat.getInsetsController(window, view)
                }
            if (controller != null) {
                if (immersive) {
                    controller.hide(WindowInsetsCompat.Type.statusBars())
                } else {
                    controller.show(WindowInsetsCompat.Type.statusBars())
                }
            }
            onDispose {
                // The screen may be exited directly before the fullscreen collapse animation finishes; it must be resumed when the composition is destroyed,
                // Otherwise, subsequent host pages will have a 0-pixel status bar inset, and the header will be permanently truncated.
                if (immersive) {
                    controller?.show(WindowInsetsCompat.Type.statusBars())
                }
            }
        }
    }
}
