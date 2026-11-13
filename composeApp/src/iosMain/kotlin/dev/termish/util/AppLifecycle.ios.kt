package dev.termish.util

import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification

/**
 * iOS: Listen for system foreground and background notifications. Record active sessions when returning to the home screen (suspend),
 * trigger auto-reconnect on return to the foreground — the socket was killed by the system, but the server-side herdr/tmux
 * The session is still active; the session will resume exactly as it was after reconnecting.
 */
actual fun observeAppLifecycle(listener: (foreground: Boolean) -> Unit): () -> Unit {
    val center = NSNotificationCenter.defaultCenter
    val backgroundObserver =
        center.addObserverForName(
            UIApplicationDidEnterBackgroundNotification,
            null,
            null,
        ) { _ -> listener(false) }
    val foregroundObserver =
        center.addObserverForName(
            UIApplicationWillEnterForegroundNotification,
            null,
            null,
        ) { _ -> listener(true) }
    return {
        center.removeObserver(backgroundObserver)
        center.removeObserver(foregroundObserver)
    }
}
