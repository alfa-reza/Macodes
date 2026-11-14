package dev.macodes.screen

import dev.macodes.generated.resources.Res
import dev.macodes.ssh.SshCallbacks
import dev.macodes.ssh.SshConnection
import dev.macodes.ssh.SshExecChannel
import dev.macodes.ssh.SshSession
import dev.macodes.ssh.createSftpSession
import dev.macodes.ssh.createSshSession
import dev.macodes.util.TermLog
import dev.macodes.util.ioDispatcher
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** ScreenSession contains only structured behavior; all user-visible text is injected by AppStrings during creation. */
internal data class ScreenSessionMessages(
    val connectionFailed: String,
    val readChannelFailed: String,
    val tcpPortMissing: String,
    val tcpChannelFailed: (Int) -> String,
    val tcpDisconnected: String,
    val screenInUse: String,
    val screenRecordingPermissionMissing: String,
    val ffmpegMissing: String,
    val unsupportedOs: (String) -> String,
    val relayUpgradeRequired: String,
    val displayMissing: String,
    val serviceNotRunning: String,
    val waylandHint: String,
    val waylandDependenciesMissing: String,
    val screenAsleepHint: String,
    val screenLockedHint: String,
    val firstFrameTimeout: String,
    val decoderInitializationFailed: (String?) -> String,
    val decoderNoOutput: (Int) -> String,
    val decodingFailed: (String) -> String,
    val playerUnsupported: String,
) {
    fun playerFailure(failure: ScreenPlayerFailure): String =
        when (failure) {
            is ScreenPlayerFailure.Initialization -> decoderInitializationFailed(failure.detail)
            is ScreenPlayerFailure.NoOutput -> decoderNoOutput(failure.fedFrames)
            is ScreenPlayerFailure.Decoding -> decodingFailed(failure.detail)
            ScreenPlayerFailure.Unsupported -> playerUnsupported
        }
}

/**
 * Screen streaming session: Independent SSH connection + direct-tcpip forwarding to a remote loopback video service,
 * After explicit TCP framing, feed the complete H.264 Annex-B frames to the hardware decoder.
 *
 * Architecture (determined by hard constraints on macOS screen recording permissions):
 * - On macOS, TCC only allows screen captures for GUI login sessions; for SSH/mosh background sessions, regardless of whether
 *   Screenshots cannot be captured for sshd/ffmpeg due to authorization issues (tested: suspend/black screen/exits).
 * - Therefore, the streaming process (ffmpeg avfoundation screen capture → VideoToolbox hardware encoding to H.264;
 *   (Linux automatically detects hardware encoding; in case of failure, it falls back to libx264) as
 *   LaunchAgent runs in the user’s GUI domain (launchctl bootstrap gui/$(id -u)) and remains resident
 *   Listen on the loopback port; on the phone side, reuse the SSH connection to transmit video and control the TCP stream—no need for
 *   Additional public ports or UDP callbacks.
 * - When the service is missing, the remote reports SCREEN_SERVICE_MISSING → uiState.serviceMissing,
 *   UI-guided one-click install ( [installService], same mode as the herdr install guide).
 */
class ScreenSession internal constructor(
    private val connection: SshConnection,
    private val callbacks: SshCallbacks,
    private val scope: CoroutineScope,
    private val uiState: ScreenUiState,
    private val messages: ScreenSessionMessages,
    /** The user initiates a reconnection due to a permission error; this is not used for automatic reconnection after a connection drop. */
    private val refreshPermissionsBeforeStart: Boolean = false,
    /** Connection-termination callbacks not actively closed (EOF/exceptions): AppRoot uses these to automatically reconnect (user feedback:
     * (Restarting relay or toggling sessions causes a “stream disconnected” error; you must manually reconnect.) */
    private val onStreamLost: (() -> Unit)? = null,
    /** When the player is continuously overloaded, request AppRoot to rewrite the configuration and rebuild the session. */
    private val onAdaptiveFpsRequested: ((Int) -> Unit)? = null,
) {
    private var ssh: SshSession? = null
    private var player: ScreenPlayer? = null

    @Volatile
    private var running = false
    private var installing = false

    /** First-frame timeout (if no frames are received after the connection is established, treat it as a streaming exception and provide a visible notice). */
    private var firstFrameDeadline = 0L
    private var firstFrameError: String? = null
    private var waylandPortalExpected = false

    private var tcpSession: ScreenTcpSession? = null

    fun start() {
        if (running) return
        running = true
        TermLog.i("screen") { "start ${connection.host}:${connection.port}" }
        scope.launch {
            try {
                val session = withContext(ioDispatcher()) { createSshSession(connection, callbacks) }
                ssh = session
                // close() may occur during the blocking phase of create/connect (quickly switching image quality, consecutive clicks
                // reconnect). The old implementation simply set `running` to `false`, but the coroutine would still continue to establish a connection
                // direct-tcpip becomes a “ghost connection” and kicks out the new screen, creating a loop that repeats every few seconds.
                if (!running) {
                    session.close()
                    return@launch
                }
                // First establish a connection and authenticate; subsequently, use the control plane exec and video direct-tcpip
                // All of them reuse this SSH connection.
                val connected = withContext(ioDispatcher()) { session.connectAuthOnly() }
                TermLog.i("screen") { "connectAuthOnly=${connected != null}" }
                if (!running) {
                    session.close()
                    return@launch
                }
                if (connected == null) {
                    uiState.error = messages.connectionFailed
                    running = false
                    return@launch
                }
                if (refreshPermissionsBeforeStart) {
                    // CoreGraphics can retain results from before authorization. It only refreshes when the user actively reconnects.
                    // Your own GUI LaunchAgent—without modifying system authorization or modifying the app's signature.
                    val refreshed =
                        withContext(ioDispatcher()) {
                            session.runCommandDetailed(REFRESH_PERMISSIONS_SCRIPT, 10_000)
                        }
                    if (!running) {
                        session.close()
                        return@launch
                    }
                    if (refreshed?.stdout?.lineSequence()?.any { it == "SCREEN_PERMISSION_REFRESH_OK" } != true) {
                        uiState.error = messages.serviceNotRunning
                        running = false
                        session.close()
                        return@launch
                    }
                    TermLog.i("screen") { "permission refresh completed ${connection.host}" }
                }
                // Control Plane Probing: Relay availability + version/FFmpeg/screen status + internal TCP port.
                // The video plane then uses the direct-tcpip channel over the same SSH connection to access the remote loopback port,
                // No additional video ports need to be opened on the public network.
                val result =
                    withContext(ioDispatcher()) {
                        session.runCommandDetailed(READ_STREAM_SCRIPT, 15_000)
                    }
                if (!running) {
                    session.close()
                    return@launch
                }
                if (result == null) {
                    uiState.error = messages.readChannelFailed
                    running = false
                    return@launch
                }
                // Handle remote detection flags (outdated version/missing FFmpeg/no display/service not running/screen status)
                if (!handleReadStreamStderr(result.stderr)) {
                    running = false
                    return@launch
                }
                val tcpPort =
                    result.stdout
                        .lineSequence()
                        .firstOrNull { it.startsWith("SCREEN_TCP_PORT:") }
                        ?.substringAfter(":")
                        ?.trim()
                        ?.toIntOrNull()
                val authToken = parseAuthToken(result.stdout)
                // Reading remote streaming parameters: sync the UI settings (the relay re-reads conf on every connect; after a reinstall or
                // writes from multiple devices the remote value may differ from the local default — otherwise the UI shows 30 while it pushes 120).
                val (cfgFps, cfgScale) = parseStreamCfg(result.stdout)
                cfgFps?.let {
                    uiState.streamFps = it
                    if (uiState.preferredStreamFps == 0) uiState.preferredStreamFps = it
                }
                cfgScale?.let { uiState.streamQuality = qualityIndexFor(it) }
                if (tcpPort == null || authToken == null) {
                    uiState.error = if (authToken == null) messages.relayUpgradeRequired else messages.tcpPortMissing
                    uiState.serviceMissing = authToken == null
                    uiState.relayNeedsUpgrade = authToken == null
                    running = false
                    return@launch
                }
                // There is a very brief race condition between relay and SSH direct-tcpip channel launch (immediately after a service upgrade or restart),
                // Retry in small steps rather than immediately exposing a “connection-refused” error to the user.
                var videoChannel: SshExecChannel? = null
                for (attempt in 0 until 6) {
                    videoChannel =
                        withContext(ioDispatcher()) {
                            session.openDirectTcpip("127.0.0.1", tcpPort)
                        }
                    if (videoChannel != null) break
                    if (attempt < 5) delay(250)
                }
                val directChannel = videoChannel
                if (!running) {
                    directChannel?.close()
                    session.close()
                    return@launch
                }
                if (directChannel == null) {
                    uiState.error = messages.tcpChannelFailed(tcpPort)
                    running = false
                    return@launch
                }
                // The player takes over H.264 hardware decoding/rendering; the first-frame callback clears the timeout.
                val p =
                    ScreenPlayer(
                        targetFps = uiState.streamFps,
                        onReady = {
                            firstFrameDeadline = 0
                            scope.launch {
                                val readyAt = Clock.System.now().toEpochMilliseconds()
                                uiState.videoReadyAtMillis = readyAt
                                uiState.videoReady = true
                                // Frame arrived: clear timeout and screen-off/lock-screen notices (recoverable state)
                                if (uiState.error == firstFrameError) uiState.error = null
                                firstFrameError = null
                                uiState.screenHint = null
                                delay(SCREEN_STABLE_WINDOW_MS)
                                if (running && uiState.videoReadyAtMillis == readyAt) {
                                    uiState.streamReconnectAttempts = 0
                                }
                            }
                        },
                        onError = { failure -> scope.launch { uiState.error = messages.playerFailure(failure) } },
                    )
                player = p
                uiState.player = p
                p.start()
                // Decoding Capability Detection (based on the resolution corresponding to the current image quality setting): The menu is based on this.
                // Hidden frame rate entries that do not meet the decoder’s threshold (flagship devices typically run at 60+ fps at 1080p; if detection
                // A lower value indicates the true bottleneck; increasing it further is pointless—the decoder is already at full capacity, so higher latency will result.
                val capScale = uiState.streamQuality
                val capW =
                    when (capScale) {
                        0 -> 960
                        2 -> 1920
                        3 -> 2560
                        else -> 1280
                    }
                uiState.decoderMaxFps = probeDecoderMaxFps(capW, (capW * 9 / 16).coerceAtLeast(480))
                TermLog.i("screen") { "decoder capability: ${uiState.decoderMaxFps}fps @ ${capW}p" }
                // close() may also occur during player/decoder initialization. It must be called before any actual data is sent
                // Double-check the TCP authentication packet before sending it; otherwise, the old session that has already been replaced will still reach the remote end,
                // It preempts new sessions and causes a disconnect loop occurring every 3–6 seconds.
                if (!running) {
                    p.stop()
                    directChannel.close()
                    session.close()
                    return@launch
                }
                // The first Wayland connection must be confirmed by the client-side user via the system’s Remote Desktop portal;
                // Allow sufficient time to complete the one-time authorization; afterward, reuse the permission within the same desktop session and resume it after 12 seconds.
                val firstFrameTimeout =
                    if (waylandPortalExpected) SCREEN_WAYLAND_FIRST_VIDEO_TIMEOUT_MS else SCREEN_FIRST_VIDEO_TIMEOUT_MS
                firstFrameDeadline = Clock.System.now().toEpochMilliseconds() + firstFrameTimeout
                // First-frame timeout monitoring: connection established but no frames arriving for a long time → show a notice (to prevent an endless black screen).
                // After the first frame arrives, onReady resets the deadline to zero, and this monitor exits naturally.
                scope.launch {
                    while (running && firstFrameDeadline > 0) {
                        if (Clock.System.now().toEpochMilliseconds() > firstFrameDeadline) {
                            if (running && !uiState.videoReady && uiState.error == null) {
                                firstFrameError = messages.firstFrameTimeout
                                uiState.error = firstFrameError
                            }
                            break
                        }
                        delay(500)
                    }
                }
                // Reusing bidirectional video channels over an authenticated SSH connection: The remote relay is a listener that listens only on the loopback address,
                // Neither video nor control depends on carrier UDP backhaul or additional public port mapping.
                val tcp =
                    ScreenTcpSession(
                        channel = directChannel,
                        scope = scope,
                        authToken = authToken,
                        firstVideoTimeoutMillis = firstFrameTimeout,
                        onVideoPacket = { packet -> p.feed(packet.data) },
                        playerMetrics = p::metrics,
                        onStatus = { status ->
                            // Initial packet and subsequent permission updates: 0=OK, 1=macOS lacks accessibility permissions, 2=control not supported,
                            // 3=Busy, 4=Lacks screen recording permission; do not attempt automatic reconnection if explicitly denied.
                            if (screenStatusRejectsConnection(status)) {
                                uiState.recordingPermissionMissing = status == SCREEN_TCP_STATUS_CAPTURE_PERMISSION_MISSING
                                running = false
                                firstFrameDeadline = 0
                                TermLog.i("screen") { "screen connection rejected status=$status" }
                                scope.launch {
                                    runCatching { p.stop() }
                                    if (player === p) player = null
                                    uiState.player = null
                                    uiState.controlSender = null
                                    uiState.keySender = null
                                    uiState.connected = false
                                    uiState.videoReady = false
                                    uiState.controlPermissionMissing = false
                                    uiState.error =
                                        if (status == SCREEN_TCP_STATUS_CAPTURE_PERMISSION_MISSING) {
                                            messages.screenRecordingPermissionMissing
                                        } else {
                                            messages.screenInUse
                                        }
                                }
                                scope.launch(ioDispatcher()) { session.close() }
                                false
                            } else {
                                uiState.recordingPermissionMissing = false
                                scope.launch {
                                    uiState.controlPermissionMissing = status == 1
                                    uiState.controlUnsupported = status == 2
                                }
                                true
                            }
                        },
                        onDisconnected = {
                            // A "direct-tcpip" disconnect usually means that the underlying SSH or relay connection has been lost; go
                            // Completely reconnect AppRoot, re-authenticate, and rebuild a clean channel.
                            if (running) {
                                running = false
                                val readyFor =
                                    uiState.videoReadyAtMillis
                                        .takeIf { it > 0 }
                                        ?.let { Clock.System.now().toEpochMilliseconds() - it }
                                TermLog.w("screen") { "video channel disconnected readyForMs=$readyFor" }
                                scope.launch {
                                    uiState.connected = false
                                    uiState.error = messages.tcpDisconnected
                                }
                                onStreamLost?.invoke()
                            }
                        },
                    )
                tcpSession = tcp
                tcp.start()
                if (!running) {
                    tcp.close()
                    p.stop()
                    session.close()
                    return@launch
                }
                // The continuous arrival of video packets does not necessarily mean that the video is still playing: some Android MediaCodec
                // The driver will enter blocking mode in `native dequeue`. After detecting a freeze in the display layer, rebuild the entire
                // the playback pipeline, to prevent the UI from getting stuck on the first frame while still showing “Controlling.”
                scope.launch {
                    val adaptiveController = ScreenAdaptiveFpsController()
                    var previousTransport = tcp.metrics()
                    var lastMetricsLogAt = Clock.System.now().toEpochMilliseconds()
                    var previousUiTransport = previousTransport
                    var previousUiPlayer = p.metrics()
                    var previousUiMetricsAt = lastMetricsLogAt
                    var adaptiveRequestPending = false
                    while (running && tcp.isActive()) {
                        delay(1_000)
                        val now = Clock.System.now().toEpochMilliseconds()
                        val renderedAt = p.lastRenderedAtMillis
                        if (
                            isScreenRenderingStalled(
                                now,
                                renderedAt,
                                uiState.videoReady,
                                p.renderSurfaceAttached,
                            )
                        ) {
                            TermLog.w("screen") {
                                "视频渲染停滞：idle=${now - renderedAt}ms，主动重建播放链路"
                            }
                            tcp.fail("render-stalled")
                            break
                        }
                        if (uiState.videoReady && p.renderSurfaceAttached) {
                            val playerMetrics = p.metrics()
                            val transport = tcp.metrics()
                            val uiElapsed = (now - previousUiMetricsAt).coerceAtLeast(1)
                            val uiReceived = (transport.receivedFrames - previousUiTransport.receivedFrames).coerceAtLeast(0)
                            val uiBytes = (transport.receivedBytes - previousUiTransport.receivedBytes).coerceAtLeast(0)
                            val uiNetworkLost = (transport.lostFrames - previousUiTransport.lostFrames).coerceAtLeast(0)
                            val uiPlayerDropped =
                                (playerMetrics.droppedFrames - previousUiPlayer.droppedFrames).coerceAtLeast(0)
                            val uiTotal = uiReceived + uiNetworkLost
                            uiState.fps = (uiReceived * 1_000 / uiElapsed).toInt()
                            uiState.bitrateKbps = (uiBytes * 8 / uiElapsed).toInt()
                            uiState.jitterMillis = transport.jitterMillis
                            uiState.droppedPermille =
                                if (uiTotal > 0) {
                                    ((uiNetworkLost + uiPlayerDropped) * 1_000 / uiTotal).toInt().coerceIn(0, 1_000)
                                } else {
                                    0
                                }
                            previousUiTransport = transport
                            previousUiPlayer = playerMetrics
                            previousUiMetricsAt = now
                            if (now - lastMetricsLogAt >= 5_000) {
                                val bytes = (transport.receivedBytes - previousTransport.receivedBytes).coerceAtLeast(0)
                                val frames = (transport.receivedFrames - previousTransport.receivedFrames).coerceAtLeast(0)
                                val elapsed = (now - lastMetricsLogAt).coerceAtLeast(1)
                                val deliveredFps = frames * 1_000 / elapsed
                                val bitrateKbps = bytes * 8 / elapsed
                                TermLog.i("screen") {
                                    "stream metrics: fps=$deliveredFps bitrate=${bitrateKbps}kbps " +
                                        "received=${playerMetrics.receivedFrames} " +
                                        "rendered=${playerMetrics.renderedFrames} " +
                                        "dropped=${playerMetrics.droppedFrames} " +
                                        "busy=${playerMetrics.decoderBusyFrames} queue=${playerMetrics.queueDepth}"
                                }
                                previousTransport = transport
                                lastMetricsLogAt = now
                            }
                            val requestedFps =
                                adaptiveController.evaluate(
                                    metrics = playerMetrics,
                                    currentFps = uiState.streamFps,
                                    preferredFps =
                                        uiState.preferredStreamFps.takeIf { it > 0 }
                                            ?: uiState.streamFps,
                                    decoderMaxFps = uiState.decoderMaxFps,
                                    nowMillis = now,
                                    lastChangeAtMillis = uiState.adaptiveFpsChangedAtMillis,
                                )
                            if (!adaptiveRequestPending && requestedFps != null && requestedFps != uiState.streamFps) {
                                adaptiveRequestPending = true
                                val oldFps = uiState.streamFps
                                uiState.streamFps = requestedFps
                                uiState.adaptiveFpsChangedAtMillis = now
                                TermLog.w("screen") {
                                    "adaptive fps: $oldFps->$requestedFps " +
                                        "preferred=${uiState.preferredStreamFps} " +
                                        "decoderMax=${uiState.decoderMaxFps}"
                                }
                                onAdaptiveFpsRequested?.invoke(requestedFps)
                            }
                        }
                    }
                }
                uiState.connected = true
                // Remote Operation Sender: Controls packet transmission over a TCP channel
                uiState.controlSender = { type, x, y, extra ->
                    tcp.sendRaw(ScreenControlPacket.encode(type, x, y, extra))
                }
                // Remote Keyboard Transmitter: Text / Key Code + Modifiers → TCP Control Packet
                uiState.keySender = { keyCode, mods, text ->
                    if (text.isNotEmpty()) {
                        runCatching { tcp.sendRaw(ScreenControlPacket.encodeText(text)) }
                    } else {
                        tcp.sendRaw(ScreenControlPacket.encodeKey(keyCode, mods))
                    }
                }
                TermLog.i("screen") { "SSH direct-tcpip 视频会话已建立 remote=127.0.0.1:$tcpPort" }
            } catch (e: Exception) {
                // The `message` may be null (e.g., `NetworkOnMainThreadException`); the full class name and stack trace must be recorded.
                TermLog.w("screen") { "screen session error: ${e::class.qualifiedName}: ${e.message}" }
                TermLog.w("screen") { e.stackTraceToString().take(1500) }
                if (running) {
                    uiState.error = messages.connectionFailed
                    running = false
                    onStreamLost?.invoke()
                }
            }
        }
    }

    /**
     * Handle detection flags for the stderr stream in the read script (one-time; replaces the original readErr loop).
     * Returns false = an error state or boot installation state has been set; the caller should stop.
     */
    private fun handleReadStreamStderr(err: String): Boolean {
        if (err.contains("FFMPEG_MISSING")) {
            uiState.ffmpegMissing = true
            uiState.serviceMissing = true
            uiState.error = messages.ffmpegMissing
            return false
        }
        if (err.contains("SCREEN_UNSUPPORTED_OS")) {
            val os =
                err
                    .substringAfter("SCREEN_UNSUPPORTED_OS:")
                    .lineSequence()
                    .first()
                    .trim()
            uiState.error = messages.unsupportedOs(os)
            return false
        }
        if (err.contains("SCREEN_RELAY_OLD") || err.contains("SCREEN_AUTH_MISSING")) {
            uiState.serviceMissing = true
            uiState.relayNeedsUpgrade = true
            uiState.error = messages.relayUpgradeRequired
            return false
        }
        if (err.contains("SCREEN_NO_DISPLAY")) {
            uiState.error = messages.displayMissing
            return false
        }
        if (err.contains("SCREEN_WAYLAND_DEPS_MISSING")) {
            uiState.serviceMissing = true
            uiState.error = messages.waylandDependenciesMissing
            return false
        }
        if (err.contains("SCREEN_SERVICE_MISSING") ||
            err.contains("SCREEN_VIDEO_SERVICE_MISSING") ||
            err.contains("Connection refused", ignoreCase = true)
        ) {
            uiState.serviceMissing = true
            uiState.error = messages.serviceNotRunning
            return false
        }
        // Notice-type (does not block streaming)
        if (err.contains("SCREEN_WAYLAND_ONLY")) {
            waylandPortalExpected = true
            uiState.screenHint = messages.waylandHint
        }
        if (err.contains("SCREEN_ASLEEP")) {
            uiState.screenHint = messages.screenAsleepHint
        } else if (err.contains("SCREEN_LOCKED")) {
            uiState.screenHint = messages.screenLockedHint
        }
        return true
    }

    /**
     * One-click installation of remote streaming service (idempotent): Check for/install FFmpeg → Write LaunchAgent plist
     * → Bootstrap to the user GUI domain and verify that the port is a listener. Follows the same mode as the herdr installation bootstrap:
     * Stream the output to [onLog] (displayed in real-time on the UI), and callback to [onComplete] upon completion.
     */
    @OptIn(ExperimentalResourceApi::class)
    fun installService(
        sudoPassword: String? = null,
        onLog: (String) -> Unit,
        onComplete: (Boolean) -> Unit,
    ) {
        if (installing) return
        val s = ssh
        if (s == null) {
            onComplete(false)
            return
        }
        installing = true
        uiState.installing = true
        uiState.installLog = ""
        scope.launch {
            var stagedBinary: String? = null
            try {
                // Consistent with the Mosh installation: On Linux, check the three permission states first if ffmpeg is missing.
                // Show the password prompt only when interactive `sudo` is actually required; do not include the password in the command line.
                val os = withContext(ioDispatcher()) { s.runCommand("uname -s", 3_000)?.trim() }
                val arch = withContext(ioDispatcher()) { s.runCommand("uname -m", 3_000)?.trim() }
                val sessionType =
                    if (os == "Linux") {
                        withContext(ioDispatcher()) { s.runCommand(SESSION_TYPE_PROBE_SCRIPT, 5_000)?.trim() }
                    } else {
                        null
                    }
                val native = ScreenServiceAssets.binaryFor(os, arch)
                if (native == null) {
                    onLog(messages.unsupportedOs("$os/$arch"))
                    onComplete(false)
                    return@launch
                }
                val ffmpegPresent =
                    if (os == "Linux") {
                        withContext(ioDispatcher()) {
                            s.runCommand(FFMPEG_PROBE_SCRIPT, 3_000)?.contains("FFMPEG_OK") == true
                        }
                    } else {
                        true
                    }
                val xlibPresent =
                    if (os == "Linux" && sessionType != "wayland") {
                        withContext(ioDispatcher()) {
                            s.runCommand(XCLIP_PROBE_SCRIPT, 3_000)?.contains("XLIB_OK") == true
                        }
                    } else {
                        true
                    }
                val waylandDependenciesPresent =
                    if (os == "Linux" && sessionType == "wayland") {
                        withContext(ioDispatcher()) {
                            s.runCommand(WAYLAND_PROBE_SCRIPT, 5_000)?.contains("WAYLAND_OK") == true
                        }
                    } else {
                        true
                    }
                val isRoot =
                    os == "Linux" &&
                        withContext(ioDispatcher()) { s.runCommand("id -u", 3_000)?.trim() == "0" }
                val desktopManagerPresent =
                    os != "Linux" ||
                        withContext(ioDispatcher()) {
                            s.runCommand("command -v zenity", 3_000)?.isNotBlank() == true
                        }
                val hasSudo =
                    os == "Linux" &&
                        !isRoot &&
                        withContext(ioDispatcher()) {
                            s.runCommand("command -v sudo", 3_000)?.isNotBlank() == true
                        }
                val sudoPasswordless =
                    if (os == "Linux" &&
                        (!ffmpegPresent || !xlibPresent || !waylandDependenciesPresent || !desktopManagerPresent) &&
                        !isRoot &&
                        hasSudo
                    ) {
                        withContext(ioDispatcher()) {
                            s
                                .runCommand("sudo -n true 2>/dev/null && echo SUDO_OK", 3_000)
                                ?.contains("SUDO_OK") == true
                        }
                    } else {
                        false
                    }
                val sudoNeedsPassword =
                    needsScreenSudoPassword(
                        os = os,
                        ffmpegPresent = ffmpegPresent,
                        xlibPresent = xlibPresent,
                        waylandDependenciesPresent = waylandDependenciesPresent,
                        desktopManagerPresent = desktopManagerPresent,
                        isRoot = isRoot,
                        hasSudo = hasSudo,
                        sudoPasswordless = sudoPasswordless,
                    )
                if (sudoNeedsPassword && sudoPassword.isNullOrBlank()) {
                    TermLog.i("screen") { "Linux screen dependencies require sudo password" }
                    installing = false
                    uiState.installing = false
                    uiState.needsSudoPassword = true
                    return@launch
                }

                val log = StringBuilder()
                run {
                    val bytes = Res.readBytes("files/macodes-screen/${native.filename}")
                    check(bytes.size == native.size) { "Invalid bundled screen service size" }
                    val sftp = withContext(ioDispatcher()) { createSftpSession(connection, callbacks) }
                    try {
                        withContext(ioDispatcher()) {
                            val directory = "${sftp.home()}/Library/Application Support/macodes"
                            val mkdir = s.runCommandDetailed("umask 077; mkdir -p ${screenShellQuote(directory)}", 10_000)
                            check(mkdir?.exitCode == 0) { "Unable to create screen service directory" }
                            val stage = "$directory/screen-service.upload-${Random.nextLong().toULong().toString(16)}"
                            stagedBinary = stage
                            var offset = 0
                            sftp.upload(stage, bytes.size.toLong()) {
                                if (offset == bytes.size) {
                                    null
                                } else {
                                    val end = (offset + 64 * 1024).coerceAtMost(bytes.size)
                                    bytes.copyOfRange(offset, end).also { offset = end }
                                }
                            }
                        }
                    } finally {
                        withContext(ioDispatcher() + NonCancellable) { sftp.close() }
                    }
                }
                val nativeEnvironment =
                    "MACODES_NATIVE_STAGE=${screenShellQuote(checkNotNull(stagedBinary))}\n" +
                        "MACODES_NATIVE_SHA256=${screenShellQuote(native.sha256)}\n" +
                        "MACODES_SESSION_TYPE=${screenShellQuote(sessionType.orEmpty())}\n"
                val command =
                    nativeEnvironment +
                        (if (sudoNeedsPassword) "MACODES_SUDO_STDIN=1\n" else "") +
                        INSTALL_SCRIPT
                val ch = withContext(ioDispatcher()) { s.startExecRaw(screenInstallCommand(command)) }
                if (ch != null) {
                    // No stdin echoback without PTY; `sudo -S` reads this line only for this installation.
                    if (sudoNeedsPassword) {
                        withContext(ioDispatcher()) {
                            ch.write((sudoPassword + "\n").encodeToByteArray())
                        }
                    }

                    fun visibleLog(): String {
                        val tail = log.toString().takeLast(4096)
                        return if (sudoNeedsPassword && (sudoPassword?.length ?: 0) >= 4) {
                            tail.replace(sudoPassword!!, "***")
                        } else {
                            tail
                        }
                    }

                    // Merge stdout/stderr remotely; ensure the log is complete after reaching EOF to prevent premature termination.
                    // The channel loses the last error output; StringBuilder is also written to by only one reader.
                    withContext(ioDispatcher()) {
                        while (true) {
                            val data = ch.read() ?: break
                            log.append(data.decodeToString().replace("\r", ""))
                            onLog(visibleLog())
                        }
                        ch.close()
                    }
                }
                installing = false
                uiState.installing = false
                // When the script fails under `set -e`, the channel reaches EOF but the exit code cannot be obtained: use the script's success marker
                // MACODES_SCREEN_OK check to prevent the installation failure from triggering a reconnect that results in an endless spinning wheel
                val succeeded = log.contains("MACODES_SCREEN_OK")
                if (succeeded) uiState.needsSudoPassword = false
                onComplete(succeeded)
            } catch (e: Exception) {
                TermLog.w("screen") { "install service error: $e" }
                installing = false
                uiState.installing = false
                onComplete(false)
            } finally {
                stagedBinary?.let { path ->
                    withContext(ioDispatcher() + NonCancellable) {
                        runCatching { s.runCommand("rm -f ${screenShellQuote(path)}", 5_000) }
                    }
                }
            }
        }
    }

    /**
     * Set remote streaming parameters (frame rate/resolution): Use SSH to write the relay configuration (~/.macodes-screen.conf),
     * Changes take effect the next time the session is rebuilt (read on each connection by relay).
     */
    suspend fun setStreamConfig(
        fps: Int,
        scale: String,
    ): Boolean {
        val s = ssh ?: return false
        // "scale" is a controlled constant (e.g., 1280:-2, without quotation marks), so it is safe to concatenate directly.
        val cmd = "printf 'fps=%d\\nscale=%s\\n' $fps '$scale' > ~/.macodes-screen.conf"
        return withContext(ioDispatcher()) { s.runCommand(cmd, 5_000) != null }
    }

    fun close() {
        running = false
        firstFrameDeadline = 0
        firstFrameError = null
        tcpSession?.close()
        tcpSession = null
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        player = null
        uiState.player = null
        uiState.controlSender = null
        uiState.keySender = null
        uiState.connected = false
        uiState.videoReady = false
        try {
            ssh?.close()
        } catch (_: Exception) {
        }
        ssh = null
    }

    companion object {
        /** TCP port of the streaming service (resident via LaunchAgent; the read-stream script uses `lsof` to probe liveness). */
        const val SCREEN_PORT = ScreenServiceAssets.SCREEN_PORT

        /** relay internal TCP video port (is a listener for remote loopback, accessed via SSH direct-tcpip). */
        const val SCREEN_TCP_PORT = SCREEN_PORT + 2

        /** Refresh the in-process permissions cache for macOS processes; no reboot is required on Linux. */
        internal val REFRESH_PERMISSIONS_SCRIPT =
            """
            if [ "${'$'}(uname)" = "Darwin" ]; then
              launchctl kickstart -k "gui/${'$'}(id -u)/dev.macodes.screen" || exit 1
              for attempt in 1 2 3 4 5 6 7 8 9 10; do
                if lsof -nP -iTCP:$SCREEN_PORT -sTCP:LISTEN >/dev/null 2>&1 \
                  && lsof -nP -iTCP:$SCREEN_TCP_PORT -sTCP:LISTEN >/dev/null 2>&1; then
                  echo SCREEN_PERMISSION_REFRESH_OK
                  exit 0
                fi
                sleep 0.5
              done
              exit 1
            fi
            echo SCREEN_PERMISSION_REFRESH_OK
            """.trimIndent()

        /** Check for ffmpeg before installation; use the same set of non-interactive SSH PATH fallbacks as the stream reader/installation script. */
        internal val FFMPEG_PROBE_SCRIPT =
            """
            for cand in ${'$'}(command -v ffmpeg 2>/dev/null) "${'$'}HOME/bin/ffmpeg" /opt/homebrew/bin/ffmpeg /usr/local/bin/ffmpeg /usr/bin/ffmpeg; do
              if [ -n "${'$'}cand" ] && [ -x "${'$'}cand" ]; then echo FFMPEG_OK; exit 0; fi
            done
            """.trimIndent()

        /** Detect dependencies for Linux remote control; if missing, share a single sudo authorization with ffmpeg to install. */
        internal const val XCLIP_PROBE_SCRIPT = "command -v xclip >/dev/null 2>&1 && echo XLIB_OK"

        internal val SESSION_TYPE_PROBE_SCRIPT =
            """
            if command -v loginctl >/dev/null 2>&1; then
              for sid in ${'$'}(loginctl list-sessions --no-legend 2>/dev/null | awk -v uid="${'$'}(id -u)" '${'$'}2 == uid { print ${'$'}1 }'); do
                TYPE="${'$'}(loginctl show-session "${'$'}sid" -p Type --value 2>/dev/null)"
                REMOTE="${'$'}(loginctl show-session "${'$'}sid" -p Remote --value 2>/dev/null)"
                case "${'$'}TYPE:${'$'}REMOTE" in
                  wayland:no|x11:no) echo "${'$'}TYPE"; exit 0 ;;
                esac
              done
            fi
            if pgrep -u "${'$'}(id -u)" -x Xorg >/dev/null 2>&1 || pgrep -u "${'$'}(id -u)" -x X >/dev/null 2>&1; then echo x11; fi
            """.trimIndent()

        /** Wayland catches the system Remote Desktop portal + PipeWire, converted to Y4M via GStreamer. */
        internal val WAYLAND_PROBE_SCRIPT =
            """
            command -v gst-launch-1.0 >/dev/null 2>&1 \
              && gst-inspect-1.0 pipewiresrc >/dev/null 2>&1 \
              && gst-inspect-1.0 y4menc >/dev/null 2>&1 \
              && echo WAYLAND_OK
            """.trimIndent()

        /** Should a sudo password input prompt appear when Linux is missing a dependency? This is a purely logical question for consistent regression testing across all platforms. */
        internal fun needsScreenSudoPassword(
            os: String?,
            ffmpegPresent: Boolean,
            xlibPresent: Boolean = true,
            waylandDependenciesPresent: Boolean = true,
            desktopManagerPresent: Boolean = true,
            isRoot: Boolean,
            hasSudo: Boolean,
            sudoPasswordless: Boolean,
        ): Boolean =
            os == "Linux" &&
                (!ffmpegPresent || !xlibPresent || !waylandDependenciesPresent || !desktopManagerPresent) &&
                !isRoot &&
                hasSudo &&
                !sudoPasswordless

        /**
         * Relay protocol version: The relay deployed via the client’s built-in installation script and the relay already running on the remote server
         * Match identifiers. Add +1 when updating relay behavior (streaming parameters/self-healing logic) —
         * The read-stream script checks for remote version files and prompts the user to reinstall if there is a mismatch (user feedback:
         * Client-side scripts must match the version of the remote scripts; otherwise, the old relay will not be able to run the new features.
         */
        const val RELAY_VERSION = ScreenServiceAssets.RELAY_VERSION

        /**
         * Read-stream pre-probe script: only probes relay/version/ffmpeg/display status; on success reports
         * Remote TCP video port loopback. Actual video does not pass through `exec stdout`; it is transmitted via SSH direct-tcpip.
         * Establish an independent two-way channel.
         */
        val READ_STREAM_SCRIPT =
            """
            PORT=$SCREEN_PORT
            OS=${'$'}(uname)
            # First confirm the remote really is a capturable desktop system, then check the relay version. Otherwise a desktop-less
            # Linux server/container is misjudged as “service version too old”, pushing users into a pointless install.
            case "${'$'}OS" in
              Darwin)
                # macOS: avfoundation screen capture (LaunchAgent service)
                :
                ;;
              Linux)
                # Trust logind's local user session type first. Wayland usually also runs a
                # rootless Xwayland; do not mistake it for X11 just because the process exists (that way
                # x11grab only yields a black root window and the mouse).
                # Do not treat the GDM greeter's own Xwayland as the current SSH user's desktop:
                # right after a reboot, before the user has logged in graphically, it usually belongs to gdm-greeter, and the current
                # user can neither read Xauthority nor inject control events.
                HAS_USER_DISPLAY=0
                USER_SESSION_TYPE=""
                if command -v loginctl >/dev/null 2>&1; then
                  for sid in ${'$'}(loginctl list-sessions --no-legend 2>/dev/null | awk -v uid="${'$'}(id -u)" '${'$'}2 == uid { print ${'$'}1 }'); do
                    SESSION_TYPE="${'$'}(loginctl show-session "${'$'}sid" -p Type --value 2>/dev/null)"
                    SESSION_REMOTE="${'$'}(loginctl show-session "${'$'}sid" -p Remote --value 2>/dev/null)"
                    case "${'$'}SESSION_TYPE:${'$'}SESSION_REMOTE" in
                      wayland:no|x11:no)
                        HAS_USER_DISPLAY=1
                        USER_SESSION_TYPE="${'$'}SESSION_TYPE"
                        break
                        ;;
                    esac
                  done
                fi
                if [ "${'$'}HAS_USER_DISPLAY" != "1" ] && { pgrep -u "${'$'}(id -u)" -x Xorg >/dev/null 2>&1 \
                  || pgrep -u "${'$'}(id -u)" -x X >/dev/null 2>&1; }; then
                  HAS_USER_DISPLAY=1
                  USER_SESSION_TYPE="x11"
                fi
                if [ "${'$'}HAS_USER_DISPLAY" != "1" ]; then
                  echo "SCREEN_NO_DISPLAY" >&2
                  exit 1
                fi
                if [ "${'$'}USER_SESSION_TYPE" = "wayland" ]; then
                  echo "SCREEN_WAYLAND_ONLY" >&2
                  if ! command -v gst-launch-1.0 >/dev/null 2>&1 \
                    || ! gst-inspect-1.0 pipewiresrc >/dev/null 2>&1 \
                    || ! gst-inspect-1.0 y4menc >/dev/null 2>&1; then
                    echo "SCREEN_WAYLAND_DEPS_MISSING" >&2
                    exit 1
                  fi
                elif ! ls /tmp/.X11-unix/X* >/dev/null 2>&1; then
                  echo "SCREEN_NO_DISPLAY" >&2
                  exit 1
                fi
                ;;
              *)
                echo "SCREEN_UNSUPPORTED_OS:${'$'}OS" >&2
                exit 1
                ;;
            esac
            # relay version match: remote version file missing/inconsistent
            # → old relay (no support for the new protocol) → guide a reinstall (user feedback: the client script
            # should match the remote script version)
            if [ ! -f "${'$'}HOME/.macodes-screen.version" ] || [ "${'$'}(cat "${'$'}HOME/.macodes-screen.version" 2>/dev/null)" != "$RELAY_VERSION" ]; then
              echo "SCREEN_RELAY_OLD:have=${'$'}(cat "${'$'}HOME/.macodes-screen.version" 2>/dev/null || echo none) expect=$RELAY_VERSION" >&2
              exit 1
            fi
            TOKEN_FILE="${'$'}HOME/.macodes-screen.token"
            TOKEN="${'$'}(tr -d '\r\n' < "${'$'}TOKEN_FILE" 2>/dev/null || true)"
            if ! printf '%s' "${'$'}TOKEN" | grep -Eq '^[0-9a-fA-F]{64}${'$'}'; then
              echo "SCREEN_AUTH_MISSING" >&2
              exit 1
            fi
            # ffmpeg lookup: the PATH of a non-interactive SSH session is limited (no brew directory), so command -v often misses
            # a brew-installed ffmpeg → false FFMPEG_MISSING (v1.5.0 user feedback: prompted to install it although it was installed)
            FF=""
            for cand in ${'$'}(command -v ffmpeg 2>/dev/null) "${'$'}HOME/bin/ffmpeg" /opt/homebrew/bin/ffmpeg /usr/local/bin/ffmpeg /usr/bin/ffmpeg; do
              if [ -n "${'$'}cand" ] && [ -x "${'$'}cand" ]; then FF="${'$'}cand"; break; fi
            done
            if [ -z "${'$'}FF" ]; then echo "FFMPEG_MISSING" >&2; exit 1; fi
            if ! (lsof -nP -iTCP:${'$'}PORT -sTCP:LISTEN >/dev/null 2>&1 \
              || ss -ltn 2>/dev/null | grep -q ":${'$'}PORT "); then
              echo "SCREEN_SERVICE_MISSING" >&2; exit 1
            fi
            if ! (lsof -nP -iTCP:${'$'}((PORT + 2)) -sTCP:LISTEN >/dev/null 2>&1 \
              || ss -ltn 2>/dev/null | grep -q ":${'$'}((PORT + 2)) "); then
              echo "SCREEN_VIDEO_SERVICE_MISSING" >&2; exit 1
            fi
            # The display-state probe is advisory only and never blocks streaming; Linux likewise queries the current user's graphical session.
            if [ "${'$'}OS" = "Darwin" ]; then
              NATIVE="${'$'}HOME/Library/Application Support/macodes/Macodes Helper.app/Contents/MacOS/Macodes Helper"
            else
              NATIVE="${'$'}HOME/Library/Application Support/macodes/screen-service"
            fi
            BACKEND="${'$'}(cat "${'$'}HOME/Library/Application Support/macodes/screen-service.backend" 2>/dev/null)"
            if [ -x "${'$'}NATIVE" ] && { [ "${'$'}BACKEND" = "rust" ] || [ "${'$'}BACKEND" = "python-macos" ]; }; then
              "${'$'}NATIVE" --display-state 2>&1 | grep -E 'SCREEN_(ASLEEP|LOCKED)' >&2 || true
            fi
            # Stream-parameter readback (the client syncs the quality-tier display; conf may hold an old value written by another device)
            CFG="${'$'}HOME/.macodes-screen.conf"
            [ -f "${'$'}CFG" ] && grep -E '^(fps|scale)=' "${'$'}CFG" | sed 's/^fps=/SCREEN_CFG_FPS:/;s/^scale=/SCREEN_CFG_SCALE:/' || true
            # The video stream goes over SSH direct-tcpip: only the relay's remote loopback TCP port is reported back,
            # so the client neither needs nor tries to reach a public port. The token only travels over authenticated SSH stdout
            # and is never logged; once direct-tcpip is connected it is used for the handshake first.
            echo "SCREEN_TCP_PORT:${'$'}((PORT + 2))"
            echo "SCREEN_AUTH_TOKEN:${'$'}TOKEN"
            """.trimIndent()

        /**
         * Streaming Service Installation Script (Idempotent, Remote Execution): Check for/install FFmpeg (via Homebrew or static package)
         * (to ~/bin) → Verify the Rust executable file uploaded from your phone
         * → Register the graphical session service → Verify that the port is a listener.
         * Both the macOS graphical login session and screen recording/accessibility authorization must be granted.
         */
        val INSTALL_SCRIPT: String
            get() = ScreenServiceAssets.INSTALL_SCRIPT

        /**
         * Parse remote streaming parameters from the stream script's stdout (rows containing SCREEN_CFG_FPS / SCREEN_CFG_SCALE;
         * Missing entries are set to null—client local defaults are not overridden when `conf` is missing.
         */
        internal fun parseStreamCfg(stdout: String): Pair<Int?, String?> {
            var fps: Int? = null
            var scale: String? = null
            stdout.lineSequence().forEach { line ->
                when {
                    line.startsWith("SCREEN_CFG_FPS:") ->
                        line
                            .substringAfter(":")
                            .trim()
                            .toIntOrNull()
                            ?.let { fps = it }

                    line.startsWith("SCREEN_CFG_SCALE:") ->
                        line
                            .substringAfter(":")
                            .trim()
                            .takeIf { it.isNotEmpty() }
                            ?.let { scale = it }
                }
            }
            return Pair(fps, scale)
        }

        /** Extract the relay bearer token from the output of an authenticated SSH probe; reject any output that contains an exception in the format. */
        internal fun parseAuthToken(stdout: String): String? =
            stdout
                .lineSequence()
                .firstOrNull { it.startsWith("SCREEN_AUTH_TOKEN:") }
                ?.substringAfter(":")
                ?.trim()
                ?.takeIf { token -> token.length == 64 && token.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } }

        /** "scale" character → image quality level index (Smooth/SD/HD/Original; if unknown, use SD). */
        internal fun qualityIndexFor(scale: String): Int =
            when (scale) {
                "960:-2" -> 0
                "1920:-2" -> 2
                "native", "2560:-2" -> 3
                else -> 1
            }

        internal fun scaleForQuality(index: Int): String =
            when (index) {
                0 -> "960:-2"
                2 -> "1920:-2"
                3 -> "native"
                else -> "1280:-2"
            }

        /**
         * Test streaming script: lavfi test video source (does not depend on screen recording permissions).
         * Use it in integration tests to regression test the “exec raw channel + stderr coroutine + NAL parsing” pipeline.
         */
        val LAVFI_SCRIPT =
            """
            FF=${'$'}(command -v ffmpeg 2>/dev/null || echo "${'$'}HOME/bin/ffmpeg")
            if [ ! -x "${'$'}FF" ]; then echo "FFMPEG_MISSING" >&2; exit 1; fi
            exec "${'$'}FF" -hide_banner -loglevel error -f lavfi -i testsrc=size=640x360:rate=30 \
              -c:v libx264 -preset ultrafast -tune zerolatency -pix_fmt yuv420p -g 15 \
              -x264opts keyint=15:aud=1 -f h264 -flush_packets 1 -
            """.trimIndent()
    }
}

internal fun screenShellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

// SSH exec uses the account to perform the shell login by default; unmatched glob patterns in zsh will interrupt POSIX installation scripts.
// Explicitly perform a selection on `sh`, reserve `stdin` for `sudo`, and place both error output and progress in the same stream.
internal fun screenInstallCommand(script: String): String = "exec /bin/sh -c ${screenShellQuote(script)} 2>&1"
