package dev.macodes.ui

import dev.macodes.data.ConnectionMode
import dev.macodes.herdr.HerdrProbe
import dev.macodes.mosh.MoshExitReason
import dev.macodes.notify.NotificationCenter
import dev.macodes.notify.NotificationEvent
import dev.macodes.ssh.MoshSession
import dev.macodes.ssh.SYSTEM_PROBE_COMMAND
import dev.macodes.ssh.SshConnection
import dev.macodes.ssh.SshSession
import dev.macodes.ssh.createKmpMoshSession
import dev.macodes.ssh.detectSystemFromOutput
import dev.macodes.ssh.parseMoshConnect
import dev.macodes.ssh.parseMoshServerPid
import dev.macodes.util.NetworkChangeKind
import dev.macodes.util.TermLog
import dev.macodes.util.TermTrace
import dev.macodes.util.ioDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Connection Orchestration Layer: Two connection modes (SSH / Mosh), reconnect backoff, network events, herdr workspace
 * Launch (detection/boot install/injection), mosh theme injection, system detection.
 *
 * `herdr` refers to the launch type of this terminal session, not a host attribute or transport protocol:
 * - Mosh: Run `mosh-server new -- herdr`; the Mosh session will launch the herdr TUI directly.
 * - SSH / mosh fallback: After a connection, perform command injection into the shell (exit back to the shell)
 * - Not installed on remote: Display an installation card (official script, real-time logs)
 *
 * Status ownership (Compose observables: status / frame / buffer …) remains
 * [TerminalController]—This class passes read/write access via `internal` within the same package; UI observables remain unchanged.
 */
internal class SessionConnector(
    private val c: TerminalController,
    private val strings: () -> AppStrings,
) {
    companion object {
        /** Maximum number of automatic reconnection attempts after an unexpected SSH disconnection (exponential backoff; see [RECONNECT_BASE_DELAY_MS]). */
        private const val RECONNECT_SSH_MAX = 3

        /** Maximum number of automatic reconnections after an exception during a successful connection to mosh (UDP environments are more fragile and have a lower limit). */
        private const val RECONNECT_MOSH_MAX = 2

        /** UDP first-packet confirmation window: Boot success but first packet not received = mosh connection failure → fallback to SSH. */
        private const val MOSH_UDP_CONFIRM_MS = 5_000L

        /** If UDP is not confirmed, clean up the detached mosh-server via the still-active SSH control channel. */
        private const val MOSH_CLEANUP_TIMEOUT_MS = 3_000L

        /** Set the duration for which mosh fallback tooltips automatically disappear (persistent tooltips can obscure the top of the terminal). */
        private const val MOSH_DEGRADE_NOTICE_MS = 6_000L

        /** Automatic reconnection with basic backoff: The nth reconnect attempt has a latency of 2n seconds. */
        private const val RECONNECT_BASE_DELAY_MS = 2_000L

        /** Network event grace period after a successful connection: Do not make changes immediately after connecting to avoid a “connect-and-disconnect” loop. */
        private const val NETWORK_IMMUNE_MS = 30_000L

        /** Debounce window for automatic reconnection when toggling networks. */
        private const val NETWORK_DEBOUNCE_MS = 15_000L

        /** After a front-end/network callback, first ensure the system’s default network connection is stable, then perform an SSH end-to-end heartbeat. */
        private const val HEALTH_CHECK_SETTLE_MS = 500L

        /** The maximum time to wait for a server response during an SSH health check. */
        private const val HEALTH_CHECK_TIMEOUT_MS = 2_500L

        /** The observation period during which the reconnect count is reset to zero after the connection stabilizes (mosh’s “connect and exit” anti-looping mechanism). */
        private const val MOSH_STABLE_RESET_MS = 30_000L

        /** mosh theme injection delay: too early → shell readline echoes it as garbled input; too late → herdr shows a gray haze. */
        private const val MOSH_THEME_INJECT_DELAY_MS = 1_200L

        /**
         * herdr injection timing: wait until terminal output is silent (PAM MOTD during login—Ubuntu
         * (landscape/ESM detection may take 1–3 seconds and produce several dozen rows of output—already completed) before performing the injection.
         * Silence Threshold: If there is no new output for this long, the MOTD is considered to have ended.
         */
        private const val HERDR_OUTPUT_QUIET_MS = 250L

        /** Silently detect polling intervals. */
        private const val HERDR_QUIET_POLL_MS = 50L

        /** Maximum silent wait time: In cases where exceptions continue to generate output (such as a login script logging while running), this value forces an injection at this point. */
        private const val HERDR_INJECT_MAX_WAIT_MS = 3_000L

        /** herdr official website install command (curl piped into sh; installed by default to ~/.local/bin). */
        private const val HERDR_INSTALL_CMD = "curl -fsSL https://herdr.dev/install.sh | sh"

        /** Installation timeout: Allow sufficient time for downloading binaries and verification (the default `exec 15s` is not enough). */
        private const val HERDR_INSTALL_TIMEOUT_MS = 180_000L

        /** mosh installation timeout: Allow sufficient time for the package manager to download the package and its dependencies. */
        private const val MOSH_INSTALL_TIMEOUT_MS = 180_000L
    }

    // ------------------------------------------------------------------
    // Entry
    // ------------------------------------------------------------------

    fun connect(
        columns: Int,
        rows: Int,
    ) {
        // Allow (re)connection in IDLE / disconnection / failure states; retain buffering
        if (c.status != ConnStatus.IDLE && c.status != ConnStatus.CLOSED && c.status != ConnStatus.ERROR) return
        c.lastCols = columns
        c.lastRows = rows
        c.reconnectAttempts = 0
        c.reconnectCount = 0
        c.reconnectCause = null
        c.errorMessage = null
        // Reset on reconnect: SSH output from fallback reconnect (moshDegradedToSsh) should be shown in a normal manner
        c.moshDisplayTakeover = false
        doConnect()
    }

    /** Reconnect using the most recent window size (preserving the screen buffer); used to resume the session after returning to the foreground from the background. */
    fun reconnect() {
        if (c.status != ConnStatus.IDLE && c.status != ConnStatus.CLOSED && c.status != ConnStatus.ERROR) return
        connect(c.lastCols, c.lastRows)
    }

    private fun newConnection(): SshConnection {
        val settings = c.repository.loadSettings()
        return SshConnection(
            host = c.host.hostname,
            port = c.host.port,
            username = c.host.username,
            password = c.password,
            privateKeyPem = c.privateKeyPem,
            keepAliveSeconds = settings.keepaliveSeconds,
            terminalType = settings.terminalType,
            // Network connections are often already lost when attempting to reconnect: TCP timeout reduced from 15s to 5s—
            // Otherwise 3 reconnect attempts × 15s ≈ 1 minute of “Connecting…” (perceived as a hang)
            connectTimeoutMillis = if (c.reconnectAttempts > 0) 5_000 else 15_000,
        )
    }

    private fun isCurrentGeneration(generation: Int): Boolean = c.connectionGeneration == generation && c.status != ConnStatus.CLOSED

    private fun isCurrentSession(
        generation: Int,
        session: SshSession,
    ): Boolean = isCurrentGeneration(generation) && c.session === session

    /** Release sessions that have been disconnected or replaced by new ones, without affecting the session references in the current generation. */
    private fun closeStaleSession(session: SshSession) {
        if (c.session === session) c.session = null
        try {
            session.close()
        } catch (e: Exception) {
            TermLog.w("ssh") { "stale session close failed ${c.host.name}: ${e.message}" }
        }
    }

    private fun doConnect() {
        val generation = ++c.connectionGeneration
        val t0 = c.nowMs()
        TermLog.i("ssh") {
            "connect start ${c.host.name} ${c.host.hostname}:${c.host.port} mode=${c.host.connectionMode} attempt=${c.reconnectAttempts} timeout=${newConnection().connectTimeoutMillis}ms"
        }
        // Connection span: the engine fills in phase timings via onTraceStep (tcp+kex/auth/shell)
        val trace =
            TermTrace.begin(
                "ssh.connect",
                "ssh",
                "host" to c.host.name,
                "attempt" to c.reconnectAttempts.toString(),
                "mode" to c.host.connectionMode.name,
            )
        c.status = ConnStatus.CONNECTING
        c.scope.launch {
            try {
                if (!isCurrentGeneration(generation)) return@launch
                when (c.host.connectionMode) {
                    ConnectionMode.MOSH -> {
                        doConnectMosh(generation = generation)
                        return@launch
                    }
                    ConnectionMode.SSH -> {}
                }
                val s = c.sessionFactory(newConnection(), c.callbacks(trace, generation))
                if (!isCurrentGeneration(generation)) {
                    closeStaleSession(s)
                    return@launch
                }
                c.session = s
                // A disconnection or reconnection may also occur between the sessionFactory and registration.
                if (!isCurrentSession(generation, s)) {
                    closeStaleSession(s)
                    return@launch
                }
                val info = s.connectAndStart(c.lastCols, c.lastRows)
                // The connection may have been disconnected or a new generation may have started during the connection: the old generation cannot be set to CONNECTED.
                if (!isCurrentSession(generation, s)) {
                    closeStaleSession(s)
                    return@launch
                }
                // TOFU: Record host fingerprints
                info.hostKey?.let { c.repository.touchConnected(c.host.id, it.fingerprintSha256) }
                trace.step("connected")
                trace.end()
                TermLog.i("ssh") { "connected ${c.host.name} kex=${info.kexAlgorithm} in ${c.nowMs() - t0}ms" }
                // Herdr Session: Detect remote Herdr; missing → display installation card;
                // A user who taps the standalone entry point explicitly launches this agent monitoring session.
                if (c.launchHerdr) {
                    val probed = HerdrProbe.probe { cmd -> s.runCommand(cmd, 5_000) }
                    if (probed == null) {
                        TermLog.w("herdr") { "herdr not found ${c.host.name}: 引导安装" }
                        c.herdrNeedsInstall = true
                        finishConnected(s, generation, sendStartup = false)
                        return@launch
                    }
                    c.herdrBin = probed.bin
                    TermLog.i("herdr") { "herdr probe ok ${c.host.name} bin=${probed.bin}" }
                }
                finishConnected(s, generation)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // Canceling a coroutine is not a connection failure: does not set ERROR, does not stop the keep-alive
            } catch (e: Exception) {
                if (isCurrentGeneration(generation)) {
                    val elapsed = c.nowMs() - t0
                    trace.fail(e.message)
                    if (elapsed > 10_000) {
                        TermLog.w(
                            "ssh",
                        ) { "connect SLOW/FAIL after ${elapsed}ms ${c.host.name}: ${e.message}（TCP 超时特征：网络黑洞/防火墙丢包）" }
                    } else {
                        TermLog.e("ssh") { "connect failed after ${elapsed}ms ${c.host.name}: ${e.message}" }
                    }
                    c.errorMessage = e.message
                    // Automatic reconnection failure: The session has expired; the keep-alive must be stopped, otherwise the foreground service and wakelock will idle.
                    // (In the event of a failure in the initial connection, keepAliveActive=false; stopKeepAlive has a guard, so it provides security.)
                    c.stopKeepAlive()
                    c.session = null

                    if (c.reconnectAttempts > 0 && c.autoReconnect && c.reconnectAttempts < RECONNECT_SSH_MAX) {
                        // If there is a failure in `doConnect()` on its own, `onClosed` will not be called; you must continue processing here.
                        // Retry the remaining attempts; otherwise, the "up to 3 attempts" promised in the comment will actually result in only 1 attempt.
                        c.reconnectAttempts++
                        c.reconnectCount = c.reconnectAttempts
                        c.status = ConnStatus.CONNECTING
                        val delayMs = RECONNECT_BASE_DELAY_MS * c.reconnectAttempts
                        TermLog.w("ssh") {
                            "reconnect retry ${c.reconnectAttempts}/$RECONNECT_SSH_MAX ${c.host.name} in ${delayMs}ms"
                        }
                        c.scope
                            .launch {
                                delay(delayMs)
                                if (c.status != ConnStatus.CLOSED) doConnect()
                            }.also { c.reconnectJob = it }
                    } else if (c.reconnectAttempts > 0) {
                        // All automatic attempts to reconnect failed: close the session and send a single notification only when the connection is finally exhausted.
                        c.status = ConnStatus.CLOSED
                        TermLog.e("ssh") { "reconnect exhausted ${c.host.name} -> CLOSED" }
                        NotificationCenter.post(
                            NotificationEvent.RECONNECT_FAILED,
                            "Macodes",
                            strings().notificationReconnectFailed(c.host.name, e.message ?: strings().terminalFailed),
                            hostId = c.host.id,
                        )
                    } else {
                        // If there is a failure in the initial connection, display an error in the UI; do not enter the automatic reconnect loop.
                        c.status = ConnStatus.ERROR
                    }
                }
            }
        }
    }

    /**
     * Mosh Mode: SSH boots the mosh-server; after the first UDP packet is confirmed, SSH is closed (the boot tool’s task is complete).
     * Herdr Session: Pre-onboarding check for Herdr (missing → install card),
     * Append ` -- herdr` to the command (the mosh session runs the herdr TUI directly).
     * Fallback semantics (two types): Guide failure = mosh-server not installed on the remote → Install card or fallback
     * (SSH shell; injection of "herdr" when launching Herdr); boot success but UDP first packet had a timeout =
     * mosh connection failure → Fallback to SSH (the SSH tunnel is still active, so use it directly as the display channel—
     * When UDP is unavailable, users should at least get a working shell, rather than being left staring at an error message;
     * tooltip: Reason for fallback + solution using a fixed UDP port).
     *
     * @param existingSession Reuse an existing authenticated connection (for subsequent connections after installation; null = self-hosted)
     */
    private suspend fun doConnectMosh(
        existingSession: SshSession? = null,
        generation: Int = c.connectionGeneration,
    ) {
        val t0 = c.nowMs()
        try {
            if (!isCurrentGeneration(generation)) return
            // 1. SSH connection + shell (initialization channel; becomes a show channel upon fallback; closes when Mosh achieves success)
            val s = existingSession ?: c.sessionFactory(newConnection(), c.callbacks(generation = generation))
            if (existingSession == null) {
                if (!isCurrentGeneration(generation)) {
                    closeStaleSession(s)
                    return
                }
                c.session = s
                if (!isCurrentSession(generation, s)) {
                    closeStaleSession(s)
                    return
                }
                val info = s.connectAndStart(c.lastCols, c.lastRows)
                if (!isCurrentSession(generation, s)) {
                    closeStaleSession(s)
                    return
                }
                info.hostKey?.let { c.repository.touchConnected(c.host.id, it.fingerprintSha256) }
            } else if (!isCurrentSession(generation, s)) {
                return
            }

            // UDP session failed; session fallback to SSH (moshDegradedToSsh): No longer retry mosh startup
            // (UDP disconnections will not be automatically resolved by reconnection; retries will only result in an additional initialization and a 5-second UDP confirmation wait each time),
            // Go directly through the fallback display channel: SSH shell (use injection to add "herdr" when launching Herdr)
            if (c.moshDegradedToSsh) {
                TermLog.i("mosh") { "mosh degraded earlier ${c.host.name}——重连直走 ssh（跳过 mosh 引导）" }
                if (!ensureHerdrProbed(s)) return // Missing → Install card (finishConnected set to CONNECTED)
                finishConnected(s, generation)
                return
            }

            // herdr Workspace: Pre-boot detection (missing → install card, SSH display channel retained)
            if (!ensureHerdrProbed(s)) return
            if (!isCurrentSession(generation, s)) {
                closeStaleSession(s)
                return
            }
            val bootstrapExtra =
                if (c.launchHerdr) {
                    " -- ${shSingleQuote(c.herdrBin ?: "herdr")}"
                } else {
                    ""
                }

            // 2. Start the mosh-server using the connection (runCommand reuses the authenticated connection; the detection output follows
            //    After MOSH CONNECT, this does not affect parseMoshConnect (the system automatically recognizes it).
            val moshColors = if (c.repository.loadSettings().terminalType == "xterm-256color") "256" else "8"
            val baseBootstrap =
                if (c.host.moshUdpPort in 1024..65535) {
                    "mosh-server new -s -c $moshColors -p ${c.host.moshUdpPort} -l LANG=en_US.UTF-8$bootstrapExtra"
                } else {
                    "mosh-server new -s -c $moshColors -l LANG=en_US.UTF-8$bootstrapExtra"
                }
            val bootstrap = "$baseBootstrap 2>&1; $SYSTEM_PROBE_COMMAND"
            TermLog.i("mosh") { "bootstrap ${c.host.name}: $baseBootstrap" }
            val raw = s.runCommand(bootstrap, 5_000)
            if (!isCurrentSession(generation, s)) {
                closeStaleSession(s)
                return
            }
            val parsed = raw?.let { parseMoshConnect(it) }
            detectSystemFromOutput(raw ?: "")?.takeIf { it.isNotBlank() }?.let { detected ->
                if (c.host.system.isBlank()) {
                    // Update only the `system` field (based on the latest repository value): `c.host` is a snapshot taken when the application was created,
                    // The entire upsert will overwrite the recently recorded TOFU fingerprint (causing the dialog to reappear when a new host reconnects).
                    c.repository.patchHost(c.host.id) { it.copy(system = detected) }
                    c.onSystemDetected?.invoke(c.host.copy(system = detected))
                }
            }
            if (parsed == null) {
                // 3a. Failure in initialization = mosh-server not installed on the remote server (or the fixed port is in use) → one of the fallback paths
                val rawTrimmed = raw?.trim()?.take(120)
                // After entering `2>&1`, "command not found" is output to stdout: Accurately identify "mosh-server not installed"
                val missing =
                    raw?.contains("not found") == true ||
                        raw?.contains("No such file") == true ||
                        rawTrimmed.isNullOrEmpty()
                val reason =
                    when {
                        c.host.moshUdpPort in 1024..65535 && raw?.contains("Address already in use") == true ->
                            strings().moshPortBusy(c.host.moshUdpPort)
                        missing -> strings().moshServerMissing
                        else -> strings().moshBootstrapFailed(rawTrimmed)
                    }
                // All setup failures should be directed to the installation card (the card can be set to “Install” or “Fallback SSH”):
                // If a package is missing, it must be installed; if it is installed but there is a failure during initialization (due to locale, dependencies, etc.), provide a fix option,
                // No longer silently performs fallback; now displays a single failure notification (User feedback: "I haven't installed mosh-server, but..."
                // (Only "Boot failure" is displayed; no options to install are shown.)
                TermLog.w("mosh") { "mosh bootstrap failed ${c.host.name}: $reason——引导安装（可降级）" }
                // First set the ready state, then set CONNECTED (eliminate the intermediate status frame: after `awaitStatus`)
                // (Tests that immediately make an assertion about `moshNeedsInstall` will not see the intermediate frames.)
                c.moshNeedsInstall = true
                c.moshInstallReason = if (missing) null else reason
                finishConnected(s, generation, sendStartup = false)
                return
            }
            val (moshPort, moshKey) = parsed
            val moshServerPid = parseMoshServerPid(raw.orEmpty())
            TermLog.i("mosh") { "mosh-server up port=$moshPort ${c.host.hostname}" }
            // Setup success: The "pending installation" status was immediately cleared (residual entries may have come from a previous session).
            c.moshNeedsInstall = false
            c.moshInstallReason = null

            // 3b. Initialization success: Create a Mosh client and wait to confirm the first UDP packet (to determine if the connection was successful).
            withContext(Dispatchers.Main) { prepareThemeSync() }
            val peerReady = CompletableDeferred<Unit>()
            val client =
                createKmpMoshSession(
                    ip = c.host.hostname,
                    port = moshPort,
                    key = moshKey,
                    columns = c.lastCols,
                    rows = c.lastRows,
                    scope = c.scope,
                    uiBuffer = c.buffer,
                    onTitle = { t -> if (isCurrentGeneration(generation)) c.title = t },
                    onClipboard = { text ->
                        if (isCurrentGeneration(generation) && c.repository.loadSettings().osc52Clipboard) {
                            c.onRemoteClipboard?.invoke(text)
                        }
                    },
                    onExit = { reason -> handleMoshExit(reason, generation) },
                    onFrame = { if (isCurrentGeneration(generation)) c.frame++ },
                    onPeerConnected = {
                        peerReady.complete(Unit)
                        c.moshSession?.let { onMoshConnected(it, generation) }
                    },
                    onLinkStatus = { secs ->
                        if (isCurrentGeneration(generation)) {
                            if (secs >= LINK_LOST_THRESHOLD_SECONDS && c.linkLostSeconds < LINK_LOST_THRESHOLD_SECONDS) {
                                TermLog.w("mosh") { "link lost ${c.host.name} ${secs}s" }
                            }
                            c.linkLostSeconds = secs
                        }
                    },
                )
            if (!isCurrentSession(generation, s)) {
                client.close()
                closeStaleSession(s)
                return
            }
            c.moshSession = client
            TermLog.i("mosh") { "mosh client started ${c.host.name} cols=${c.lastCols}x${c.lastRows}" }
            if (!isCurrentSession(generation, s)) {
                c.moshSession = null
                client.close()
                return
            }
            val udpOk = withTimeoutOrNull(MOSH_UDP_CONFIRM_MS) { peerReady.await() }
            if (!isCurrentSession(generation, s)) {
                if (c.moshSession === client) c.moshSession = null
                client.close()
                closeStaleSession(s)
                return
            }
            if (udpOk == null && c.status != ConnStatus.CONNECTED) {
                // 3c. Initialization success but UDP first packet had a timeout = mosh connection failure: fallback to SSH
                // (The SSH guide channel is still active; treat it directly as a display channel; the UDP connection failure is a genuine issue, but
                // (Users should at least be able to obtain a usable shell; a tooltip should indicate the reason and provide a solution for a fixed port.)
                TermLog.w("mosh") { "mosh UDP unconfirmed ${c.host.name} ${MOSH_UDP_CONFIRM_MS}ms——降级 SSH" }
                c.moshSession?.close()
                c.moshSession = null
                cleanupUnconfirmedMoshServer(s, moshServerPid)
                // UDP connectivity issues are due to environmental barriers: Mark this session entry so that subsequent reconnections go directly through SSH,
                // Do not retry mosh (retrials will only occur in new sessions)
                c.moshDegradedToSsh = true
                finishConnected(s, generation)
                showDegradeNotice(strings().moshUdpDegraded)
                TermLog.i("mosh") { "mosh degraded-to-ssh ${c.host.name} in ${c.nowMs() - t0}ms" }
                return
            }
            // 3d. UDP confirmation success: mosh connection success—first gate the SSH prompt output, then close the connection (PAM MOTD
            //     (These bytes will be written into the UI buffer of the mosh session and permanently remain beneath the herdr TUI),
            //     close sync: Triggers onClosed; use swallowClosed to short-circuit and prevent false positives.
            c.moshDisplayTakeover = true
            c.swallowClosed = true
            c.session = null
            try {
                s.close()
            } catch (_: Exception) {
            }
            c.swallowClosed = false
            TermLog.i("mosh") { "mosh connected ${c.host.name} in ${c.nowMs() - t0}ms（SSH 引导通道已关）" }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (isCurrentGeneration(generation)) {
                TermLog.e("mosh") { "mosh connect failed ${c.host.name}: ${e.message}" }
                c.status = ConnStatus.ERROR
                c.errorMessage = e.message
                c.stopKeepAlive()
            }
        }
    }

    /**
     * Clean up the remote process launched during this boot sequence if the first UDP packet is not confirmed.
     *
     * The PID comes from mosh-server’s own detached output; pass `ps comm` again before executing `kill`.
     * The verification target remains mosh-server to prevent other processes from being accidentally affected by extreme PID reuse. SIGUSR1 is
     * mosh-server provides a signal to terminate disconnected sessions; it does not use `pkill`, which could affect other sessions.
     */
    private fun cleanupUnconfirmedMoshServer(
        session: SshSession,
        pid: Int?,
    ) {
        if (pid == null) {
            TermLog.w("mosh") { "mosh cleanup skipped ${c.host.name}: detached pid unavailable" }
            return
        }
        val command =
            "MOSH_PID=$pid; " +
                "MOSH_COMM=\$(ps -p \"\$MOSH_PID\" -o comm= 2>/dev/null); " +
                "case \"\$MOSH_COMM\" in *mosh-server) " +
                "kill -USR1 \"\$MOSH_PID\" && echo MOSH_CLEANUP_OK ;; esac"
        val cleaned =
            runCatching {
                session.runCommand(command, MOSH_CLEANUP_TIMEOUT_MS)?.contains("MOSH_CLEANUP_OK") == true
            }.getOrDefault(false)
        if (cleaned) {
            TermLog.i("mosh") { "cleaned unconfirmed mosh-server pid=$pid ${c.host.name}" }
        } else {
            TermLog.w("mosh") { "mosh cleanup not confirmed pid=$pid ${c.host.name}" }
        }
    }

    /**
     * Herdr session ensures detection ([c.herdrBin]); not installed → display installation card
     *(keep the SSH connection open and set it to CONNECTED); return false. If the toggle is off, return true immediately.
     */
    private suspend fun ensureHerdrProbed(s: SshSession): Boolean {
        if (!c.launchHerdr || c.herdrBin != null) return true
        val probed = HerdrProbe.probe { cmd -> s.runCommand(cmd, 5_000) }
        if (probed == null) {
            TermLog.w("herdr") { "herdr not found ${c.host.name}: 引导安装" }
            c.herdrNeedsInstall = true
            finishConnected(s, c.connectionGeneration, sendStartup = false)
            return false
        }
        c.herdrBin = probed.bin
        TermLog.i("herdr") { "herdr probe ok ${c.host.name} bin=${probed.bin}" }
        return true
    }

    /**
     * herdr Setup Guide: If herdr is not available remotely, run the official installation script over an authenticated connection.
     * (curl https://herdr.dev/install.sh | sh), and if success, re-detect and reconnect
     * (Mosh: re-bootstraps with ` -- herdr`; SSH / degraded: injects the herdr command).
     * Stream the script output during the install process into [TerminalController.herdrInstallLog] (bootstrap card)
     * (Real-time display), so that when the system is stuck, users can see that the logs are no longer updating.
     */
    fun installHerdr() {
        val s = c.session ?: return
        if (c.herdrInstalling) return
        c.herdrInstalling = true
        c.errorMessage = null
        c.herdrInstallLog = ""
        c.scope.launch {
            try {
                val log = StringBuilder()
                // The $HOME variable for non-interactive exec commands may be empty or incorrect (install.sh encounters failure when trying to create /.local/bin),
                // Use `pwd` to retrieve the actual home directory (sshd defaults to changing directories to `home`), and explicitly pass it to the install script.
                val home =
                    withContext(ioDispatcher()) {
                        s.runCommand("pwd")?.trim()?.takeIf { it.startsWith("/") }
                    }
                // Escape single quotes in the home directory (without breaking shell semantics when spaces or special characters are present)
                val installCmd =
                    if (home !=
                        null
                    ) {
                        "HOME=${shSingleQuote(home)} $HERDR_INSTALL_CMD"
                    } else {
                        HERDR_INSTALL_CMD
                    }
                withContext(ioDispatcher()) {
                    // Streaming exec (JVM): Read the script block by block and log the output; fall back to `runCommand` if `startExec` is not available.
                    val exec = s.startExec(installCmd, c.lastCols, c.lastRows)
                    if (exec != null) {
                        try {
                            while (true) {
                                val chunk = exec.read() ?: break
                                log.append(chunk.decodeToString().replace("\r", ""))
                                c.herdrInstallLog = log.toString()
                            }
                        } finally {
                            exec.close()
                        }
                    } else {
                        log.append(s.runCommand(installCmd, HERDR_INSTALL_TIMEOUT_MS) ?: "")
                        c.herdrInstallLog = log.toString()
                    }
                }
                // Detection: the actual path parsed from install.sh output → $home/.local/bin → HerdrProbe candidates.
                // Use --version (to check installation): Immediately after installation, the daemon is not running; the API snapshot will
                // "server_not_running" error—this false positive was exactly why it kept reporting failure even after installation was complete.
                val probed =
                    withContext(ioDispatcher()) {
                        val candidates =
                            buildList {
                                parseInstallPath(log.toString())?.let(::add)
                                home?.let { add("$it/.local/bin/herdr") }
                            }.distinct()
                        val explicit =
                            candidates.firstNotNullOfOrNull { path ->
                                val raw = s.runCommand("${shSingleQuote(path)} --version", 5_000)
                                raw
                                    ?.takeIf { HerdrProbe.isVersionOutput(it) }
                                    ?.let { HerdrProbe.Result(path) }
                            }
                        explicit ?: HerdrProbe.probe { cmd -> s.runCommand(cmd, 5_000) }
                    }
                if (probed == null) {
                    TermLog.e("herdr") { "install then probe failed ${c.host.name}: ${log.take(120)}" }
                    c.herdrInstalling = false
                    c.errorMessage =
                        strings().herdrInstallFailed(
                            log
                                .toString()
                                .trim()
                                .take(200)
                                .ifBlank { null },
                        )
                    return@launch
                }
                TermLog.i("herdr") { "herdr installed ${c.host.name} bin=${probed.bin}" }
                c.herdrBin = probed.bin
                c.herdrInstalling = false
                c.herdrNeedsInstall = false
                if (c.host.connectionMode == ConnectionMode.MOSH && !c.moshDegradedToSsh) {
                    // Mosh: Reboot with herdr parameters (the mosh session runs the herdr TUI directly)
                    doConnectMosh(existingSession = s)
                } else {
                    // SSH / Fallback Entry: Session is CONNECTED; perform direct injection of the `herdr` command
                    // (Latency + clear screen, same injection path as `finishConnected`: login shell
                    //  (Late MOTD output will push the herdr screen to the top.)
                    sendHerdrLaunch(s)
                    // Installation success → Launch agent monitoring (the normal path is launched by `finishConnected`,
                    // (However, this branch will not resume monitoring until after `installHerdr` is run.)
                    c.startHerdrMonitor()
                    c.frame++
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                c.herdrInstalling = false
                c.errorMessage = strings().herdrInstallFailed(e.message)
            }
        }
    }

    /**
     * Mosh install: in Mosh mode, when the remote lacks `mosh-server`, install via the system package manager over the authenticated connection —
     * Three permission modes: direct installation as root / password-less sudo for non-root users (`sudo -n`) / non-
     * non-root needs a password (entered on the install card; `sudo -S` feeds the password via stdin — JVM writes over the exec channel,
     * (Do not write the command line; if exec is unavailable, fall back to a printf pipe; passwords must not appear in the Compose state or in logs.)
     * After success, reboot mosh (in case of failure, naturally return to the boot card or perform a fallback).
     * Stream the installation process output into [TerminalController.moshInstallLog] (displayed in real-time on the guide card).
     *
     * @param password sudo password (After the first tap, if the system detects that sudo requires a password, the card will
     * Show an input field; after the user enters text, click "Install" to submit it).
     */
    fun installMosh(password: String? = null) {
        val s = c.session ?: return
        if (c.moshInstalling) return
        c.moshInstalling = true
        c.errorMessage = null
        c.moshInstallLog = ""
        c.scope.launch {
            try {
                // System detection (handle failures as unknown: display a tooltip for manual installation)
                val system = detectSystemFromOutput(s.probeSystem() ?: "")
                val isRoot = s.runCommand("id -u", 3_000)?.trim() == "0"
                val installCmd = moshInstallCommand(system)
                if (installCmd == null) {
                    TermLog.w("mosh") { "mosh install unsupported ${c.host.name}: system=$system" }
                    c.moshInstalling = false
                    c.errorMessage = strings().moshInstallUnsupported(system ?: "?")
                    return@launch
                }
                // sudo Requirement Check: When not running as root, check if `sudo -n true` allows password-less authentication
                val sudoNeedsPassword =
                    if (isRoot) {
                        false
                    } else {
                        val hasSudo = s.runCommand("command -v sudo", 3_000)?.isNotBlank() == true
                        if (!hasSudo) {
                            // Non-root and no sudo: Cannot be installed automatically; tooltip for manual installation (to avoid making users enter their password in vain).
                            TermLog.w("mosh") { "mosh install ${c.host.name}: sudo not found（非 root）" }
                            c.moshInstalling = false
                            c.errorMessage = strings().moshInstallFailed("sudo not found on remote (non-root user)")
                            return@launch
                        }
                        val sudoNaked = s.runCommand("sudo -n true 2>&1 && echo SUDO_OK", 3_000)
                        sudoNaked?.contains("SUDO_OK") != true
                    }
                if (sudoNeedsPassword && password.isNullOrBlank()) {
                    // A password is required but the user hasn't entered one yet: The card shows the password input field; retry after the user enters it.
                    TermLog.w("mosh") { "mosh install ${c.host.name}: sudo needs password——等待用户输入" }
                    c.moshInstalling = false
                    c.moshNeedsSudoPassword = true
                    return@launch
                }
                val fullCmd =
                    when {
                        isRoot -> installCmd
                        sudoNeedsPassword -> "sudo -S -p '' sh -c '$installCmd'"
                        else -> "sudo -n $installCmd"
                    }
                TermLog.i(
                    "mosh",
                ) { "mosh install ${c.host.name}: $installCmd${if (sudoNeedsPassword) "（sudo -S）" else ""}" }
                val log = StringBuilder()
                withContext(ioDispatcher()) {
                    // Streaming exec (JVM): Read install output block by block and log it; fall back to `runCommand` if `startExec` is not available
                    val exec = s.startExec(fullCmd, c.lastCols, c.lastRows)
                    if (exec != null) {
                        try {
                            // sudo -S reads the password from stdin: sudo on a PTY turns off echo, so the password does not appear in
                            // Command line (not visible in `ps`); it’s okay if written after `exec` launches—the PTY input buffer provides a fallback.
                            if (sudoNeedsPassword) exec.write((password + "\n").encodeToByteArray())
                            while (true) {
                                val chunk = exec.read() ?: break
                                log.append(chunk.decodeToString().replace("\r", ""))
                                c.moshInstallLog = log.toString()
                            }
                        } finally {
                            exec.close()
                        }
                    } else {
                        // Fallback for when the exec channel is unavailable (e.g. the connection dropped just before
                        // exec): POSIX printf pipe feeds the password (single quotes escaped; no cross-platform
                        // base64 -d flag quirks); on a wrong password `sudo` prints "Sorry, try again".
                        // Note: with this path the password travels inside the command string, briefly visible in the
                        // remote process list; the streaming exec path above avoids that.
                        val piped =
                            if (sudoNeedsPassword) {
                                "printf '%s' ${shSingleQuote(password!!)} | $fullCmd"
                            } else {
                                fullCmd
                            }
                        log.append(s.runCommand(piped, MOSH_INSTALL_TIMEOUT_MS) ?: "")
                        c.moshInstallLog = log.toString()
                    }
                }
                // Defensive sanitization: In case PTY echoes the password, do not log it in plain text (do not sanitize short passwords to avoid false positives).
                if (sudoNeedsPassword && (password?.length ?: 0) >= 4) {
                    c.moshInstallLog = c.moshInstallLog.replace(password!!, "***")
                }
                // After installation, verify that mosh-server is in place (PATH detection); in the event of failure → provide a clear error message,
                // The card retains the option to retry or fallback (with a tooltip at the end of the log indicating the reason).
                val verified = s.runCommand("command -v mosh-server", 5_000)?.isNotBlank() == true
                if (!verified) {
                    TermLog.e("mosh") { "mosh install then verify failed ${c.host.name}: ${log.take(120)}" }
                    c.moshInstalling = false
                    c.errorMessage =
                        strings().moshInstallFailed(
                            log
                                .toString()
                                .trim()
                                .take(200)
                                .ifBlank { null },
                        )
                    return@launch
                }
                // Installation completed: Reboot immediately (bootstrap verifies the installation: success → mosh;
                // Still missing → the card reappears, can retry/fall back; other errors → normal fallback.).
                // Herdr launch type is handled inside doConnectMosh (bootstrap command carries ` -- herdr`)
                TermLog.i("mosh") { "mosh install finished ${c.host.name}: ${log.take(120)}" }
                c.moshInstalling = false
                c.moshNeedsSudoPassword = false
                c.moshNeedsInstall = false
                doConnectMosh(existingSession = s)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                c.moshInstalling = false
                c.errorMessage = strings().moshInstallFailed(e.message)
            }
        }
    }

    /** “Fallback SSH” on the guidance card: Abandon installation; the current SSH channel is shown as “regular.”
     *  The user explicitly made a selection for SSH → Mark this session entry so that mosh will not retry on subsequent reconnections.
     *  Herdr performs command injection into the session (returns to the shell); otherwise, it executes the host launch command. */
    fun degradeMoshToSsh() {
        if (!c.moshNeedsInstall) return
        TermLog.i("mosh") { "mosh install skipped ${c.host.name}——降级 SSH" }
        c.moshNeedsInstall = false
        c.moshNeedsSudoPassword = false
        c.moshDegradedToSsh = true
        val cmd =
            if (c.launchHerdr) {
                c.herdrBin
            } else {
                c.host.startupCommand
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
        if (cmd != null) {
            if (c.launchHerdr) {
                // herdr injection should always use latency + screen-clearing path (to prevent MOTD from being pushed off the screen due to late output)
                sendHerdrLaunch(c.session ?: return)
            } else {
                c.session?.sendData((cmd + "\n").encodeToByteArray())
            }
        }
        c.frame++
    }

    /** System → mosh installation command (without the `sudo` prefix; permission level is configured in [installMosh] as root/password-less/password-based); returns `null` for unknown systems. */
    private fun moshInstallCommand(system: String?): String? =
        when (system) {
            "ubuntu", "debian" -> "apt-get update -y && apt-get install -y mosh"
            "fedora", "centos", "rhel", "rocky", "almalinux" -> "dnf install -y mosh"
            "arch", "manjaro", "endeavouros" -> "pacman -S --noconfirm mosh"
            "alpine" -> "apk add --no-cache mosh"
            "opensuse", "opensuse-leap", "opensuse-tumbleweed", "suse" -> "zypper -n install mosh"
            "void" -> "xbps-install -y mosh"
            "macos", "darwin" -> "brew install mosh"
            else -> null
        }

    /** Connection closure (Mosh fallback / SSH sharing): CONNECTED + keep-alive + grace period + system detection. */
    private fun finishConnected(
        s: SshSession,
        generation: Int,
        sendStartup: Boolean = true,
    ) {
        if (!isCurrentSession(generation, s)) {
            closeStaleSession(s)
            return
        }
        c.status = ConnStatus.CONNECTED
        // Silent detection start point: Counting from the completion of the connection. MOTD has finished → Herdr injection after 250 ms;
        // Still processing → lastOutputAtMs is being continuously refreshed by the consumer loop; wait until it finishes processing before performing injection.
        c.lastOutputAtMs = c.nowMs()
        c.reconnectAttempts = 0
        c.reconnectCount = 0
        c.reconnectCause = null
        c.errorMessage = null
        c.startKeepAlive()
        c.networkImmuneUntilMs = c.nowMs() + NETWORK_IMMUNE_MS
        c.swallowClosed = false
        if (c.host.system.isBlank()) {
            c.scope.launch {
                val raw = runCatching { s.probeSystem() }.getOrNull()
                TermLog.d("ssh") { "probeSystem ${c.host.name}: ${raw?.take(60) ?: "null"}" }
                val detected = raw?.let { detectSystemFromOutput(it) }
                if (detected != null && detected.isNotBlank() && c.status == ConnStatus.CONNECTED) {
                    // Update only the `system` field (based on the latest repository value): `c.host` is a snapshot taken when the application was created,
                    // The entire upsert will overwrite the recently recorded TOFU fingerprint (causing the dialog to reappear when a new host reconnects).
                    c.repository.patchHost(c.host.id) { it.copy(system = detected) }
                    c.onSystemDetected?.invoke(c.host.copy(system = detected))
                }
            }
        }
        // Launch point: Herdr session takes precedence (herdr is the launch point; use the full path obtained from detection);
        // Otherwise, launch the command (e.g., `tmux new -A -s main`, which resumes the session in its current state).
        if (sendStartup) {
            if (c.launchHerdr) {
                sendHerdrLaunch(s)
            } else {
                val cmd =
                    c.host.startupCommand
                        .trim()
                        .takeIf { it.isNotBlank() }
                if (cmd != null) s.sendData((cmd + "\n").encodeToByteArray())
            }
        }
        // herdr Workbench: Launch agent monitoring (polling source for blocked notifications) once the connection is ready.
        // If herdr is not installed, the installation card path is followed (with an early return from `finishConnected`), so this section will not be reached;
        // After success in installation, it is explicitly launched by `installHerdr`. The process is uniformly terminated by `controller.close()` when stopped.
        if (c.launchHerdr) c.startHerdrMonitor()
        c.frame++
    }

    /**
     * Launch the herdr command via shell injection (SSH / degraded path; the Mosh path is via the bootstrap parameter ` -- herdr`
     * (This issue does not occur when using `exec` directly without performing a login.)
     *
     * Combine the "silent output" and "clear screen" methods to resolve residual MOTD on Ubuntu hosts:
     * - The PAM MOTD (landscape/ESM check) during login to the shell may take 1–3 seconds and generate dozens of rows of output;
     *   Immediate injection will be echoed by readline in the middle of the MOTD; after herdr is launched, the delayed MOTD bytes push the
     *   herdr screen upward — herdr then shows only a small block at the top (text stays below)
     * - Injection timing = terminal output is quiet ([HERDR_OUTPUT_QUIET_MS] no new output; see
     *   [TerminalController.lastOutputAtMs]): hosts without MOTD are injected after ~250 ms (not
     *   (such as waiting idly for a fixed latency), wait until the MOTD finishes displaying before performing injection (clean); continuous output scenarios
     *   [HERDR_INJECT_MAX_WAIT_MS] Upper limit fallback
     * - During injection of the command, first clear the screen (current screen + scrollback) before launching herdr: even if the MOTD has already been displayed,
     *   herdr’s startup screen is also clean; clearing the screen is an optional enhancement, and failure must not block herdr.
     * - Use printf octal escape sequences to clear the screen (POSIX \0ddd, supported by both dash and bash), without any dependency
     *   Does /usr/bin/clear exist?
     * If the session has been closed or reconnected (the session has been replaced) before injection, skip the injection: the new connection has its own injection attempt.
     */
    private fun sendHerdrLaunch(s: SshSession) {
        val bin = c.herdrBin ?: "herdr"
        c.scope.launch {
            val t0 = c.nowMs()
            while (c.nowMs() - c.lastOutputAtMs < HERDR_OUTPUT_QUIET_MS &&
                c.nowMs() - t0 < HERDR_INJECT_MAX_WAIT_MS
            ) {
                delay(HERDR_QUIET_POLL_MS)
            }
            if (c.session !== s || c.status != ConnStatus.CONNECTED) return@launch
            val launch = herdrShellLaunchCommand(bin)
            s.sendData(launch.encodeToByteArray())
            TermLog.i("herdr") { "injected herdr launch (quiet+clear) ${c.host.name}" }
        }
    }

    /** Fallback banner: Displays the reason for the fallback and automatically disappears after a few seconds (if left displayed, it will cover the top of the terminal).
     *  === Clear only this tooltip; do not clear it if it is overwritten by a real error during the process. */
    private fun showDegradeNotice(message: String) {
        c.errorMessage = message
        c.scope.launch {
            delay(MOSH_DEGRADE_NOTICE_MS)
            if (c.errorMessage === message) c.errorMessage = null
        }
    }

    fun handleMoshExit(
        reason: MoshExitReason,
        generation: Int = c.connectionGeneration,
    ) {
        if (generation != c.connectionGeneration) return
        TermLog.w("mosh") { "mosh exit ${c.host.name} reason=$reason status=${c.status}" }
        if (c.status == ConnStatus.CONNECTED) {
            c.moshSession = null
            // Exception during connection (not user-initiated): automatically reconnect
            if (c.autoReconnect && c.reconnectAttempts < RECONNECT_MOSH_MAX) {
                c.reconnectAttempts++
                c.status = ConnStatus.CONNECTING
                c.reconnectCount = c.reconnectAttempts
                c.errorMessage = null
                c.scope
                    .launch {
                        delay(RECONNECT_BASE_DELAY_MS * c.reconnectAttempts)
                        if (c.status != ConnStatus.CLOSED) doConnect()
                    }.also { c.reconnectJob = it }
            } else {
                c.status = ConnStatus.CLOSED
                // Session exceptions, timeouts, and other issues must be made visible (previously they were silently discarded,
                // Users can only see “Disconnected” and do not know why)
                moshExitMessage(reason)?.let { c.errorMessage = it }
                c.stopKeepAlive()
            }
        } else if (c.status == ConnStatus.CONNECTING) {
            c.status = ConnStatus.ERROR
            c.errorMessage = moshExitMessage(reason) ?: strings().moshClientExited
            c.stopKeepAlive()
        }
    }

    /** Exit reason → user-visible message; NORMAL (clean close) returns null (no error shown). */
    private fun moshExitMessage(reason: MoshExitReason): String? =
        when (reason) {
            MoshExitReason.SESSION_ERROR -> strings().moshSessionError
            MoshExitReason.CONNECT_TIMEOUT -> strings().moshConnectTimeout
            MoshExitReason.NORMAL -> null
        }

    /** Unified cleanup after a mosh session is truly established (callback triggered upon receiving the first packet from the other end). */
    private fun onMoshConnected(
        client: MoshSession,
        generation: Int,
    ) {
        if (!isCurrentGeneration(generation) || c.moshSession !== client) { // Closed or replaced by a newer version
            client.close()
            return
        }
        // Latency injection: It cannot be too early (before herdr takes over, the bytes will be echoed as garbled characters by shell readline when treated as input),
        // It also can’t be too late (the herdr default theme shows a gray overlay for a few seconds). By 1200 ms, herdr has usually already taken over.
        if (c.moshThemePayload != null) {
            c.scope.launch {
                delay(MOSH_THEME_INJECT_DELAY_MS)
                injectThemeIfNeeded()
            }
        }
        c.status = ConnStatus.CONNECTED
        c.reconnectCount = 0
        c.errorMessage = null
        c.linkLostSeconds = 0
        c.startKeepAlive()
        // Network Toggle Immunity Period + Stability Period Reset:
        // Network events will not trigger a proactive reconnect within the first 30 seconds after connecting; after the connection has been maintained for 30 seconds, the reconnect count is reset to zero,
        // Avoid an infinite loop caused by onExit automatically reconnecting in a “connect-and-exit” scenario.
        c.networkImmuneUntilMs = c.nowMs() + NETWORK_IMMUNE_MS
        c.scope.launch {
            delay(MOSH_STABLE_RESET_MS)
            if (c.status == ConnStatus.CONNECTED) c.reconnectAttempts = 0
        }
        // Launch the command only in a regular session: Run herdr directly under the mosh-server in the herdr workspace.
        // (`-- herdr`), and when you run the launch command again, it will be fed into the herdr TUI's input stream
        if (!c.launchHerdr && c.host.startupCommand.isNotBlank()) {
            client.sendData((c.host.startupCommand.trim() + "\n").encodeToByteArray())
        }
        c.frame++
    }

    /**
     * Use Mosh for theme injection into a remote device (tools like herdr parse OSC responses from stdin).
     * See [dev.macodes.term.TerminalEmulator.buildThemeSyncPayload].
     * After the connection is established, perform a fixed latency injection; bytes are delivered via the mosh input channel,
     * herdr will parse this as if it were a terminal response.
     */
    private fun prepareThemeSync() {
        // Injection occurs only in TUI sessions (herdr workbench / herdr, tmux, etc., with configuration for a launch command):
        // The injected OSC response is sent to the remote shell as “user input”; a plain shell (bash +
        // readline) does not parse OSC and echoes ESC]10;… as ordinary characters. Only TUIs that
        // query the terminal theme understand it, so injection is safe only under these conditions.
        if (!c.host.moshThemeSync || (!c.launchHerdr && c.host.startupCommand.isBlank())) return
        c.moshThemePayload = c.emulator.buildThemeSyncPayload()
        c.moshThemeInjected = false
    }

    private fun injectThemeIfNeeded() {
        val payload = c.moshThemePayload ?: return
        if (c.moshThemeInjected) return
        val client = c.moshSession ?: return
        if (c.status == ConnStatus.CONNECTED && client.isActive()) {
            c.moshThemeInjected = true
            client.sendData(payload)
        }
    }

    /**
     * Unexpected SSH disconnection (`callbacks.onClosed` delegation): Exponential backoff for automatic reconnection (while retaining terminal buffering data),
     * When reconnect attempts are exhausted, set CLOSED and post a background notification.
     */
    fun onUnexpectedClose(reason: String?) {
        TermLog.w("ssh") { "onClosed ${c.host.name} reason=$reason status=${c.status} attempts=${c.reconnectAttempts}" }
        val wasConnected = c.status == ConnStatus.CONNECTED || c.status == ConnStatus.AUTH
        if (c.autoReconnect && wasConnected && c.reconnectAttempts < RECONNECT_SSH_MAX) {
            c.reconnectAttempts++
            if (c.reconnectCause == null) c.reconnectCause = SessionReconnectCause.CONNECTION_LOST
            c.session = null
            c.status = ConnStatus.CONNECTING
            c.reconnectCount = c.reconnectAttempts
            c.errorMessage = null
            c.scope
                .launch {
                    delay(RECONNECT_BASE_DELAY_MS * c.reconnectAttempts)
                    if (c.status != ConnStatus.CLOSED) doConnect()
                }.also { c.reconnectJob = it }
            return
        }
        TermLog.e("ssh") { "reconnect exhausted ${c.host.name} -> CLOSED" }
        c.status = ConnStatus.CLOSED
        c.stopKeepAlive()
        if (reason != null) c.errorMessage = reason
        // Background Event Notification: Unexpected disconnection (without reconnect) reports CONNECTION_LOST;
        // Automatic reconnection attempts have exhausted all options but still resulted in failure, reporting RECONNECT_FAILED (user intervention required);
        // All include a “Reconnect” action (notification prompting the user to tap to reconnect using the hostId)
        NotificationCenter.post(
            if (c.reconnectAttempts > 0) {
                NotificationEvent.RECONNECT_FAILED
            } else {
                NotificationEvent.CONNECTION_LOST
            },
            "Macodes",
            if (c.reconnectAttempts > 0) {
                strings().notificationReconnectFailed(c.host.name, reason ?: strings().terminalFailed)
            } else {
                strings().notificationConnectionLost(c.host.name, reason ?: strings().terminalDisconnected)
            },
            hostId = c.host.id,
        )
    }

    /**
     * Silently verify whether the current SSH connection is actually available. Services that stop and changes to the default network configuration are merely risk signals,
     * It cannot be directly proven that TCP is dead; the connection is only rebuilt if the server does not provide a response to the SSH request within the timeout period.
     */
    fun verifySshConnection(cause: SessionReconnectCause) {
        if (!c.autoReconnect || c.status != ConnStatus.CONNECTED || c.moshSession != null) return
        if (c.healthCheckJob?.isActive == true) {
            TermLog.d("ssh") { "health check coalesced ${c.host.name} cause=$cause" }
            return
        }
        val generation = c.connectionGeneration
        val target = c.session ?: return
        c.healthCheckJob =
            c.scope.launch {
                try {
                    delay(HEALTH_CHECK_SETTLE_MS)
                    if (!isCurrentSession(generation, target) || c.status != ConnStatus.CONNECTED) return@launch
                    val startedAt = c.nowMs()
                    val healthy = runCatching { target.checkAlive(HEALTH_CHECK_TIMEOUT_MS) }.getOrDefault(false)
                    if (!isCurrentSession(generation, target) || c.status != ConnStatus.CONNECTED) return@launch
                    if (healthy) {
                        TermLog.i("ssh") {
                            "health check ok ${c.host.name} cause=$cause in ${c.nowMs() - startedAt}ms"
                        }
                    } else {
                        TermLog.w("ssh") {
                            "health check failed ${c.host.name} cause=$cause in ${c.nowMs() - startedAt}ms; reconnect"
                        }
                        // Clear references to the current task to avoid `forceReconnect -> close()` canceling itself after
                        // This affects the next generation of connections that are launched immediately afterward.
                        c.healthCheckJob = null
                        forceReconnect(cause)
                    }
                } finally {
                    c.healthCheckJob = null
                }
            }
    }

    /** Clearly define controlled rebuild after failure: Old-generation callbacks are discarded by generation, while buffering and the input state are preserved. */
    fun forceReconnect(cause: SessionReconnectCause) {
        if (!c.autoReconnect) return
        TermLog.i("ssh") { "force reconnect ${c.host.name} cause=$cause status=${c.status}" }
        c.close()
        c.reconnectCause = cause
        c.reconnectAttempts = 1
        c.reconnectCount = 1
        c.errorMessage = null
        doConnect()
    }

    /**
     * When default network changes occur (Wi-Fi ↔ mobile data, switching between networks of the same type, or switching to or from VPN), the platform layer makes the call:
     * - SSH: First perform a health check that returns a response; only reconnect if the old TCP connection has expired;
     * - Mosh: Retains the existing session and allows the protocol to continue roaming from the new source address.
     */
    fun onNetworkChanged(kind: NetworkChangeKind) {
        TermLog.i("net") { "network event $kind status=${c.status} immune=${c.nowMs() < c.networkImmuneUntilMs}" }
        if (!c.autoReconnect) return
        // mosh: Neither a network outage nor a toggle to the default network will trigger a [rebuild]—UDP is connectionless, and the server fetches new sources from the client.
        // Learn the target address from the response + port rotation; mosh will automatically resume after network changes (native mosh
        // roaming capability). Automatic reconnect occurs only when the client exits with an exception (onExit).
        if (c.moshSession != null) return
        // Receiving "LOST" alone does not trigger SSH detection: some Android ROMs drop the default network callback a few seconds
        // after the app goes to the background, while existing TCP sockets and the foreground service stay valid. Tearing the session
        // down at that point is exactly what causes “switching to another app and coming back requires reconnecting”; the real network
        // toggle is handled by the DEFAULT_NETWORK_CHANGED path below, and if the socket really is closed, reader/onClosed reconnects.
        // This also allows the original TCP connection to be reused after a brief Wi-Fi interruption if the IP address remains unchanged.
        if (kind == NetworkChangeKind.LOST) {
            TermLog.i("net") { "LOST: keep socket ${c.host.name}, wait for transport change or onClosed" }
            return
        }
        val now = c.nowMs()
        if (now < c.networkImmuneUntilMs) {
            TermLog.d("net") { "DEFAULT_NETWORK_CHANGED: 免疫期内跳过 ${c.host.name}" }
            return
        }
        if (c.lastNetworkReconnectAtMs != 0L && now - c.lastNetworkReconnectAtMs < NETWORK_DEBOUNCE_MS) {
            TermLog.d("net") { "DEFAULT_NETWORK_CHANGED: 防抖跳过 ${c.host.name}" }
            return
        }
        c.lastNetworkReconnectAtMs = now
        when (c.status) {
            ConnStatus.CONNECTED -> verifySshConnection(SessionReconnectCause.NETWORK_CHANGED)
            // Connection/reconnect in progress: The network has just been toggled; please wait for the current process to complete (the count will not be reset).
            ConnStatus.CONNECTING, ConnStatus.AUTH -> {}
            else -> {}
        }
    }
}

/**
 * Herdr launch command in the SSH shell.
 *
 * Some minimal shells, and the first moments of a reconnect, have no `printf`; if clearing the screen fails, continue anyway and
 * launch herdr — otherwise `&&` leaves the session in a plain shell. Standard error is discarded so users never see it
 * (`printf: command not found`).
 */
internal fun herdrShellLaunchCommand(bin: String): String = "printf '\\0033[2J\\0033[3J\\0033[H' 2>/dev/null; ${shSingleQuote(bin)}\n"

/**
 * POSIX sh single-quote escape: `'` → `'\''`, used for piping printf output to feed a sudo password
 * (No differences across platforms regarding the `base64 -d` parameter).
 */
internal fun shSingleQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/**
 * Parse the actual installation path from the output of install.sh (install.sh prints
 * `installed herdr to /path/herdr`). Do not hardcode `~/.local/bin` or have any dependencies on $HOME:
 * Regardless of which directory the script is installed in, use the actual path it reports.
 */
internal fun parseInstallPath(log: String): String? {
    val marker = "installed herdr to "
    val idx = log.indexOf(marker)
    if (idx < 0) return null
    return log
        .substring(idx + marker.length)
        .substringBefore('\n')
        .trim()
        .takeIf { it.startsWith("/") }
}
