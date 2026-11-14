package dev.macodes.ssh

/** JVM (Android) engine factory. */
actual fun createSshSession(
    connection: SshConnection,
    callbacks: SshCallbacks,
): SshSession = SshSessionSshj(connection, callbacks)
