package dev.termish.util

/**
 * Observing the platform foreground/background lifecycle.
 *
 * Currently, only iOS is fully integrated: after returning to the home screen, the system suspends the process and closes the SSH socket,
 * Automatically reconnect to active sessions (while retaining the cache) when returning to the foreground. On Android, this is handled by a foreground service + wakelock.
 * Keep alive; the corresponding implementation is a null operation.
 *
 * Returns an unregistration function (the implementation of an empty operation returns an empty lambda).
 */
expect fun observeAppLifecycle(listener: (foreground: Boolean) -> Unit): () -> Unit
