package dev.termish.util

import android.util.Log
import dev.termish.AppContext
import dev.termish.SessionService

actual object SessionKeepAlive {
    actual fun onSessionStart(sessionId: String) {
        try {
            SessionService.start(AppContext.get(), sessionId)
        } catch (e: Exception) {
            // If there is a failure to launch the foreground service (e.g., due to background restrictions), do not terminate the connection, but be sure to log the issue for troubleshooting.
            Log.e("Termish-SessionKeepAlive", "onSessionStart failed", e)
        }
    }

    actual fun onSessionEnd(sessionId: String) {
        try {
            SessionService.stop(AppContext.get(), sessionId)
        } catch (e: Exception) {
            Log.e("Termish-SessionKeepAlive", "onSessionEnd failed", e)
        }
    }

    actual fun isActive(): Boolean = SessionService.isRunning

    actual fun foregroundSshRecovery(): ForegroundSshRecovery = if (SessionService.isRunning) ForegroundSshRecovery.KEEP else ForegroundSshRecovery.VERIFY
}
