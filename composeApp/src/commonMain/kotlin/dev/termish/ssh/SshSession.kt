package dev.termish.ssh

import dev.termish.util.base64Decode

/**
 * Authentication methods.
 */
enum class SshAuthMethod { PASSWORD, KEYBOARD_INTERACTIVE, PUBLIC_KEY, PASSPHRASE, NONE }

/** Authentication interaction prompts (keyboard-interactive / password change). */
data class AuthPrompt(
    val method: SshAuthMethod,
    val name: String,
    val instruction: String,
    val prompts: List<PromptField>,
)

data class PromptField(
    val label: String,
    val echo: Boolean,
)

/**
 * Determine whether a PEM private key is subject to encryption (passphrase-protected):
 * - PKCS#8: `BEGIN ENCRYPTED PRIVATE KEY`
 * - Traditional PEM: `Proc-Type: 4,ENCRYPTED`
 * - OpenSSH: decode the `ciphername` in the `openssh-key-v1` header; if it is not `none`, the key is encrypted
 */
internal fun isEncryptedPem(pem: String): Boolean {
    if (pem.contains("ENCRYPTED PRIVATE KEY") || pem.contains("Proc-Type: 4,ENCRYPTED")) return true
    if (!pem.contains("OPENSSH PRIVATE KEY")) return false
    return try {
        val b64 = pem.lineSequence().filter { !it.startsWith("-----") }.joinToString("")
        val bytes = base64Decode(b64)
        // The magic number for `openssh-key-v1` is 15 bytes (including the trailing NUL); if one byte is missing, the
        // ciphername length reads as misaligned, and a plaintext key is taken for an encrypted one
        val marker = "openssh-key-v1\u0000".encodeToByteArray()
        if (bytes.size < marker.size + 8 || !bytes.copyOfRange(0, marker.size).contentEquals(marker)) {
            return false
        }
        var off = marker.size
        val clen = readIntBE(bytes, off)
        off += 4
        // `ciphername` is ASCII; JVM character sets cannot be used across platforms.
        val cipher =
            buildString {
                for (b in bytes.copyOfRange(off, off + clen)) append(b.toInt().toChar())
            }
        cipher != "none"
    } catch (_: Exception) {
        // If parsing fails, handle it as plaintext and display a prompt for the passphrase only after the authentication phase naturally fails
        false
    }
}

private fun readIntBE(
    b: ByteArray,
    at: Int,
): Int =
    ((b[at].toInt() and 0xFF) shl 24) or
        ((b[at + 1].toInt() and 0xFF) shl 16) or
        ((b[at + 2].toInt() and 0xFF) shl 8) or
        (b[at + 3].toInt() and 0xFF)

/** Server host key fingerprint (used for TOFU / known-hosts verification). */
data class HostKeyInfo(
    val algorithm: String,
    val fingerprintSha256: String,
    val fingerprintMd5: String,
)

data class SessionInfo(
    val serverVersion: String,
    val hostKey: HostKeyInfo?,
    val kexAlgorithm: String,
)

/** Results of a single command execution (e.g., Mosh boot). */
data class CommandResult(
    val output: String,
    val hostKey: HostKeyInfo?,
)

/** All parameters required for a single connection (excluding callbacks). */
data class SshConnection(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String? = null,
    val privateKeyPem: String? = null,
    val connectTimeoutMillis: Long = 15_000,
    /** Idle keepalive interval (seconds); <=0 indicates that no app-layer keepalives are sent. */
    val keepAliveSeconds: Int = 30,
    /** PTY terminal type ($TERM): default xterm-256color (matches the terminal capabilities of the local machine);
     *  xterm / vt100 / linux are provided as compatibility alternatives (servers will fallback based on terminfo capabilities). */
    val terminalType: String = "xterm-256color",
)

/**
 * Session callbacks. All callbacks are triggered on the session’s background thread (except [onPrompt]; see below).
 *
 * - [onOutput]/[onStderr] are suspended functions: the engine calls them in the reader coroutine, and the calling code can
 *   Apply backpressure at the bounds (for example, suspend when enqueuing to a bounded queue, rather than blocking the reader thread).
 * - [onExitStatus]/[onClosed] must be thread-safe and return as quickly as possible; the upper layer decides how to switch back to the UI thread.
 * - [onPrompt] is a suspended function: the engine calls it during the authentication process, allowing the upper layer to display a UI prompt.
 * - [verifyHostKey] Called synchronously during the handshake phase; returning false will terminate the connection.
 */
interface SshCallbacks {
    suspend fun onOutput(data: ByteArray)

    suspend fun onStderr(data: ByteArray)

    fun onExitStatus(status: Int)

    fun onClosed(reason: String?)

    suspend fun onPrompt(prompt: AuthPrompt): List<String>?

    fun verifyHostKey(hostKey: HostKeyInfo): Boolean

    /**
     * Connection-phase events (tcp/kex/auth…): Callbacks triggered when the engine completes the corresponding phase,
     * For TermTrace to break down the time spent at each stage. Default empty implementation (can be ignored if you are not concerned with the caller of `trace`).
     */
    fun onTraceStep(step: String) {}
}

/**
 * Platform-independent SSH session abstraction. The engine is subjected to platform injection (JVM: sshj; iOS: libssh2/NTermish).
 */
interface SshSession {
    /** Blocking: Connection + Authentication + Open session channel + Launch shell. */
    fun connectAndStart(
        columns: Int,
        rows: Int,
    ): SessionInfo

    /**
     * Blocking: Connection + authentication only (no shell, no interactive channel).
     * For use with exec-type connections (such as screen streaming); a return value of null indicates that the platform has not implemented this feature.
     */
    fun connectAuthOnly(): SessionInfo? = null

    /** Notify the remote of window size changes (bytes/pixels). */
    fun resize(
        columns: Int,
        rows: Int,
        widthPx: Int,
        heightPx: Int,
    )

    /** Write bytes (keyboard input) to a remote shell. */
    fun sendData(data: ByteArray)

    /** Execute a single command and wait for it to exit (one-time calls such as Mosh bootstrapping; non-interactive exec channels). */
    fun connectAndRun(
        command: String,
        timeoutMs: Long = 15_000,
    ): CommandResult

    /**
     * Open a temporary exec channel on the same authenticated connection to execute the command, returning stdout;
     * Returns null on failure, disconnection, or timeout. Does not re-authenticate or interrupt the interactive shell.
     * For use by control-plane queries (system probes, Herdr API, etc.); execute one-time commands and
     * Use [connectAndRun] (connection + authenticate + execute + close) when the connection has not yet been established.
     * The default implementation delegates to [runCommandDetailed] (the engine only needs to implement the latter).
     */
    fun runCommand(
        command: String,
        timeoutMs: Long = 15_000,
    ): String? = runCommandDetailed(command, timeoutMs)?.stdout

    /**
     * Execute a single command and return stdout / stderr / exit code.
     * Diagnostics for errors require stderr and exit codes: in case of a failure of the herdr CLI, the error JSON is written to stderr and
     * exit code non-zero (e.g., `herdr agent prompt` returns exit 1 for an invalid pane +
     * stderr `{"error":{...}}` — Reading only from stdout will cause failures to be mistakenly interpreted as successes.
     * The platform implementation must read from two streams (failing to read from stderr will cause blocking of the channel due to remote buffering becoming full).
     */
    fun runCommandDetailed(
        command: String,
        timeoutMs: Long = 15_000,
    ): CommandOutput?

    /**
     * Detect remote systems (Termius-style automatic recognition): Execute on an authenticated connection
     * [SYSTEM_PROBE_COMMAND], returns the raw output; returns null on failure.
     * The default implementation delegates to [runCommand] (the engine does not need to reimplement it).
     */
    fun probeSystem(): String? = runCommand(SYSTEM_PROBE_COMMAND, timeoutMs = 5_000)

    /**
     * Exec channels with PTY (e.g., `herdr` TUI: crossterm requires a TTY; it will panic if no TTY is available).
     * No interactive shell—no prompt or command echo; the output is the program's UI itself.
     * Returns a channel (blocking read / write / close); returns null on failure or if no connection is available.
     * Note: The pty size of an exec channel cannot be adjusted later (it is fixed at the size allocated at creation).
     */
    fun startExec(
        command: String,
        columns: Int,
        rows: Int,
    ): SshExecChannel?

    /**
     * Streaming exec channels without a PTY (such as screen frames: binary output cannot be rewritten by a PTY).
     * No interactive shell; stdout = raw byte stream, stdin = raw input.
     * Returns a channel (blocking read / write / close); returns null on failure or if no connection is available.
     * The platform must actually implement this (without `pty`); if the implementation is not complete, return `null`.
     */
    fun startExecRaw(command: String): SshExecChannel? = null

    /**
     * Open a direct-tcpip channel over an authenticated SSH connection to connect to [host]:[port], which is visible to the remote host.
     *
     * A typical use case is accessing an internal service that only has a listener on a remote loopback address: data is transmitted end-to-end by reusing SSH encryption,
     * There is no need to expose or perform port forwarding for the service port to the public internet. The return channel’s stdout and stdin correspond to the target TCP
     * The read/write direction of the connection; returns null if the platform has not implemented it or if there is a failure.
     */
    fun openDirectTcpip(
        host: String,
        port: Int,
    ): SshExecChannel? = null

    /** Close the session proactively. */
    fun close()

    /**
     * Perform end-to-end health checks on established SSH connections.
     *
     * [isActive] simply indicates that the client object has not yet been actively closed; after the device enters sleep mode or the network toggles, the TCP connection may
     * It has become a half-open connection. Supported platforms should send an SSH request requiring a server response and, in
     * Confirm within [timeoutMillis]; the default implementation serves only as a conservative fallback for platforms lacking active detection capabilities.
     */
    fun checkAlive(timeoutMillis: Long = 2_500L): Boolean = isActive()

    fun isActive(): Boolean
}

/**
 * exec+pty channel: stdout = byte stream rendered by the program, stdin = raw input.
 *
 * The result of a single command execution (stdout + stderr + exit code; null if the exit code is unavailable).
 */

data class CommandOutput(
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
)

interface SshExecChannel {
    /** Blocks reading the next block (stdout); returns null on EOF or closure. */
    fun read(): ByteArray?

    /** Blocking reading the next block (stderr); failing to read it will cause the channel to block due to a full buffer; by default, it is not read. */
    fun readErr(): ByteArray? = null

    /** Write to a remote destination (exec channel stdin). */
    fun write(data: ByteArray)

    /** Close channel (idempotent). */
    fun close()
}

/** Platform Factory: Create an [SshSession] for the corresponding engine. */
expect fun createSshSession(
    connection: SshConnection,
    callbacks: SshCallbacks,
): SshSession

class SshException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
