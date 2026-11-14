package dev.macodes.ui

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import dev.macodes.AppContext

@Composable
@Suppress("DEPRECATION") // `adjustResize` remains the compatibility path here for resuming the input area height across OEMs.
actual fun AgentImeResizeEffect() {
    val window = AppContext.currentActivity?.window
    DisposableEffect(window) {
        val previousMode = window?.attributes?.softInputMode
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose {
            previousMode?.let(window::setSoftInputMode)
        }
    }
}
