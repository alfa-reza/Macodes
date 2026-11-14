package dev.termish.util

/**
 * Observing the platform foreground/background lifecycle.
 *
 * Currently, on Android the foreground service + wakelock keeps sessions alive in the background; this hook is a no-op.
 * On other platforms, suspension closes the SSH socket, so returning to the foreground rebuilds active sessions.
 *
 * Returns an unregistration function (the implementation of an empty operation returns an empty lambda).
 */
expect fun observeAppLifecycle(listener: (foreground: Boolean) -> Unit): () -> Unit
