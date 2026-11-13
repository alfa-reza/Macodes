package dev.termish.util

import android.view.HapticFeedbackConstants
import dev.termish.AppContext

/** Android: Light tap feedback (KEYBOARD_TAP, system-level, no VIBRATE permission required). */
actual fun hapticTick() {
    runCatching {
        val activity = AppContext.currentActivity ?: return
        activity.window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
}
