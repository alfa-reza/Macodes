package dev.macodes.util

import android.view.HapticFeedbackConstants
import dev.macodes.AppContext

/** Android: Light tap feedback (KEYBOARD_TAP, system-level, no VIBRATE permission required). */
actual fun hapticTick() {
    runCatching {
        val activity = AppContext.currentActivity ?: return
        activity.window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
}
