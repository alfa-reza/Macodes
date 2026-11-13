package dev.termish.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import dev.termish.AppContext

/**
 * Android: The lifecycle hook implementation using Activity foreground/background counting, triggered when the app returns to the foreground
 * sessionManager.reconnectDroppedSessions() (consistent with iOS behavior).
 * If the connection is lost due to a failure in the keep-alive process or a network interruption, simply return to the foreground to automatically reconnect and resume the session.
 */
actual fun observeAppLifecycle(listener: (foreground: Boolean) -> Unit): () -> Unit {
    val app =
        try {
            AppContext.get() as? Application
        } catch (_: Throwable) {
            null
        } ?: return {}

    // When registering, MainActivity is already in the STARTED state (the AppRoot composition occurs after the Activity has been launched),
    // However, `registerActivityLifecycleCallbacks` does not resend calls for existing Activities.
    // onActivityStarted—starting at 0 will cause the first onStopped to be decremented to -1,
    // listener(false) Never trigger (persistence when moving to the background and reconnection when returning to the foreground will both fail).
    // The initial count is compensated to 1.
    var started = 1
    val callback =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (started == 0) listener(true)
                started++
            }

            override fun onActivityStopped(activity: Activity) {
                started--
                if (started == 0) listener(false)
            }

            override fun onActivityCreated(
                activity: Activity,
                savedInstanceState: Bundle?,
            ) {}

            override fun onActivityResumed(activity: Activity) {}

            override fun onActivityPaused(activity: Activity) {}

            override fun onActivitySaveInstanceState(
                activity: Activity,
                outState: Bundle,
            ) {}

            override fun onActivityDestroyed(activity: Activity) {}
        }
    app.registerActivityLifecycleCallbacks(callback)
    return { app.unregisterActivityLifecycleCallbacks(callback) }
}
