package dev.termish.ssh

/** iOS engine factory (libssh2 + OpenSSL statically linked). */
actual fun createSshSession(
    connection: SshConnection,
    callbacks: SshCallbacks,
): SshSession = SshSessionLibssh2(connection, callbacks)
