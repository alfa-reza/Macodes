package dev.termish.ssh

import dev.termish.crypto.Sha256
import dev.termish.util.base64Encode
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import libssh2.LIBSSH2_CHANNEL
import libssh2.LIBSSH2_ERROR_EAGAIN
import libssh2.LIBSSH2_HOSTKEY_TYPE_DSS
import libssh2.LIBSSH2_HOSTKEY_TYPE_ECDSA_256
import libssh2.LIBSSH2_HOSTKEY_TYPE_ECDSA_384
import libssh2.LIBSSH2_HOSTKEY_TYPE_ECDSA_521
import libssh2.LIBSSH2_HOSTKEY_TYPE_ED25519
import libssh2.LIBSSH2_HOSTKEY_TYPE_RSA
import libssh2.LIBSSH2_SESSION
import libssh2.LIBSSH2_SFTP
import libssh2._LIBSSH2_USERAUTH_KBDINT_PROMPT
import libssh2._LIBSSH2_USERAUTH_KBDINT_RESPONSE
import libssh2.libssh2_channel_close
import libssh2.libssh2_channel_direct_tcpip_ex
import libssh2.libssh2_channel_free
import libssh2.libssh2_channel_get_exit_status
import libssh2.libssh2_channel_open_ex
import libssh2.libssh2_channel_process_startup
import libssh2.libssh2_channel_read_ex
import libssh2.libssh2_channel_request_pty_ex
import libssh2.libssh2_channel_request_pty_size_ex
import libssh2.libssh2_channel_write_ex
import libssh2.libssh2_init
import libssh2.libssh2_keepalive_config
import libssh2.libssh2_keepalive_send
import libssh2.libssh2_session_banner_get
import libssh2.libssh2_session_disconnect_ex
import libssh2.libssh2_session_free
import libssh2.libssh2_session_handshake
import libssh2.libssh2_session_hostkey
import libssh2.libssh2_session_init_ex
import libssh2.libssh2_session_last_error
import libssh2.libssh2_session_set_blocking
import libssh2.libssh2_session_set_timeout
import libssh2.libssh2_sftp_init
import libssh2.libssh2_userauth_keyboard_interactive_ex
import libssh2.libssh2_userauth_password_ex
import libssh2.libssh2_userauth_publickey_frommemory
import platform.posix.EINPROGRESS
import platform.posix.EISCONN
import platform.posix.ETIMEDOUT
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.IPPROTO_TCP
import platform.posix.O_NONBLOCK
import platform.posix.POLLERR
import platform.posix.POLLHUP
import platform.posix.POLLOUT
import platform.posix.SOCK_STREAM
import platform.posix.addrinfo
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.freeaddrinfo
import platform.posix.gai_strerror
import platform.posix.getaddrinfo
import platform.posix.malloc
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.socket
import platform.posix.usleep
import sftp_write.termish_channel_write

/** Global handler for keyboard-interactive callbacks (staticCFunction cannot capture variables). */
@Volatile
private var kbiHandler: (suspend (AuthPrompt) -> List<String>?)? = null

/** kbiHandler is a process-level singleton: it serializes multi-session concurrency in KBI authentication to prevent prompts from being routed to an error session. */
private val kbiMutex = Mutex()

@OptIn(ExperimentalForeignApi::class)
private val kbiCallback =
    staticCFunction {
        name: CPointer<ByteVar>?,
        nameLen: Int,
        instruction: CPointer<ByteVar>?,
        instructionLen: Int,
        numPrompts: Int,
        prompts: CPointer<_LIBSSH2_USERAUTH_KBDINT_PROMPT>?,
        responses: CPointer<_LIBSSH2_USERAUTH_KBDINT_RESPONSE>?,
        _abstract: CPointer<CPointerVar<out kotlinx.cinterop.CPointed>>?,
        ->
        val nameStr = name?.readBytes(nameLen)?.toKString() ?: ""
        val instrStr = instruction?.readBytes(instructionLen)?.toKString() ?: ""
        val fields = ArrayList<PromptField>()
        for (i in 0 until numPrompts) {
            val p = prompts?.get(i) ?: continue
            val textPtr = p.text?.reinterpret<ByteVar>()
            val text = if (textPtr != null) textPtr.readBytes(p.length.toInt()).toKString() else ""
            fields.add(PromptField(text, p.echo.toInt() != 0))
        }
        val answers =
            kbiHandler?.let { h ->
                runBlocking { h(AuthPrompt(SshAuthMethod.KEYBOARD_INTERACTIVE, nameStr, instrStr, fields)) }
            }
        for (i in 0 until numPrompts) {
            val resp = responses?.get(i) ?: continue
            val ans = answers?.getOrNull(i) ?: ""
            val bytes = ans.encodeToByteArray()
            val buf = malloc((bytes.size + 1).toULong())?.reinterpret<ByteVar>()
            if (buf != null) {
                for (j in bytes.indices) buf[j] = bytes[j]
                buf[bytes.size] = 0
                resp.text = buf
                resp.length = bytes.size.toUInt()
            } else {
                resp.text = null
                resp.length = 0u
            }
        }
    }

/**
 * iOS engine: libssh2 (statically linked with OpenSSL). See scripts/build-ios-native.sh.
 */
@OptIn(ExperimentalForeignApi::class)
class SshSessionLibssh2(
    private val connection: SshConnection,
    private val callbacks: SshCallbacks,
) : SshSession {
    private var session: CPointer<LIBSSH2_SESSION>? = null
    private var channel: CPointer<LIBSSH2_CHANNEL>? = null
    private var sock: Int = -1

    @Volatile
    private var closed = false

    /** Read-loop coroutine: on close, wait for it to exit before releasing libssh2 resources, to avoid use-after-free. */
    private var readerJob: Job? = null
    private var lastKeepaliveMs: Long = 0

    /** Auxiliary channels such as direct-tcpip and stream exec read and write only on [serialDispatcher]. */
    private val auxiliaryChannels = mutableListOf<CPointer<LIBSSH2_CHANNEL>>()

    private var hostKeyInfo: HostKeyInfo? = null
    private var authFailureReason: String? = null

    // Single-threaded serial dispatcher: libssh2 sessions are not thread-safe; read loop / write / resize / keepalive /
    // cleanup: Group all operations into a single thread to prevent concurrency in accessing the same LIBSSH2_SESSION.
    private val serialDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(serialDispatcher + SupervisorJob())

    companion object {
        init {
            libssh2_init(0)
        }
    }

    override fun connectAndStart(
        columns: Int,
        rows: Int,
    ): SessionInfo {
        val (s, banner) = connectAndAuthenticate()

        // The handshake/authentication is performed in blocking mode; once the interactive phase begins, switch to non-blocking mode:
        // The read loop can provide a response to `close()` and can also periodically send keepalive requests on the same thread.
        // (libssh2 sessions are not thread-safe; keepalive requests must be sent from the read thread).
        libssh2_session_set_blocking(s, 0)
        if (connection.keepAliveSeconds > 0) {
            libssh2_keepalive_config(s, 1, connection.keepAliveSeconds.toUInt())
        }

        val ch =
            openChannel(s)
                ?: run {
                    cleanup()
                    throw SshException("打开会话通道失败")
                }
        channel = ch
        val term = connection.terminalType
        // cinterop’s String parameters are encoded in UTF-8: the length must be specified in bytes (following the convention for the `username` parameter in `authenticate`).
        val termLen = term.encodeToByteArray().size
        if (retryUntilSuccess {
                libssh2_channel_request_pty_ex(ch, term, termLen.toUInt(), null, 0u, columns, rows, 0, 0)
            } != 0
        ) {
            cleanup()
            throw SshException("PTY 请求失败")
        }
        if (retryUntilSuccess { libssh2_channel_process_startup(ch, "shell", 5u, null, 0u) } != 0) {
            cleanup()
            throw SshException("启动 shell 失败")
        }
        startReader(ch)
        val hk = hostKeyInfo
        return SessionInfo(banner, hk, hk?.algorithm ?: "")
    }

    override fun connectAuthOnly(): SessionInfo? =
        try {
            val (s, banner) = connectAndAuthenticate()
            libssh2_session_set_blocking(s, 0)
            if (connection.keepAliveSeconds > 0) {
                libssh2_keepalive_config(s, 1, connection.keepAliveSeconds.toUInt())
            }
            val hk = hostKeyInfo
            SessionInfo(banner, hk, hk?.algorithm ?: "")
        } catch (_: Exception) {
            cleanup()
            null
        }

    /** Establish a TCP + SSH handshake + host key verification + authentication; return the session and banner. */
    private fun connectAndAuthenticate(): Pair<CPointer<LIBSSH2_SESSION>?, String> {
        sock = tcpConnect(connection.host, connection.port)
        callbacks.onTraceStep("tcp")
        val s = libssh2_session_init_ex(null, null, null, null) ?: throw SshException("libssh2 初始化失败")
        session = s
        if (libssh2_session_handshake(s, sock) != 0) {
            throw SshException("SSH 握手失败: ${lastError(s)}")
        }
        callbacks.onTraceStep("kex")
        val banner = libssh2_session_banner_get(s)?.toKString() ?: ""

        val hostKey = computeHostKey(s)
        hostKeyInfo = hostKey
        if (!callbacks.verifyHostKey(hostKey)) {
            cleanup()
            throw SshException("主机密钥验证未通过")
        }
        if (!authenticate(s)) {
            cleanup()
            throw SshException("认证失败${authFailureReason ?: ""}")
        }
        callbacks.onTraceStep("auth")
        return s to banner
    }

    // ---------- Authentication ----------

    private fun authenticate(s: CPointer<LIBSSH2_SESSION>?): Boolean {
        // cinterop’s String parameters are encoded in UTF-8, and their length must be specified in bytes; otherwise, non-ASCII credentials will fail.
        val usernameBytes = connection.username.encodeToByteArray()
        var pubkeyError: String? = null
        connection.privateKeyPem?.let { pem ->
            val pemLen = pem.encodeToByteArray().size.toULong()
            var rc =
                libssh2_userauth_publickey_frommemory(
                    s,
                    connection.username,
                    usernameBytes.size.toULong(),
                    null,
                    0u,
                    pem,
                    pemLen,
                    "",
                )
            // Encryption of private key: Prompt the user to input a password, then retry with "passphrase"
            if (rc != 0 && isEncryptedPem(pem)) {
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
                if (!passphrase.isNullOrEmpty()) {
                    rc =
                        libssh2_userauth_publickey_frommemory(
                            s,
                            connection.username,
                            usernameBytes.size.toULong(),
                            null,
                            0u,
                            pem,
                            pemLen,
                            passphrase,
                        )
                }
            }
            if (rc == 0) return true
            pubkeyError = lastError(s)
        }
        connection.password?.let { pw ->
            if (libssh2_userauth_password_ex(
                    s,
                    connection.username,
                    usernameBytes.size.toUInt(),
                    pw,
                    pw.encodeToByteArray().size.toUInt(),
                    null,
                ) == 0
            ) {
                return true
            }
        }
        // KBI fallback (including secondary verification scenarios). kbiHandler is a global singleton; a mutex is used to prevent concurrency between sessions.
        // Clear after use: staticCFunction cannot capture variables, so the process globally retains the callbacks (→ controller
        // → 10k row buffer); if not cleared, it cannot be recycled after the session closes until the next KBI overwrite.
        val kbiOk =
            runBlocking {
                kbiMutex.withLock {
                    kbiHandler = callbacks::onPrompt
                    try {
                        libssh2_userauth_keyboard_interactive_ex(
                            s,
                            connection.username,
                            usernameBytes.size.toUInt(),
                            kbiCallback,
                        ) == 0
                    } finally {
                        kbiHandler = null
                    }
                }
            }
        if (kbiOk) return true
        authFailureReason =
            buildString {
                append("：")
                if (pubkeyError != null) {
                    append("私钥认证失败（").append(pubkeyError).append("）")
                    append("；若私钥带 passphrase，请确认口令是否正确，或改用密码认证")
                } else {
                    append("密码与 keyboard-interactive 均未通过")
                }
            }
        return false
    }

    // ---------- Data Transmission and Reception ----------

    /** In non-blocking mode, retry until a non-EAGAIN error occurs, and return the last return code (0 = success). */
    private fun retryUntilSuccess(block: () -> Int): Int {
        val deadline = Clock.System.now().toEpochMilliseconds() + 10_000
        var rc: Int
        while (true) {
            rc = block()
            if (rc != LIBSSH2_ERROR_EAGAIN) return rc
            if (closed || Clock.System.now().toEpochMilliseconds() >= deadline) return rc
            usleep(30_000u)
        }
    }

    /** Open a channel in non-blocking mode (retry on EAGAIN). */
    private fun openChannel(s: CPointer<LIBSSH2_SESSION>?): CPointer<LIBSSH2_CHANNEL>? {
        val deadline = Clock.System.now().toEpochMilliseconds() + 10_000
        while (!closed && Clock.System.now().toEpochMilliseconds() < deadline) {
            val ch = libssh2_channel_open_ex(s, "session", 7u, (2u * 1024u * 1024u), 32768u, null, 0u)
            if (ch != null) return ch
            usleep(30_000u)
        }
        return null
    }

    /** Send application-layer keepalives at scheduled intervals (must be called from a read thread; libssh2 sessions are not thread-safe). */
    private fun maybeSendKeepalive(s: CPointer<LIBSSH2_SESSION>?) {
        if (connection.keepAliveSeconds <= 0) return
        val now = Clock.System.now().toEpochMilliseconds()
        if (now - lastKeepaliveMs >= connection.keepAliveSeconds * 1000L) {
            lastKeepaliveMs = now
            memScoped {
                val sec = alloc<IntVar>()
                libssh2_keepalive_send(s, sec.ptr)
            }
        }
    }

    private fun startReader(ch: CPointer<LIBSSH2_CHANNEL>?) {
        readerJob =
            scope.launch {
                try {
                    val buf = ByteArray(64 * 1024)
                    while (!closed) {
                        val n =
                            buf.usePinned { pinned ->
                                libssh2_channel_read_ex(ch, 0, pinned.addressOf(0), buf.size.toULong())
                            }
                        when {
                            n > 0 -> callbacks.onOutput(buf.copyOf(n.toInt()))
                            n == 0L -> break
                            n.toInt() == LIBSSH2_ERROR_EAGAIN -> {
                                maybeSendKeepalive(session)
                                delay(30)
                            }
                            else -> break
                        }
                    }
                } catch (_: Exception) {
                }
                handleClosed()
            }
    }

    private fun handleClosed() {
        if (closed) return
        closed = true
        // Be sure to release the channel/session/socket when the remote connection disconnects; otherwise, a file descriptor (fd) and a libssh2 object will be leaked each time the connection is reconnected.
        // This function runs on the read thread (serialDispatcher) and does not have any concurrency with read/write operations.
        cleanup()
        callbacks.onClosed(null)
    }

    override fun resize(
        columns: Int,
        rows: Int,
        widthPx: Int,
        heightPx: Int,
    ) {
        scope.launch {
            channel?.let { libssh2_channel_request_pty_size_ex(it, columns, rows, widthPx, heightPx) }
        }
    }

    // iOS does not currently implement the exec+pty channel (additional channel management is required under the libssh2 serial thread model);
    // Returns null → the installer/bootstrap caller falls back to the runCommand path.
    override fun startExec(
        command: String,
        columns: Int,
        rows: Int,
    ): SshExecChannel? = null

    override fun startExecRaw(command: String): SshExecChannel? =
        runBlocking {
            withContext(serialDispatcher) {
                val s = session ?: return@withContext null
                if (closed) return@withContext null
                val ch = openChannel(s) ?: return@withContext null
                val commandLength = command.encodeToByteArray().size
                if (retryUntilSuccess {
                        libssh2_channel_process_startup(ch, "exec", 4u, command, commandLength.toUInt())
                    } != 0
                ) {
                    libssh2_channel_free(ch)
                    return@withContext null
                }
                auxiliaryChannels.add(ch)
                wrapAuxiliaryChannel(ch)
            }
        }

    override fun openDirectTcpip(
        host: String,
        port: Int,
    ): SshExecChannel? =
        runBlocking {
            withContext(serialDispatcher) {
                val s = session ?: return@withContext null
                if (closed) return@withContext null
                val deadline = Clock.System.now().toEpochMilliseconds() + 10_000
                var ch: CPointer<LIBSSH2_CHANNEL>? = null
                while (!closed && Clock.System.now().toEpochMilliseconds() < deadline) {
                    ch = libssh2_channel_direct_tcpip_ex(s, host, port, "127.0.0.1", 0)
                    if (ch != null) break
                    delay(30)
                }
                ch?.let {
                    auxiliaryChannels.add(it)
                    wrapAuxiliaryChannel(it)
                }
            }
        }

    /**
     * Wrap the libssh2 auxiliary channel into a blocking public interface. Each native call passes through a serial dispatcher,
     * but the EAGAIN wait uses suspend, letting video reads and control writes advance alternately within the same SSH session.
     */
    private fun wrapAuxiliaryChannel(ch: CPointer<LIBSSH2_CHANNEL>): SshExecChannel =
        object : SshExecChannel {
            @Volatile
            private var channelClosed = false

            // The control messages for `direct-tcpip` use a length-based framing protocol. Multiple sending coroutines return `EAGAIN`
            // If interleaved writes occur during this period, the frame header and payload will be corrupted; the same auxiliary channel must ensure
            // You can only start the next batch after the entire ByteArray has been written.
            private val writeMutex = Mutex()

            override fun read(): ByteArray? = readStream(0)

            override fun readErr(): ByteArray? = readStream(1)

            private fun readStream(streamId: Int): ByteArray? =
                runBlocking {
                    withContext(serialDispatcher) {
                        val buffer = ByteArray(64 * 1024)
                        while (!closed && !channelClosed) {
                            val read =
                                buffer.usePinned { pinned ->
                                    libssh2_channel_read_ex(
                                        ch,
                                        streamId,
                                        pinned.addressOf(0),
                                        buffer.size.toULong(),
                                    )
                                }
                            when {
                                read > 0 -> return@withContext buffer.copyOf(read.toInt())
                                read == 0L -> return@withContext null
                                read.toInt() == LIBSSH2_ERROR_EAGAIN -> {
                                    maybeSendKeepalive(session)
                                    delay(15)
                                }
                                else -> return@withContext null
                            }
                        }
                        null
                    }
                }

            override fun write(data: ByteArray) {
                if (channelClosed || data.isEmpty()) return
                val copy = data.copyOf()
                scope.launch {
                    writeMutex.withLock {
                        var offset = 0
                        while (!closed && !channelClosed && offset < copy.size) {
                            val written =
                                copy.usePinned { pinned ->
                                    termish_channel_write(
                                        ch,
                                        pinned.addressOf(offset).reinterpret(),
                                        (copy.size - offset).toULong(),
                                    )
                                }
                            when {
                                written > 0 -> offset += written.toInt()
                                written.toInt() == LIBSSH2_ERROR_EAGAIN -> delay(15)
                                else -> return@withLock
                            }
                        }
                    }
                }
            }

            override fun close() {
                if (channelClosed) return
                channelClosed = true
                scope.launch { closeAuxiliaryChannel(ch) }
            }
        }

    private fun closeAuxiliaryChannel(ch: CPointer<LIBSSH2_CHANNEL>) {
        if (!auxiliaryChannels.remove(ch)) return
        retryUntilSuccess { libssh2_channel_close(ch) }
        libssh2_channel_free(ch)
    }

    override fun sendData(data: ByteArray) {
        if (closed || data.isEmpty()) return
        val copy = data.copyOf()
        // Mapped to the serial thread: The caller is on the UI thread and cannot directly access the libssh2 session here.
        scope.launch { writeAll(copy) }
    }

    /** Non-blocking full write executed on the serialDispatcher thread. */
    private fun writeAll(data: ByteArray) {
        val ch = channel ?: return
        // cinterop maps the `buf` from `write_ex` to a String (written using UTF-8 encoding plus an explicit length).
        // All terminal input is valid UTF-8 in the local encoding; decoding is lossless.
        val text = data.decodeToString()
        var bytesDone = 0 // Confirmed number of bytes written (counted as full characters)
        var charOffset = 0
        var idleRounds = 0
        while (bytesDone < data.size && !closed) {
            // The `length` parameter must be less than or equal to the actual number of bytes in the remaining string (`bytesDone` only advances to the character bounds).
            val rc =
                libssh2_channel_write_ex(
                    ch,
                    0,
                    text.substring(charOffset),
                    (data.size - bytesDone).toULong(),
                )
            when {
                rc > 0 -> {
                    var n = rc.toInt()
                    // Only skip fully written characters: "rc" may be interrupted by a UTF-8 character.
                    while (charOffset < text.length) {
                        val c = text[charOffset]
                        val charLen =
                            when {
                                c.code < 0x80 -> 1
                                c.code < 0x800 -> 2
                                c.isHighSurrogate() -> 4
                                else -> 3
                            }
                        if (charLen > n) break
                        n -= charLen
                        bytesDone += charLen
                        charOffset += if (c.isHighSurrogate()) 2 else 1
                    }
                    if (n > 0) {
                        // rc falls in the middle of a character: bytes that have already been written will be duplicated when the entire character is resent
                        // (The String API does not support appending to the middle of a byte array; this is extremely rare and is tolerated.)
                        bytesDone += n
                    }
                    idleRounds = 0
                }
                rc.toInt() == LIBSSH2_ERROR_EAGAIN -> {
                    if (++idleRounds > 200) return // ~6s and it still won’t write out; give up
                    usleep(30_000u)
                }
                else -> return // True error (connection may have been disconnected)
            }
        }
    }

    override fun connectAndRun(
        command: String,
        timeoutMs: Long,
    ): CommandResult {
        // Non-interactive exec channel: Used for one-time commands such as booting mosh-server
        return try {
            val (s, _) = connectAndAuthenticate()
            libssh2_session_set_blocking(s, 0)
            val ch =
                openChannel(s)
                    ?: throw SshException("打开 exec 通道失败")
            // Commands may contain non-ASCII characters (e.g., in file names): Specify the length in bytes; otherwise, perform truncation using UTF-8.
            val cmdLen = command.encodeToByteArray().size

            fun startExec(): Int = libssh2_channel_process_startup(ch, "exec", 4u, command, cmdLen.toUInt())
            if (startExec().let {
                    var rc = it
                    var guard = 0
                    while (rc == LIBSSH2_ERROR_EAGAIN && guard++ < 300) {
                        usleep(30_000u)
                        rc = startExec()
                    }
                    rc
                } != 0
            ) {
                libssh2_channel_close(ch)
                libssh2_channel_free(ch)
                throw SshException("启动命令失败: ${lastError(s)}")
            }
            val chunks = ArrayList<ByteArray>()
            val buf = ByteArray(64 * 1024)
            val deadline = Clock.System.now().toEpochMilliseconds() + timeoutMs
            while (Clock.System.now().toEpochMilliseconds() < deadline) {
                val n =
                    buf.usePinned { pinned ->
                        libssh2_channel_read_ex(ch, 0, pinned.addressOf(0), buf.size.toULong())
                    }
                when {
                    n > 0L -> chunks.add(buf.copyOf(n.toInt()))
                    n == 0L -> break
                    n.toInt() == LIBSSH2_ERROR_EAGAIN -> usleep(30_000u)
                    else -> break
                }
            }
            libssh2_channel_close(ch)
            libssh2_channel_free(ch)
            val total = chunks.sumOf { it.size }
            val all = ByteArray(total)
            var off = 0
            for (c in chunks) {
                c.copyInto(all, off)
                off += c.size
            }
            CommandResult(all.decodeToString(), hostKeyInfo)
        } finally {
            cleanup()
        }
    }

    override fun runCommandDetailed(
        command: String,
        timeoutMs: Long,
    ): CommandOutput? =
        runBlocking {
            // libssh2 sessions are not thread-safe: the read loop/keepalive runs in a serial thread,
            // Control-plane commands must be grouped into the same thread to avoid concurrency in accessing LIBSSH2_SESSION.
            withContext(serialDispatcher) { doRunCommand(command, timeoutMs) }
        }

    /** Open an SFTP channel over an authenticated connection (blocking mode; the caller is responsible for `shutdown` + `close`). */
    fun openSftp(): CPointer<LIBSSH2_SFTP>? {
        val (s, _) = connectAndAuthenticate()
        libssh2_session_set_blocking(s, 1)
        // Blocking mode + keepalive_config: libssh2 automatically sends keepalive packets (no app thread required)
        if (connection.keepAliveSeconds > 0) {
            libssh2_keepalive_config(s, 1, connection.keepAliveSeconds.toUInt())
        }
        // Blocking call timeout: If the connection is broken or there is no response after 30 seconds, back with an error → the operation throws an exception → display a UI banner.
        // (If left unchecked, it will hang indefinitely: After an iOS SFTP connection is lost, `list` and `download` operations are permanently blocked, similar to the terminal
        //  (Compared to path-based keepalive detection, this serves as a fallback for request-response channels.)
        libssh2_session_set_timeout(s, 30_000L)
        return libssh2_sftp_init(s)
    }

    /** Open a temporary exec channel on an authenticated session to execute control-plane commands without re-authentication or interrupting the interactive shell. */
    private fun doRunCommand(
        command: String,
        timeoutMs: Long,
    ): CommandOutput? {
        val s = session ?: return null
        if (closed) return null
        val ch = openChannel(s) ?: return null
        // Commands may contain non-ASCII characters: Specify the length in bytes (UTF-8 truncation will result in the remote receiving only half a character).
        val cmdLen = command.encodeToByteArray().size
        return try {
            if (retryUntilSuccess {
                    libssh2_channel_process_startup(ch, "exec", 4u, command, cmdLen.toUInt())
                } != 0
            ) {
                return null
            }
            val outChunks = ArrayList<ByteArray>()
            val errChunks = ArrayList<ByteArray>()
            val buf = ByteArray(16 * 1024)
            // Alternate reading from stdout(0) and stderr(1): When one stream is full, it blocks the other stream's channel.
            // In non-blocking mode, a return value of 0 indicates EOF on this stream; when both streams reach EOF, command output ends.
            var outEof = false
            var errEof = false
            val deadline = Clock.System.now().toEpochMilliseconds() + timeoutMs
            while (Clock.System.now().toEpochMilliseconds() < deadline && !(outEof && errEof)) {
                var progressed = false
                if (!outEof) {
                    val n =
                        buf.usePinned { pinned ->
                            libssh2_channel_read_ex(ch, 0, pinned.addressOf(0), buf.size.toULong())
                        }
                    when {
                        n > 0L -> {
                            outChunks.add(buf.copyOf(n.toInt()))
                            progressed = true
                        }
                        n == 0L -> outEof = true
                        n.toInt() == LIBSSH2_ERROR_EAGAIN -> Unit
                        else -> break
                    }
                }
                if (!errEof) {
                    val n =
                        buf.usePinned { pinned ->
                            libssh2_channel_read_ex(ch, 1, pinned.addressOf(0), buf.size.toULong())
                        }
                    when {
                        n > 0L -> {
                            errChunks.add(buf.copyOf(n.toInt()))
                            progressed = true
                        }
                        n == 0L -> errEof = true
                        n.toInt() == LIBSSH2_ERROR_EAGAIN -> Unit
                        else -> break
                    }
                }
                if (!progressed) usleep(30_000u)
            }
            CommandOutput(
                stdout = outChunks.joinToString("") { it.decodeToString() },
                stderr = errChunks.joinToString("") { it.decodeToString() },
                exitCode = libssh2_channel_get_exit_status(ch),
            )
        } catch (_: Exception) {
            null
        } finally {
            try {
                libssh2_channel_close(ch)
                libssh2_channel_free(ch)
            } catch (_: Exception) {
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // Do not allow the calling thread (usually the UI main thread) to block while waiting: `channel_close` in `cleanup` on a dead channel
        // The EAGAIN loop may run for several seconds. Async operations are converted to a serial thread; wait for the read loop to exit before releasing resources.
        scope.launch {
            withTimeoutOrNull(2000) { readerJob?.join() }
            cleanup()
        }
        callbacks.onClosed(null)
    }

    override fun isActive(): Boolean = !closed

    // ---------- Tools ----------

    private fun cleanup() {
        auxiliaryChannels.toList().forEach { ch ->
            try {
                libssh2_channel_close(ch)
                libssh2_channel_free(ch)
            } catch (_: Exception) {
            }
        }
        auxiliaryChannels.clear()
        channel?.let {
            retryUntilSuccess { libssh2_channel_close(it) }
            libssh2_channel_free(it)
        }
        channel = null
        session?.let {
            libssh2_session_disconnect_ex(it, 11, "Bye", "")
            libssh2_session_free(it)
        }
        session = null
        if (sock >= 0) {
            close(sock)
            sock = -1
        }
    }

    private fun lastError(s: CPointer<LIBSSH2_SESSION>?): String =
        memScoped {
            val err = alloc<CPointerVar<ByteVar>>()
            val errLen = alloc<IntVar>()
            libssh2_session_last_error(s, err.ptr, errLen.ptr, 0)
            err.value?.readBytes(errLen.value)?.toKString() ?: "未知错误"
        }

    private fun computeHostKey(s: CPointer<LIBSSH2_SESSION>?): HostKeyInfo =
        memScoped {
            val len = alloc<ULongVar>()
            val type = alloc<IntVar>()
            val keyPtr = libssh2_session_hostkey(s, len.ptr, type.ptr)
            val keyLen = len.value.toInt()
            val typeName = hostKeyTypeName(type.value)
            if (keyPtr == null || keyLen <= 0) {
                return@memScoped HostKeyInfo(typeName, "SHA256:", "")
            }
            val keyBytes = keyPtr.readBytes(keyLen)
            // The blob returned by `libssh2_session_hostkey` already contains the prefix of the algorithm string.
            // (Consistent with libssh2_hostkey_hash(SHA256), i.e., the OpenSSH fingerprint standard),
            // Calculate the SHA256 hash directly on the entire blob; do not manually concatenate the type prefix again.
            HostKeyInfo(typeName, "SHA256:" + base64Encode(Sha256.digest(keyBytes)).trimEnd('='), "")
        }

    private fun hostKeyTypeName(type: Int): String =
        when (type) {
            LIBSSH2_HOSTKEY_TYPE_RSA -> "ssh-rsa"
            LIBSSH2_HOSTKEY_TYPE_DSS -> "ssh-dss"
            LIBSSH2_HOSTKEY_TYPE_ECDSA_256 -> "ecdsa-sha2-nistp256"
            LIBSSH2_HOSTKEY_TYPE_ECDSA_384 -> "ecdsa-sha2-nistp384"
            LIBSSH2_HOSTKEY_TYPE_ECDSA_521 -> "ecdsa-sha2-nistp521"
            LIBSSH2_HOSTKEY_TYPE_ED25519 -> "ssh-ed25519"
            else -> "unknown"
        }

    // ---------- POSIX TCP ----------

    private fun tcpConnect(
        host: String,
        port: Int,
    ): Int =
        memScoped {
            val hints = alloc<addrinfo>()
            hints.ai_family = 0
            hints.ai_socktype = SOCK_STREAM
            hints.ai_protocol = IPPROTO_TCP
            val res = alloc<CPointerVar<addrinfo>>()
            val gai = getaddrinfo(host, port.toString(), hints.ptr, res.ptr)
            if (gai != 0) {
                throw SshException("DNS 解析失败: ${gai_strerror(gai)?.toKString()}")
            }
            var fd = -1
            var cur = res.value
            var lastErr = 0
            var flags = 0
            // Connection timeout: Alignment with sshj’s `setConnectTimeout` (quick failure in case of packet loss due to blackhole addresses or firewalls,
            // Rather than depending on the kernel’s TCP retries (which can take 60 seconds or more on macOS/iOS, resulting in a continuously spinning loading icon in the UI).
            val timeoutMs = connection.connectTimeoutMillis.toInt()
            while (cur != null) {
                val s = socket(cur.pointed.ai_family, cur.pointed.ai_socktype, cur.pointed.ai_protocol)
                if (s < 0) {
                    lastErr = errno
                    cur = cur.pointed.ai_next
                    continue
                }
                // Non-blocking `connect`: Immediately returns `EINPROGRESS`; use `poll` to wait for writability + timeout
                flags = fcntl(s, F_GETFL, 0)
                fcntl(s, F_SETFL, flags or O_NONBLOCK)
                val rc = connect(s, cur.pointed.ai_addr, cur.pointed.ai_addrlen)
                if (rc == 0) {
                    fd = s // Connect immediately (loopback/local machine)
                    break
                }
                if (errno != EINPROGRESS) {
                    lastErr = errno
                    close(s)
                    cur = cur.pointed.ai_next
                    continue
                }
                val pfd = alloc<pollfd>()
                pfd.fd = s
                pfd.events = POLLOUT.toShort()
                val prc = poll(pfd.ptr, 1u, timeoutMs)
                if (prc > 0 && (pfd.revents.toInt() and (POLLOUT or POLLERR or POLLHUP)) != 0) {
                    // Result of the second connect attempt after the poll is ready: 0 or EISCONN indicates success for a connection
                    // (POSIX standard technique to avoid the cross-platform type overhead of `getsockopt SO_ERROR`)
                    val rc2 = connect(s, cur.pointed.ai_addr, cur.pointed.ai_addrlen)
                    if (rc2 == 0 || errno == EISCONN) {
                        fd = s
                        break
                    }
                    lastErr = errno
                } else if (prc == 0) {
                    lastErr = ETIMEDOUT
                } else {
                    lastErr = errno
                }
                close(s)
                cur = cur.pointed.ai_next
            }
            freeaddrinfo(res.value)
            if (fd < 0) {
                val msg =
                    if (lastErr == ETIMEDOUT) {
                        "连接超时（${timeoutMs / 1000}s 无响应）"
                    } else {
                        "无法连接 $host:$port (errno $lastErr)"
                    }
                throw SshException(msg)
            }
            // Resume blocking mode: subsequent libssh2 reads/writes rely on a blocking socket
            fcntl(fd, F_SETFL, flags)
            fd
        }
}
