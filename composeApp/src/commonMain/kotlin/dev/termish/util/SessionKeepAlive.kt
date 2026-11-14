package dev.termish.util

/**
 * Session keep-alive hook: Called when an SSH connection is established or disconnected.
 * Android implementation uses a foreground service + PARTIAL_WAKE_LOCK to prevent the connection from being
 * terminated when the app is moved to the background and the process is recycled by the system.
 */
enum class ForegroundSshRecovery {
    /** The keep-alive mechanism operates normally; no additional handling is required. */
    KEEP,

    /** The keep-alive mechanism has been interrupted, but the socket may still be healthy; check first, and only reconnect if there is a failure. */
    VERIFY,

    /** Suspending the platform will inevitably cause the socket to become invalid; rebuild the connection immediately. */
    REBUILD,
}

expect object SessionKeepAlive {
    fun onSessionStart(sessionId: String)

    fun onSessionEnd(sessionId: String)

    /**
     * Is the background service actually running? After an Android foreground service is stopped by the system or a manufacturer’s power-saving policy,
     * returns false so the calling layer can re-register active sessions.
     */
    fun isActive(): Boolean

    /**
     * How to handle an SSH socket that still shows as having a connection when returning to the foreground.
     * On Android, perform an end-to-end check only when the keep-alive service stops.
     */
    fun foregroundSshRecovery(): ForegroundSshRecovery
}
