package dev.macodes.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.macodes.data.Host
import dev.macodes.data.HostRepository
import dev.macodes.herdr.HerdrMonitor
import dev.macodes.ssh.AuthPrompt
import dev.macodes.ssh.HostKeyInfo
import dev.macodes.ssh.MoshSession
import dev.macodes.ssh.SshCallbacks
import dev.macodes.ssh.SshConnection
import dev.macodes.ssh.SshSession
import dev.macodes.ssh.createSshSession
import dev.macodes.term.TerminalBuffer
import dev.macodes.term.TerminalEmulator
import dev.macodes.term.TerminalSelection
import dev.macodes.term.argbToRgb
import dev.macodes.ui.theme.TerminalThemes
import dev.macodes.util.NetworkChangeKind
import dev.macodes.util.SessionKeepAlive
import dev.macodes.util.TermLog
import dev.macodes.util.TermTrace
import dev.macodes.util.ioDispatcher
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock

enum class ConnStatus { IDLE, CONNECTING, AUTH, CONNECTED, CLOSED, ERROR }

enum class SessionReconnectCause {
    CONNECTION_LOST,
    NETWORK_CHANGED,
    FOREGROUND_RESUME,
}

/** mosh link-loss alert threshold (seconds): both sides heartbeat ~3s; 5s avoids jitter from a single lost packet.
 *  The "Out of Contact" banner is shared with the status dot. */
internal const val LINK_LOST_THRESHOLD_SECONDS = 5

data class AuthPromptRequest(
    val prompt: AuthPrompt,
) {
    internal val deferred = CompletableDeferred<List<String>?>()
}

data class HostKeyRequest(
    val key: HostKeyInfo,
    /** The server fingerprint does not match the saved one (due to server reinstallation, hardware replacement, port forwarding changes, etc.); the user must verify it again. */
    val changed: Boolean = false,
    val previousFingerprint: String? = null,
) {
    internal val deferred = CompletableDeferred<Boolean>()
}

/** Ensure that all SSH UI confirmations share the same timeout semantics to prevent the terminal, SFTP, and agent from waiting indefinitely. */
internal const val SSH_PROMPT_TIMEOUT_MS = 120_000L

internal suspend fun awaitAuthPromptAnswer(deferred: CompletableDeferred<List<String>?>): List<String>? = withTimeoutOrNull(SSH_PROMPT_TIMEOUT_MS) { deferred.await() }

internal fun awaitHostKeyPromptAnswer(deferred: CompletableDeferred<Boolean>): Boolean =
    runBlocking {
        withTimeoutOrNull(SSH_PROMPT_TIMEOUT_MS) { deferred.await() } ?: false
    }

/**
 * Terminal Session Controller: Maintains terminal state (buffer / emulator / connection status) for UI observation,
 * and route keyboard input to the current transport (SSH shell / mosh).
 *
 * Connection Orchestration (SSH/Mosh connection establishment, reconnect, session launch types, network events) in
 * [SessionConnector]—State ownership remains here (Compose observers remain unchanged),
 * The connector is accessed and modified via internal calls within the same package.
 */
enum class TerminalLaunchMode {
    SHELL,
    HERDR,
}

class TerminalController(
    val host: Host,
    internal val password: String?,
    internal val privateKeyPem: String?,
    internal val repository: HostRepository,
    /** Automatically reconnect upon unexpected disconnection (exponential backoff, up to 3 attempts). */
    internal val autoReconnect: Boolean = true,
    /** This session launches a regular shell or herdr; it is not part of the host connection configuration. */
    val launchMode: TerminalLaunchMode = TerminalLaunchMode.SHELL,
    /** Connection error-message provider (retrieve the latest AppStrings when the language toggles). */
    private val strings: () -> AppStrings = { appStringsFor("en") },
    /** The time the entry was first created; this remains unchanged after a reconnection following a disconnection or after resuming a process. */
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
    /** SSH session factory (performs injection of `fake` in tests; uses the platform engine by default in production). */
    internal val sessionFactory: (SshConnection, SshCallbacks) -> SshSession = ::createSshSession,
) {
    internal val launchHerdr: Boolean get() = launchMode == TerminalLaunchMode.HERDR
    val buffer = TerminalBuffer(80, 24, maxScrollbackLines = 10_000)
    val emulator = TerminalEmulator(buffer)
    val selection = TerminalSelection(buffer)

    var status by mutableStateOf(ConnStatus.IDLE)
        internal set
    var errorMessage by mutableStateOf<String?>(null)
        internal set

    /** Current number of reconnection attempts: If this value is >0 and the status is CONNECTING, it indicates that an automatic reconnection is in progress. */
    var reconnectCount by mutableStateOf(0)
        internal set

    /** Reason for the most recent automatic resume; displayed only during reconnect and cleared after a successful connection or manual closure. */
    var reconnectCause by mutableStateOf<SessionReconnectCause?>(null)
        internal set

    /** mosh link-loss seconds (0 = healthy; at threshold the UI shows the “Lost contact” banner; session kept). */
    var linkLostSeconds by mutableStateOf(0)
        internal set
    var title by mutableStateOf(host.name)
        internal set
    var authPrompt by mutableStateOf<AuthPromptRequest?>(null)
        private set
    var hostKeyPrompt by mutableStateOf<HostKeyRequest?>(null)
        private set
    var exitStatus by mutableStateOf<Int?>(null)
        private set

    /** Change number: The UI uses this to determine whether a repaint is needed. */
    var frame by mutableStateOf(0L)
        internal set

    /** Cursor blinking phase: A rendering-layer-only animation state, separate from the protocol’s visibility (buffer.cursorVisible). */
    var cursorBlinkPhase by mutableStateOf(true)
        private set

    /** OSC 52: Callback when a remote program writes to the clipboard (accessed by the UI layer via the system clipboard). */
    var onRemoteClipboard: ((String) -> Unit)? = null

    /** Callback when a remote system is automatically detected and saved (Termius-style recognition; the UI refreshes the host list accordingly). */
    var onSystemDetected: ((Host) -> Unit)? = null

    /** When a Herdr session is launched, Herdr is not installed on the remote machine (the banner shows installation instructions). */
    var herdrNeedsInstall by mutableStateOf(false)
        internal set

    /** herdr installation is underway (progress is shown in a banner to prevent repeated triggers). */
    var herdrInstalling by mutableStateOf(false)
        internal set

    /** Real-time output from the herdr installation script (displayed in the onboarding card; visible when the process is hung and no new progress is being made). */
    var herdrInstallLog by mutableStateOf("")
        internal set

    /** Mosh mode: mosh-server is not installed on the remote (card: Install or fallback to SSH). */
    var moshNeedsInstall by mutableStateOf(false)
        internal set

    /** Mosh installation in progress (a progress card shows the progress to prevent repeated triggers). */
    var moshInstalling by mutableStateOf(false)
        internal set

    /** Real-time output of Mosh installation commands (displayed in the onboarding card; visible when the process is hung and no new progress is being made). */
    var moshInstallLog by mutableStateOf("")

    /** Reason for mosh bootstrap failure (installation card tooltip; null = mosh-server not detected). */
    var moshInstallReason by mutableStateOf<String?>(null)
        internal set

    /** Installing Mosh requires a sudo password (the card shows a password input field; this applies only if the remote user is not root and sudo is not password-less). */
    var moshNeedsSudoPassword by mutableStateOf(false)
        internal set

    /** Detected Herdr executable paths (used by Herdr to launch mosh sessions and perform command injection). */
    internal var herdrBin: String? = null

    /** This session entry has fallen back from mosh to SSH (UDP connection failed, or the user actively made this selection during installation):
     *  Subsequent reconnections will use SSH directly; the system will no longer retry the mosh boot—UDP is blocked by the network environment, and reconnecting will not change that.
     *  Self-healing: Retrying only adds one additional initialization and a 5-second UDP confirmation wait each time; mosh is only retried when a new session is opened.
     *  Intentionally not reset by `close()`: Fallback decisions take effect across reconnections. */
    internal var moshDegradedToSsh = false

    /** Credential signature at the time this session was created: If the credentials change after the host is edited, this can be used to determine that the old session has expired. */
    val credentialKey: String = credentialSignature(host, password, privateKeyPem)

    companion object {
        /** SSH output queue capacity (chunks, each ≤64KB): when full, the reader coroutine suspends, imposing
         *  TCP backpressure—no byte loss and no infinite memory overflow (when using `cat` on large files). */
        private const val OUTPUT_QUEUE_CAPACITY = 256

        /** Main thread batch consumption of single-frame budget: After draining all output arriving in the same frame, trigger a redraw all at once,
         *  Avoid frame jitter caused by small packet flooding—one packet per frame; yield the main thread once the quota is reached. */
        private const val OUTPUT_BATCH_BUDGET_MS = 8
    }

    // ---- Transmission Channel (connector reads and writes; all resources are released upon close)----
    internal var session: SshSession? = null
    internal var moshSession: MoshSession? = null

    /** Mosh: Short-circuit `onClosed` when closing the SSH channel on a successful path (to avoid misinterpreting it as an unexpected disconnection). */
    internal var swallowClosed = false

    /** mosh has taken over the display: discard late output from the SSH bootstrap channel.
     *
     * The PAM MOTD script for cloud hosts (landscape/ESM detection) may take 1–3s; its output arrives in mosh only after the UDP confirmation
     * at this point the SSH-launched shell has already finished its task, but the reader coroutine will still
     * Late bytes are written via `enqueueOutput` into the UI buffer shared with the `mosh` session, overwriting the `herdr` TUI.
     * Below, with no subsequent MOSH frames overlaying it (HERDR is idle and does not redraw), this manifests as “HERDR only draws the top block,”
     * leftover Ubuntu upgrade copy below.” After the flag is set, both the enqueue and consume sides are gated simultaneously. */
    @Volatile
    internal var moshDisplayTakeover = false

    internal val scope = CoroutineScope(ioDispatcher() + SupervisorJob())

    /** Flag set after `destroy()`: the `reader` coroutine stops enqueuing to the output queue (the queue is closed; attempting to enqueue will throw). */
    @Volatile
    private var destroyed = false

    /**
     * SSH byte output queue. The reader coroutine only delivers bytes, while the main thread consumes them one at a time and feeds them to the emulator—
     * As a result, all read and write operations on this buffer (rendering, resizing, selection, and emulator writes) are serialized on the main thread,
     * Eliminate the race condition between reader writes and Canvas drawing (previously, resizing caused an interruption while rebuilding the row array)
     * `reader` may write out of bounds; `mosh` paths are synced via a copy on the main thread, so this issue does not apply).
     */
    private val outputQueue = Channel<ByteArray>(capacity = OUTPUT_QUEUE_CAPACITY)
    internal var lastCols = 80
    internal var lastRows = 24

    /** Session unique identifier (to distinguish between multiple sessions on the same host; used for Compose key() reassembly). */
    val sessionId: String = "${host.id}:${Random.nextLong()}"
    internal var reconnectAttempts = 0

    /**
     * Connection generation: increments on each connect or active disconnect. An old connection blocked inside the SSH engine
     * must re-check the generation after it returns; stale results must not overwrite the new session or write back the old state.
     */
    @Volatile
    internal var connectionGeneration = 0

    private var keepAliveActive = false

    /** Delayed automatic-reconnect tasks: cancel them in `close()` so the delayed coroutine does not re-establish the connection after close. */
    internal var reconnectJob: Job? = null

    /** Lossless health check tasks: Shared between foreground resume and network changes to avoid concurrency in detection on the same connection. */
    internal var healthCheckJob: Job? = null

    /** Mosh theme injection: A non-empty value indicates that this session is enabled (see SessionConnector.prepareThemeSync). */
    internal var moshThemePayload: ByteArray? = null
    internal var moshThemeInjected = false

    /** Monotonic clock: Use a monotonic millisecond clock for network immunity and debouncing to avoid interference from users changing the wall clock time or NTP corrections. */
    private val mono = TimeSource.Monotonic.markNow()

    internal fun nowMs(): Long = mono.elapsedNow().inWholeMilliseconds

    /**
     * The time of the most recent terminal output (monotonic clock; updated by a consumption loop).
     * For herdr injection: output going quiet = MOTD finished printing; safe to clear the screen and launch herdr.
     */
    @Volatile
    internal var lastOutputAtMs = 0L

    /** Network event immunity period after a successful connection (monotonic milliseconds): No active reconnect is triggered during this period (to prevent disconnections immediately after connecting). */
    internal var networkImmuneUntilMs = 0L

    /** Last active reconnect time (monotonic milliseconds): 15-second debounce to prevent continuous reconnections caused by network jitter. */
    internal var lastNetworkReconnectAtMs = 0L

    private val connector = SessionConnector(this, strings)

    /**
     * herdr agent monitor (enabled during Herdr sessions): Polls `herdr api snapshot`,
     * blocked: Send an AGENT_TASK notification after two rounds of confirmation (filtered by the NotificationCenter foreground).
     *
     * Lifecycle: Connection ready (finishConnected / installHerdr success) → start;
     * Session close ([close]/[destroy]) → stop. runCommand dynamically reads each time it is executed
     * [session]: There is no need to rebuild the monitor after reconnecting with a new connection.
     * Currently no agent-list UI (onEvents/onAgents callbacks are empty): the polling exists only for background “blocked” notifications
     * Notification: When developing the agent panel in the future, these two callbacks will serve as data sources.
     */
    private val herdrMonitor =
        HerdrMonitor(
            hostName = host.name,
            hostId = host.id,
            runCommand = { cmd -> session?.runCommand(cmd, 10_000) },
            scope = scope,
            onEvents = {},
            onAgents = {},
        )

    /** Launch agent monitoring once the connection is ready (idempotent; takes effect only for Herdr sessions when CONNECTED). */
    internal fun startHerdrMonitor() {
        if (!launchHerdr || status != ConnStatus.CONNECTED) return
        herdrMonitor.start()
    }

    /** Stop monitoring when the session closes (idempotent; use `close` to handle cleanup). */
    internal fun stopHerdrMonitor() {
        herdrMonitor.stop()
    }

    init {
        // Default terminal foreground/background colors: SSH responses (OSC 10/11) and Mosh theme injection both have a dependency on them.
        // The settings must be configured according to the current theme when creating; cannot wait for the TerminalScreen composition—automatic background reconnection,
        // Paths such as session resumption should not go through the UI; otherwise, the dark theme default value will be sent to the remote server, indicating that "the phone is using a dark theme,"
        // Causes the pane to render as a black/dark overlay on the white theme.
        val terminalTheme =
            TerminalThemes.ALL
                .getOrElse(repository.loadSettings().terminalThemeIndex) { TerminalThemes.ALL[0] }
        buffer.defaultFgRgb = argbToRgb(terminalTheme.foreground)
        buffer.defaultBgRgb = argbToRgb(terminalTheme.background)
        buffer.defaultCursorRgb = argbToRgb(terminalTheme.cursor)

        emulator.onTitleChange = { t -> title = t }
        // OSC responses (10/11/4 color queries, DSR/DA, etc.) are routed the same way as keyboard input (current transmission)
        emulator.onResponse = { bytes -> sendBytes(bytes) }
        emulator.onClipboardWrite = { text ->
            // OSC 52: Remote clipboard writing is controlled by a setting switch (remote clipboard can be silently overwritten; enabled by default)
            if (repository.loadSettings().osc52Clipboard) onRemoteClipboard?.invoke(text)
        }
        // Output consumption loop: Only manipulate the buffer on the main thread. Batch-process output arriving in the same frame and then redraw it all at once;
        // During continuous flooding, `receiveCatching` does not suspend; you must explicitly `yield` to give Compose a chance to draw.
        scope.launch(Dispatchers.Main) {
            while (true) {
                val first = outputQueue.receiveCatching().getOrNull() ?: break
                // mosh has taken over the display: discard late bytes from the bootstrap channel (including those already queued),
                // to prevent them from lingering permanently on top of the mosh state
                if (moshDisplayTakeover) {
                    frame++
                    yield()
                    continue
                }
                // Single-batch byte parsing exceptions (such as emulator residual index bugs) must not terminate the consumption loop—
                // Otherwise, the terminal will freeze silently without any diagnostics output. Log the error and discard this batch to continue running: skip a section of output
                // at worst a corrupted display, far better than the session becoming permanently unresponsive
                try {
                    emulator.write(first)
                    lastOutputAtMs = nowMs()
                    val mark = TimeSource.Monotonic.markNow()
                    while (mark.elapsedNow().inWholeMilliseconds < OUTPUT_BATCH_BUDGET_MS) {
                        val next = outputQueue.tryReceive().getOrNull() ?: break
                        emulator.write(next)
                        lastOutputAtMs = nowMs()
                    }
                } catch (t: Throwable) {
                    TermLog.e("term") { "emulator.write 异常，丢弃该批次续跑: ${t.stackTraceToString()}" }
                }
                frame++
                yield()
            }
        }
    }

    fun connect(
        columns: Int,
        rows: Int,
    ) = connector.connect(columns, rows)

    /** Reconnect using the most recent window size (preserving the screen buffer); used to resume the session after returning to the foreground from the background. */
    fun reconnect() = connector.reconnect()

    /** herdr guided installation: If not installed on the remote server, run the official installation script over an authenticated connection and maintain the connection. */
    fun installHerdr() = connector.installHerdr()

    /** Mosh mode: If not installed on the remote host, install it using the system package manager and continue the Mosh connection.
     *  @param password sudo password (passed in via a prompt card when the remote user is not root and sudo requires a password);
     *  (Used only for this installation; no storage in Compose state and no logging.) */
    fun installMosh(password: String? = null) = connector.installMosh(password)

    /** “Fallback SSH” on the Mosh onboarding card: Abort installation and continue using the current SSH connection. */
    fun degradeMoshToSsh() = connector.degradeMoshToSsh()

    /** Hide the error banner (clear [errorMessage]). */
    fun dismissError() {
        errorMessage = null
    }

    /** Whether automatic reconnection is allowed (determined by the settings at the time the session is opened). */
    val autoReconnectEnabled: Boolean get() = autoReconnect

    internal fun callbacks(
        trace: TermTrace.Span? = null,
        generation: Int = connectionGeneration,
    ) = object : SshCallbacks {
        override fun onTraceStep(step: String) {
            trace?.step(step)
        }

        override suspend fun onOutput(data: ByteArray) {
            if (generation == connectionGeneration) enqueueOutput(data)
        }

        override suspend fun onStderr(data: ByteArray) {
            if (generation == connectionGeneration) enqueueOutput(data)
        }

        override fun onExitStatus(status: Int) {
            if (generation == connectionGeneration) exitStatus = status
        }

        override fun onClosed(reason: String?) {
            if (generation != connectionGeneration) {
                TermLog.d("ssh") { "ignore stale close ${host.name} generation=$generation current=$connectionGeneration" }
                return
            }
            if (swallowClosed) return // Mosh success path: proactively closing the bootstrap channel
            if (status == ConnStatus.CLOSED) return // User proactively disconnected
            connector.onUnexpectedClose(reason)
        }

        override suspend fun onPrompt(prompt: AuthPrompt): List<String>? {
            if (generation != connectionGeneration) return null
            val req = AuthPromptRequest(prompt)
            authPrompt = req
            // Handle timeouts as cancellations: Dialog windows are destroyed along with the screen; do not allow connection coroutines to hang when there is no response for an extended period.
            val r = awaitAuthPromptAnswer(req.deferred)
            if (authPrompt === req && r == null) authPrompt = null
            return if (generation == connectionGeneration) r else null
        }

        override fun verifyHostKey(hostKey: HostKeyInfo): Boolean {
            if (generation != connectionGeneration) return false
            // Prioritize reading the most recently saved fingerprint in the repository: After a successful connection, `touchConnected` only writes to the repository,
            // The Host in memory (AppRoot hosts state/this controller) does not refresh; it reads directly from the host's
            // After the initial connection within the same process, every subsequent reconnect returns null and repeatedly displays the trust prompt.
            val known =
                repository.getHost(host.id)?.knownHostFingerprint
                    ?: host.knownHostFingerprint
            if (known != null) {
                if (known == hostKey.fingerprintSha256) {
                    TermLog.d("ssh") { "hostkey ok ${host.name} ${hostKey.algorithm}" }
                    return true
                }
                // Fingerprint has changed: no longer results in a hard failure (otherwise, connections would be permanently lost after changing the address or server keys, and there would be no way to reset it),
                // Change it to a dialog that allows users to compare the old and new fingerprints before making a decision.
                TermLog.w("ssh") { "hostkey CHANGED ${host.name}: $known -> ${hostKey.fingerprintSha256}" }
                val req = HostKeyRequest(hostKey, changed = true, previousFingerprint = known)
                hostKeyPrompt = req
                val ok = awaitHostKeyAnswer(req)
                if (generation != connectionGeneration) return false
                // Accept and adopt the new fingerprint: even in case of failure during subsequent authentication, do not display the dialog again (TOFU trusts the host key).
                if (ok) repository.recordHostKey(host.id, hostKey.fingerprintSha256)
                return ok
            }
            // First-time connection: If the user has disabled “First-time Connection Confirmation,” the connection is trusted immediately (the fingerprint will still be visible in settings).
            if (!repository.loadSettings().verifyHostKeyOnFirstUse) {
                TermLog.d("ssh") { "hostkey trust-on-first-use skipped (setting off) ${host.name}" }
                return true
            }
            // TOFU: First connection requires the user to confirm it
            TermLog.d("ssh") { "hostkey TOFU prompt ${host.name} ${hostKey.fingerprintSha256}" }
            val req = HostKeyRequest(hostKey)
            hostKeyPrompt = req
            val ok = awaitHostKeyAnswer(req)
            if (generation != connectionGeneration) return false
            // Tapping “Trust” records the fingerprint, decoupling it from the success or failure of authentication: otherwise, if there is a failure in authentication (e.g., incorrect password),
            // The authorization window pops up repeatedly with every connection.
            if (ok) repository.recordHostKey(host.id, hostKey.fingerprintSha256)
            return ok
        }
    }

    /** Wait for host key confirmation and clear the dialog as a fallback (if a normal response has already been cleared by `respondToHostKey`, treat a timeout as a rejection). */
    private fun awaitHostKeyAnswer(req: HostKeyRequest): Boolean {
        val r = awaitHostKeyPromptAnswer(req.deferred)
        if (hostKeyPrompt === req) hostKeyPrompt = null
        return r
    }

    /** reader coroutine output: If the queue is full, it suspends to apply backpressure (TCP window convergence) without losing any bytes.
     * Discard after mosh takes over (the bootstrap channel's work is done; late bytes can only pollute the mosh session view). */
    internal suspend fun enqueueOutput(data: ByteArray) {
        if (destroyed || moshDisplayTakeover) return
        try {
            outputQueue.send(data)
        } catch (_: ClosedSendChannelException) {
            // destroy() closes the queue; attempting to send data afterward throws a ClosedSendChannelException: the session is dead; data is discarded.
        }
    }

    fun respondToPrompt(answers: List<String>?) {
        authPrompt?.let { req ->
            authPrompt = null
            req.deferred.complete(answers)
        }
    }

    fun respondToHostKey(accept: Boolean) {
        hostKeyPrompt?.let { req ->
            hostKeyPrompt = null
            req.deferred.complete(accept)
        }
    }

    fun sendText(text: String) {
        moshSession?.sendData(text.encodeToByteArray()) ?: session?.sendData(text.encodeToByteArray())
    }

    fun sendBytes(bytes: ByteArray) {
        moshSession?.sendData(bytes) ?: session?.sendData(bytes)
    }

    /** Read DECCKM at dispatch time; it can change without recomposing the toolbar. */
    fun sendSpecialKey(key: SpecialKey) {
        sendBytes(specialKeyBytes(key, buffer.applicationCursorKeys))
    }

    fun resize(
        columns: Int,
        rows: Int,
        widthPx: Int,
        heightPx: Int,
    ) {
        if (columns <= 0 || rows <= 0) return
        lastCols = columns
        lastRows = rows
        if (columns != buffer.cols || rows != buffer.rows) {
            buffer.resize(columns, rows)
            frame++
        }
        moshSession?.resize(columns, rows) ?: session?.resize(columns, rows, widthPx, heightPx)
    }

    /** Cursor blinking: Toggles the rendering phase and triggers a repaint; steady-state styles (2/4/6) do not blink. */
    fun blinkCursor() {
        // DECSCUSR Steady-state styles do not flicker and do not change phase (steady-state styles do not read phase when drawn).
        if (buffer.cursorStyle == 2 || buffer.cursorStyle == 4 || buffer.cursorStyle == 6) return
        cursorBlinkPhase = !cursorBlinkPhase
        frame++
    }

    internal fun startKeepAlive() {
        // Android performs idempotent registration based on sessionId; after the service is stopped by the system, isActive() returns false,
        // Even if the controller still remembers `keepAliveActive`, it must re-register and restart the service.
        if (!keepAliveActive || !SessionKeepAlive.isActive()) {
            keepAliveActive = true
            SessionKeepAlive.onSessionStart(sessionId)
        }
    }

    /** After the service is rebuilt, re-register using the sessionId; on Android, this is idempotent; on other platforms, it is a null operation. */
    internal fun restoreKeepAliveRegistration() {
        if (keepAliveActive && isConnected()) SessionKeepAlive.onSessionStart(sessionId)
    }

    internal fun stopKeepAlive() {
        if (keepAliveActive) {
            keepAliveActive = false
            SessionKeepAlive.onSessionEnd(sessionId)
        }
    }

    /** Silently detect changes when the foreground resumes or the default network changes; a healthy connection will not alter the UI state. */
    internal fun verifySshConnection(cause: SessionReconnectCause) = connector.verifySshConnection(cause)

    /** When the platform explicitly cannot retain the socket (e.g. the app is suspended), actively rebuild it and show the actual resume semantics. */
    internal fun forceReconnect(cause: SessionReconnectCause) = connector.forceReconnect(cause)

    fun close() {
        // First, invalidate all pending connection establishment attempts; even if the underlying blocking calls return, they don't go back,
        // It will only release its own resources and will not revive the session.
        connectionGeneration++
        status = ConnStatus.CLOSED
        linkLostSeconds = 0
        // herdr agent monitoring stops when the session ends (and is launched again by `finishConnected` after a successful reconnect)
        stopHerdrMonitor()
        // Cancel pending automatic-reconnect tasks so a delayed task does not re-establish the connection after close.
        reconnectJob?.cancel()
        reconnectJob = null
        healthCheckJob?.cancel()
        healthCheckJob = null
        reconnectCause = null
        // Complete the suspended authentication/host key dialog and release the SSH thread blocked on `await`.
        authPrompt?.let {
            authPrompt = null
            it.deferred.complete(null)
        }
        hostKeyPrompt?.let {
            hostKeyPrompt = null
            it.deferred.complete(false)
        }
        stopKeepAlive()
        val closingMosh = moshSession
        moshSession = null
        try {
            closingMosh?.close()
        } catch (e: Exception) {
            TermLog.w("mosh") { "close failed ${host.name}: ${e.message}" }
        }
        // Prevent installation status from persisting across sessions (re-detect after reconnection);
        // moshDegradedToSsh / herdrBin intentionally retained: Fallback decisions take effect across reconnections
        moshNeedsInstall = false
        moshInstalling = false
        moshInstallLog = ""
        moshNeedsSudoPassword = false
        val closingSession = session
        session = null
        try {
            closingSession?.close()
        } catch (e: Exception) {
            TermLog.w("ssh") { "close failed ${host.name}: ${e.message}" }
        }
    }

    /**
     * Destroy the controller (called when removed from the session list): close the connection, close the output queue, and cancel the coroutine
     * Scope: reclaims resources held by delayed tasks (stable-state reset / theme injection / reconnect backoff).
     * Difference from [close]: close preserves re-entry capability (the scope remains active, and reconnect() is possible),
     * After `destroy`, the controller can no longer be used.
     */
    fun destroy() {
        close()
        destroyed = true
        outputQueue.close()
        scope.cancel()
    }

    fun isConnected(): Boolean = status == ConnStatus.CONNECTED || status == ConnStatus.AUTH

    /**
     * Called by the platform layer on network switches (Wi-Fi ↔ mobile data, etc.): see [SessionConnector.onNetworkChanged] for semantics.
     */
    fun onNetworkChanged(kind: NetworkChangeKind) = connector.onNetworkChanged(kind)
}
