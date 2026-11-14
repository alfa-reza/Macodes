package dev.macodes.util

import android.util.Log
import dev.macodes.AppContext
import dev.macodes.SessionService

actual object SessionKeepAlive {
    actual fun onSessionStart(sessionId: String) {
        try {
            SessionService.start(AppContext.get(), sessionId)
        } catch (e: Exception) {
            // If there is a failure to launch the foreground service (e.g., due to background restrictions), do not terminate the connection, but be sure to log the issue for troubleshooting.
            Log.e("Macodes-SessionKeepAlive", "onSessionStart failed", e)
        }
    }

    actual fun onSessionEnd(sessionId: String) {
        try {
            SessionService.stop(AppContext.get(), sessionId)
        } catch (e: Exception) {
            Log.e("Macodes-SessionKeepAlive", "onSessionEnd failed", e)
        }
    }

    actual fun isActive(): Boolean = SessionService.isRunning

    actual fun foregroundSshRecovery(): ForegroundSshRecovery = if (SessionService.isRunning) ForegroundSshRecovery.KEEP else ForegroundSshRecovery.VERIFY
}
