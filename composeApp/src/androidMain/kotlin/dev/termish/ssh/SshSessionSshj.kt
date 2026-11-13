package dev.termish.ssh
import dev.termish.util.TermLog
import java.io.StringReader
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.AbstractChannel
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import net.schmizz.sshj.transport.DisconnectListener
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthMethod
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.method.ChallengeResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource

/**
 * sshj engine (Android). The transport layer, KEX, authentication, and encryption are all handled by
 * Tried-and-true sshj + BouncyCastle; this class is responsible only for the session lifecycle and callbacks.
 */
class SshSessionSshj(
    private val connection: SshConnection,
    private val callbacks: SshCallbacks,
    /** Socket read timeout (milliseconds; 0 = no timeout). Pass 0 from the terminal shell (interactive streams cannot have a timeout);
     *  SFTP and other request-response channels have a time limit (e.g., 30 seconds): a timeout on a single request must be due to a connection exception,
     *  Otherwise write/list can hang indefinitely, leaving the UI stuck on “Transferring…” after a disconnect. */
    private val readTimeoutMs: Long = 0L,
) : SshSession {
    private val client = SSHClient()
    private var session: Session? = null
    private var shell: Session.Shell? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Single-threaded write dispatcher: This avoids writing to sockets on the main thread (which would throw a `NetworkOnMainThreadException` on Android) while ensuring the order of input.
    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)

    // Single-threaded read dispatcher: If the two readers for stdout and stderr callbacks are executed in concurrent fashion,
    // It will simultaneously enter the lock-free TerminalEmulator state machine (escape sequences get interrupted and UTF-8 characters split across reads are garbled),
    // Must be fed to the upper layer in a serialized manner. onOutput/onStderr are callbacks that are suspended (can be used for backpressure) in the reader
    // Called within a coroutine; releases the current dispatcher thread when suspended
    private val readerDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val closed =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    private var hostKeyInfo: HostKeyInfo? = null
    private var serverVersion: String = ""
    private var privateKeyError: String? = null

    override fun connectAndStart(
        columns: Int,
        rows: Int,
    ): SessionInfo {
        connectTransport()
        val s = client.startSession()
        session = s
        s.allocatePTY(connection.terminalType, columns, rows, 0, 0, emptyMap())
        val sh = s.startShell()
        shell = sh
        callbacks.onTraceStep("shell")

        startReaders(sh)
        return SessionInfo(
            serverVersion = serverVersion,
            hostKey = hostKeyInfo,
            kexAlgorithm = hostKeyInfo?.algorithm ?: "",
        )
    }

    override fun connectAuthOnly(): SessionInfo? {
        try {
            connectTransport()
            return SessionInfo(
                serverVersion = serverVersion,
                hostKey = hostKeyInfo,
                kexAlgorithm = hostKeyInfo?.algorithm ?: "",
            )
        } catch (e: Exception) {
            TermLog.w("ssh") { "connectAuthOnly failed: ${e.message}" }
            return null
        }
    }

    private fun connectTransport() {
        client.addHostKeyVerifier(
            object : HostKeyVerifier {
                override fun verify(
                    hostname: String,
                    port: Int,
                    key: PublicKey,
                ): Boolean {
                    val algorithm = keyTypeName(key)
                    val info =
                        HostKeyInfo(
                            algorithm = algorithm,
                            fingerprintSha256 = fingerprintSha256(key),
                            fingerprintMd5 = fingerprintMd5(key),
                        )
                    hostKeyInfo = info
                    return callbacks.verifyHostKey(info)
                }

                override fun findExistingAlgorithms(
                    hostname: String,
                    port: Int,
                ): List<String> = emptyList()
            },
        )

        client.setConnectTimeout(connection.connectTimeoutMillis.toInt())
        client.setTimeout(readTimeoutMs.toInt()) // Interactive shell=0: no time limit; SFTP request-response: time-limited

        client.connect(connection.host, connection.port)
        // sshj’s `connect()` is blocked until KEX completes: TCP and KEX are merged into a single phase
        callbacks.onTraceStep("tcp+kex")
        serverVersion = client.transport.serverVersion
        authenticate()
        callbacks.onTraceStep("auth")

        // Idle Keep-Alive (<=0 to disable): Place all entries here; this setting applies to all connection paths.
        // (Previously, the settings were only configured in the terminal shell path; SFTP connections had no keep-alive, and disconnections went completely unnoticed.)
        try {
            if (connection.keepAliveSeconds > 0) {
                client.connection.keepAlive.setKeepAliveInterval(connection.keepAliveSeconds)
            }
        } catch (_: Exception) {
        }

        // Transport layer disconnect (remote end closed/FIN/RST/keepalive failure) → forwarding onClosed:
        // Terminal paths are handled by the `startReaders` channel’s `join` fallback; sessions without a shell, such as SFTP,
        // No listeners had been registered previously, so the UI was unaware of the disconnection (which could only be detected upon failure of the next operation).
        try {
            client.transport.setDisconnectListener(
                object : DisconnectListener {
                    override fun notifyDisconnect(
                        reason: DisconnectReason,
                        message: String,
                    ) {
                        TermLog.w("ssh") { "transport disconnect $reason: $message" }
                        handleClosed()
                    }
                },
            )
        } catch (_: Exception) {
        }
    }

    override fun connectAndRun(
        command: String,
        timeoutMs: Long,
    ): CommandResult {
        connectTransport()
        return try {
            val s = client.startSession()
            val cmd = s.exec(command)
            val out = java.io.ByteArrayOutputStream()
            cmd.inputStream.copyTo(out)
            cmd.join(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            CommandResult(out.toString(Charsets.UTF_8.name()), hostKeyInfo)
        } finally {
            close()
        }
    }

    override fun runCommandDetailed(
        command: String,
        timeoutMs: Long,
    ): CommandOutput? {
        if (closed.get() || !client.isConnected) return null
        var s: Session? = null
        return try {
            // Reuse an authenticated connection: Open a new exec channel without re-authenticating or interrupting the interactive shell
            s = client.startSession()
            val cmd = s.exec(command)
            // stdout/stderr must achieve concurrent reading: sshj streams are blocking, and if a single stream fills up (due to a large volume of output on the remote stderr
            // (Output) will cause the other stream to enter blocking until a timeout occurs—reading from stdout will also discard diagnostic info
            val out = java.io.ByteArrayOutputStream()
            val err = java.io.ByteArrayOutputStream()
            val outT = Thread { cmd.inputStream.copyTo(out) }.apply { start() }
            val errT = Thread { cmd.errorStream.copyTo(err) }.apply { start() }
            outT.join(10_000)
            errT.join(10_000)
            cmd.join(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            CommandOutput(
                stdout = out.toString(Charsets.UTF_8.name()),
                stderr = err.toString(Charsets.UTF_8.name()),
                exitCode = cmd.exitStatus,
            )
        } catch (_: Exception) {
            null
        } finally {
            try {
                s?.close()
            } catch (_: Exception) {
            }
        }
    }

    override fun startExec(
        command: String,
        columns: Int,
        rows: Int,
    ): SshExecChannel? {
        if (closed.get() || !client.isConnected) return null
        val s = client.startSession()
        return try {
            // `exec` with PTY: An interactive CLI (herdr TUI) requires a TTY; `crossterm` will panic if no TTY is available.
            s.allocatePTY(connection.terminalType, columns, rows, 0, 0, emptyMap())
            val cmd = s.exec(command)
            val mutex = Any()
            object : SshExecChannel {
                override fun read(): ByteArray? =
                    try {
                        val buf = ByteArray(8192)
                        val n = cmd.inputStream.read(buf)
                        if (n < 0) null else buf.copyOf(n)
                    } catch (e: Exception) {
                        null
                    }

                override fun readErr(): ByteArray? =
                    try {
                        val buf = ByteArray(8192)
                        val n = cmd.errorStream.read(buf)
                        if (n < 0) null else buf.copyOf(n)
                    } catch (e: Exception) {
                        null
                    }

                override fun write(data: ByteArray) {
                    // Async version: The caller may be the main thread (emulator.onResponse provides a response to an OSC query—
                    // (herdr launches 256 consecutive OSC 4 queries; sync socket writes will cause the main thread to hang)
                    scope.launch(writeDispatcher) {
                        synchronized(mutex) {
                            try {
                                cmd.outputStream.write(data)
                                cmd.outputStream.flush()
                            } catch (_: Exception) {
                            }
                        }
                    }
                }

                override fun close() {
                    try {
                        s.close()
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            TermLog.w("ssh") { "startExec failed: ${e.message}" }
            try {
                s.close()
            } catch (_: Exception) {
            }
            null
        }
    }

    override fun startExecRaw(command: String): SshExecChannel? {
        if (closed.get() || !client.isConnected) return null
        val s = client.startSession()
        return try {
            // No PTY: Binary output (screen frame stream) cannot be rewritten by pty (e.g., \n conversion).
            val cmd = s.exec(command)
            val mutex = Any()
            object : SshExecChannel {
                override fun read(): ByteArray? =
                    try {
                        val buf = ByteArray(64 * 1024)
                        val n = cmd.inputStream.read(buf)
                        if (n < 0) null else buf.copyOf(n)
                    } catch (e: Exception) {
                        null
                    }

                override fun readErr(): ByteArray? =
                    try {
                        val buf = ByteArray(8192)
                        val n = cmd.errorStream.read(buf)
                        if (n < 0) null else buf.copyOf(n)
                    } catch (e: Exception) {
                        null
                    }

                override fun write(data: ByteArray) {
                    scope.launch(writeDispatcher) {
                        synchronized(mutex) {
                            try {
                                cmd.outputStream.write(data)
                                cmd.outputStream.flush()
                            } catch (_: Exception) {
                            }
                        }
                    }
                }

                override fun close() {
                    try {
                        cmd.close()
                    } catch (_: Exception) {
                    }
                    try {
                        s.close()
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            TermLog.w("ssh") { "startExecRaw failed: ${e.message}" }
            try {
                s.close()
            } catch (_: Exception) {
            }
            null
        }
    }

    override fun openDirectTcpip(
        host: String,
        port: Int,
    ): SshExecChannel? {
        if (closed.get() || !client.isConnected || !client.isAuthenticated) return null
        return try {
            val direct = client.newDirectConnection(host, port)
            val mutex = Any()
            object : SshExecChannel {
                override fun read(): ByteArray? =
                    try {
                        val buf = ByteArray(64 * 1024)
                        val n = direct.inputStream.read(buf)
                        if (n < 0) null else buf.copyOf(n)
                    } catch (_: Exception) {
                        null
                    }

                override fun write(data: ByteArray) {
                    scope.launch(writeDispatcher) {
                        synchronized(mutex) {
                            try {
                                direct.outputStream.write(data)
                                direct.outputStream.flush()
                            } catch (_: Exception) {
                            }
                        }
                    }
                }

                override fun close() {
                    try {
                        direct.close()
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            TermLog.w("ssh") { "openDirectTcpip $host:$port failed: ${e.message}" }
            null
        }
    }

    /** Open an SFTP channel over an authenticated connection (the SFTP session independently holds this object; the caller is responsible for closing it). */
    fun openSftp(): net.schmizz.sshj.sftp.SFTPClient {
        connectTransport()
        return client.newSFTPClient()
    }

    // ---------- Authentication ----------

    private fun authenticate() {
        val methods = ArrayList<AuthMethod>()

        connection.privateKeyPem?.let { pem ->
            loadKeyProvider(pem)?.let { methods.add(AuthPublickey(it)) }
        }
        connection.password?.let { pw ->
            val finder =
                object : PasswordFinder {
                    override fun reqPassword(resource: Resource<*>?): CharArray = pw.toCharArray()

                    override fun shouldRetry(resource: Resource<*>?): Boolean = false
                }
            methods.add(AuthPassword(finder))
        }
        // keyboard-interactive: as a fallback for passwords/two-factor authentication
        methods.add(AuthKeyboardInteractive(kbiProvider))

        try {
            client.auth(connection.username, methods)
        } catch (e: Exception) {
            // If there is a failure during private key loading (unsupported format/incorrect password/input canceled), include the reason in the error message,
            // Avoid situations where users see only “Failure in authentication” without realizing that the private key was never used in the authentication process.
            val hint = privateKeyError?.let { "（私钥加载失败：$it）" } ?: ""
            throw SshException("SSH 认证失败$hint：${e.message}", e)
        }
    }

    private fun loadKeyProvider(pem: String): FileKeyProvider? {
        val isOpenSsh = pem.contains("OPENSSH PRIVATE KEY")

        fun newProvider(): FileKeyProvider =
            if (isOpenSsh) {
                com.hierynomus.sshj.userauth.keyprovider
                    .OpenSSHKeyV1KeyFile()
            } else {
                PKCS8KeyFile()
            }

        // Encryption of private keys: sshj’s `init` does not throw an exception for encrypted keys (decryption is deferred until authentication),
        // Must first perform a static check and display a prompt asking the user to enter the passphrase
        if (isEncryptedPem(pem)) {
            val passphrase =
                runBlocking {
                    callbacks.onPrompt(
                        AuthPrompt(
                            method = SshAuthMethod.PASSPHRASE,
                            name = "私钥口令",
                            instruction = "该私钥已加密（passphrase-protected），请输入口令。",
                            prompts = listOf(PromptField("Passphrase", echo = false)),
                        ),
                    )
                }?.firstOrNull()
            if (passphrase.isNullOrEmpty()) {
                privateKeyError = "加密私钥需要口令，已取消输入"
                return null
            }
            return try {
                val kp = newProvider()
                kp.init(
                    StringReader(pem),
                    null,
                    object : PasswordFinder {
                        override fun reqPassword(resource: Resource<*>?): CharArray = passphrase.toCharArray()

                        override fun shouldRetry(resource: Resource<*>?): Boolean = false
                    },
                )
                privateKeyError = null
                kp
            } catch (e: Exception) {
                privateKeyError = "口令错误或密钥无法解析：${e.message ?: e.javaClass.simpleName}"
                null
            }
        }

        // Private key in plain text
        return try {
            val kp = newProvider()
            kp.init(StringReader(pem), null, null)
            privateKeyError = null
            kp
        } catch (e: Exception) {
            privateKeyError = e.message ?: e.javaClass.simpleName
            null
        }
    }

    private val kbiProvider =
        object : ChallengeResponseProvider {
            @Volatile private var cancelled = false

            @Volatile private var name = ""

            @Volatile private var instruction = ""

            override fun getSubmethods(): List<String> = emptyList()

            override fun init(
                resource: Resource<*>,
                name: String,
                instruction: String,
            ) {
                this.name = name
                this.instruction = instruction
            }

            override fun getResponse(
                prompt: String,
                echo: Boolean,
            ): CharArray {
                val answers =
                    runBlocking {
                        callbacks.onPrompt(
                            AuthPrompt(
                                method = SshAuthMethod.KEYBOARD_INTERACTIVE,
                                name = name,
                                instruction = instruction,
                                prompts = listOf(PromptField(prompt, echo)),
                            ),
                        )
                    }
                if (answers.isNullOrEmpty()) {
                    cancelled = true
                    return CharArray(0)
                }
                return answers.first().toCharArray()
            }

            override fun shouldRetry(): Boolean = !cancelled
        }

    // ---------- Data Transmission and Reception ----------

    private fun startReaders(sh: Session.Shell) {
        scope.launch(readerDispatcher) {
            try {
                val buf = ByteArray(64 * 1024)
                val input = sh.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) callbacks.onOutput(buf.copyOf(n))
                }
            } catch (_: Exception) {
            }
        }
        scope.launch(readerDispatcher) {
            try {
                val err = sh.errorStream ?: return@launch
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = err.read(buf)
                    if (n < 0) break
                    if (n > 0) callbacks.onStderr(buf.copyOf(n))
                }
            } catch (_: Exception) {
            }
        }
        scope.launch(readerDispatcher) {
            // Wait for the channel to close and report the exit status
            try {
                (sh as? AbstractChannel)?.join()
            } catch (_: Exception) {
                // A channel may end with a disconnect
                while ((sh as? AbstractChannel)?.isOpen == true) delay(50)
            }
            handleClosed()
        }
    }

    private fun handleClosed() {
        if (!closed.compareAndSet(false, true)) return
        try {
            val sc = shell as? SessionChannel
            if (sc != null) {
                // The exit state may be reached shortly after the channel is closed; poll briefly.
                var status: Int? = null
                val deadline = System.currentTimeMillis() + 2000
                while (status == null && System.currentTimeMillis() < deadline) {
                    status = sc.getExitStatus()
                    if (status == null) Thread.sleep(20)
                }
                status?.let { callbacks.onExitStatus(it) }
            }
        } catch (_: Exception) {
        }
        callbacks.onClosed(null)
    }

    override fun resize(
        columns: Int,
        rows: Int,
        widthPx: Int,
        heightPx: Int,
    ) {
        scope.launch(writeDispatcher) {
            try {
                shell?.changeWindowDimensions(columns, rows, widthPx, heightPx)
            } catch (_: Exception) {
            }
        }
    }

    override fun sendData(data: ByteArray) {
        scope.launch(writeDispatcher) {
            try {
                shell?.outputStream?.write(data)
                shell?.outputStream?.flush()
            } catch (_: Exception) {
            }
        }
    }

    override fun close() {
        scope.launch(writeDispatcher) {
            try {
                shell?.close()
            } catch (_: Exception) {
            }
            try {
                session?.close()
            } catch (_: Exception) {
            }
            try {
                client.disconnect()
            } catch (_: Exception) {
            }
        }
        if (!closed.get()) {
            closed.set(true)
            callbacks.onClosed(null)
        }
    }

    override fun checkAlive(timeoutMillis: Long): Boolean {
        if (!isActive()) return false
        return try {
            // SSH_MSG_IGNORE only proves that the local write did not experience any failure immediately; global requests with `want-reply`
            // The system must wait for the server to return REQUEST_SUCCESS or REQUEST_FAILURE before it can identify a TCP connection that remains half-open after entering sleep mode.
            // When OpenSSH does not recognize this request, it returns FAILURE, which also serves as a valid way to confirm liveness.
            client.connection
                .sendGlobalRequest("keepalive@openssh.com", true, byteArrayOf())
                .retrieve(timeoutMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            isActive()
        } catch (e: ConnectionException) {
            // OpenSSH returns REQUEST_FAILURE for unknown global requests; sshj wraps it as an exception,
            // However, receiving this response itself proves that the bidirectional SSH connection is alive. Timeouts or transmission exceptions
            // If the message text differs, treat it as a failure.
            val serverRejectedRequest =
                e.message?.contains("Global request [global req for keepalive@openssh.com] failed") == true
            if (serverRejectedRequest && isActive()) return true
            TermLog.w("ssh") { "health check failed: ${e.message}" }
            false
        } catch (e: Exception) {
            TermLog.w("ssh") { "health check failed: ${e.message}" }
            false
        }
    }

    override fun isActive(): Boolean =
        !closed.get() &&
            client.isConnected &&
            client.isAuthenticated &&
            client.transport.isRunning &&
            shell?.isOpen == true

    // ---------- fingerprint ----------

    private fun keyTypeName(key: PublicKey): String =
        try {
            KeyType.fromKey(key).toString()
        } catch (_: Exception) {
            key.algorithm
        }

    private fun fingerprintSha256(key: PublicKey): String {
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private fun fingerprintMd5(key: PublicKey): String {
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance("MD5").digest(blob)
        return digest.joinToString(":") { "%02x".format(it) }
    }
}
