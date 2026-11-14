package dev.macodes.mosh

import dev.macodes.util.TermLog
import dev.macodes.util.ioDispatcher
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Reasons for mosh session termination. The protocol layer does not generate UI text (maintaining zero UI dependencies); the UI layer translates accordingly. */
enum class MoshExitReason {
    /** Session event loop coroutine terminated with an exception (see TermLog for underlying messages). */
    SESSION_ERROR,

    /** "still_connecting" phase: No first packet received from the other end after 15 seconds (e.g., UDP port unreachable). */
    CONNECT_TIMEOUT,

    /** Normal shutdown (remote ACK shutdown / local confirmation of remote shutdown / shutdown timeout). */
    NORMAL,
}

/**
 * The Mosh client session implementation is entirely in Kotlin (SSP protocol, no dependency on the native mosh-client).
 *
 * Thread model: `transport` is not thread-safe; all access must be confined to event-loop coroutines.
 * Input, resize, and close events on the UI thread, as well as incoming socket packets, are all routed through the [events] channel,
 * The packet-receiving coroutine is only responsible for blocking on the socket read and delivering the data, ensuring that the input wakes up immediately (without waiting for a `receive` timeout).
 *
 * Rendering model: A shadow terminal that directly maintains server-side state; when the state advances, the shadow buffer
 * Sync content to the UI buffer ([onStateUpdate]); do not use the ANSI byte stream.
 */
@Suppress("ktlint:standard:property-naming") // mosh port rotation parameters
class KmpMoshSession(
    ip: String,
    port: Int,
    key: String,
    columns: Int,
    rows: Int,
    private val scope: CoroutineScope,
    /** Latest shadow state (callback in a session coroutine; the implementation should copy it and then switch back to the main thread to render). */
    private val onStateUpdate: (ShadowTerminalView) -> Unit,
    private val onExit: (MoshExitReason) -> Unit,
    /** Callback once when the first valid packet is received from the other end (connection established; the `still_connecting` flag in the corresponding protocol is flipped). */
    private val onPeerConnected: () -> Unit = {},
    /** Link health: The number of seconds since the last packet was received from the other end; a callback is triggered only when the value changes (idle heartbeats occur approximately every 3 seconds).
     *  Corresponds to the protocol notification “Last contact Ns ago.” */
    private val onLinkStatus: (Int) -> Unit = {},
) {
    /** A shadow state view for the UI to read. */
    class ShadowTerminalView internal constructor(
        internal val shadow: ShadowTerminal,
    ) {
        // The fully qualified names are intentional: protocol-level rules prohibit `import dev.macodes.term` (see AGENTS.md),
        // However, the view must expose the shadow buffer type to the UI—fully qualified notation is the only acceptable way to do this.
        val buffer: dev.macodes.term.TerminalBuffer get() = shadow.buffer
        val echoAck: ULong get() = shadow.echoAck

        /** Shadow buffer read/write lock: acquire the lock before the UI copies; mutually excludes with writes from the session coroutine. */
        internal val lock: kotlinx.coroutines.sync.Mutex get() = shadow.lock
    }

    private sealed class Event {
        class Packet(
            val data: ByteArray,
            val congestionExperienced: Boolean,
        ) : Event()

        class Input(
            val data: ByteArray,
        ) : Event()

        class Resize(
            val cols: Int,
            val rows: Int,
        ) : Event()

        object Close : Event()
    }

    // ---- mosh Client Port Rotation ----
    private val PORT_HOP_INTERVAL = 10_000L
    private val MAX_OLD_SOCKET_AGE = 60_000L
    private val MAX_PORTS_OPEN = 10

    private val mark = TimeSource.Monotonic.markNow()

    private fun nowMs(): Long = mark.elapsedNow().inWholeMilliseconds

    private val initialCols = columns
    private val initialRows = rows
    private val remoteIp = ip
    private val remotePort = port

    private val sockets = ArrayDeque<MoshUdpSocket>().apply { addLast(MoshUdpSocket(remoteIp, remotePort)) }
    private var sendSocket = sockets.first()
    private val socketJobs = ArrayDeque<Job>()
    private var lastHopAt = 0L
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val prediction = PredictionLayer(::nowMs)

    private val transport =
        MoshTransport(
            // The MTU address family is determined based on the parsing result (mosh set_MTU(sa_family)): when a domain resolves to an AAAA record,
            // Using a hostname string to determine the MTU may result in an incorrect IPv4 MTU selection, and packet loss may occur on the tunnel/PPPoE path.
            ipv6Path = sockets.first().isIpv6,
            initialCols = columns,
            initialRows = rows,
            key = key,
            nowMs = ::nowMs,
            sendDatagram = { data ->
                try {
                    sendSocket.send(data)
                } catch (e: Exception) {
                    TermLog.w("mosh") { "UDP 发送失败: ${e.message}" }
                    SendResult.FAILED
                }
            },
            onNewState = { shadow ->
                // Confirmation state reached: echo_ack has acknowledged the input; survival prediction replays on the new confirmation state
                prediction.onConfirmed(shadow)
                pushToUi(prediction.currentForDisplay() ?: shadow)
            },
        )

    private fun pushToUi(shadow: ShadowTerminal) {
        shadow.onTitleChange = { t -> titleCallback(t) }
        shadow.onClipboardWrite = { s -> clipboardCallback(s) }
        onStateUpdate(ShadowTerminalView(shadow))
    }

    private var titleCallback: (String) -> Unit = {}
    private var clipboardCallback: (String) -> Unit = {}
    private var loopJob: Job? = null

    /** Read and write operations are performed only by the event thread; after `close`, the event thread sets the value to `false`. Cross-thread reads (`close`/UI) use `@Volatile`. */
    @Volatile
    private var active = true

    @Volatile
    private var peerSeen = false
    private var startedAt = 0L
    private var lastReportedLostSecs = 0

    fun setTitleCallback(cb: (String) -> Unit) {
        titleCallback = cb
    }

    fun setClipboardCallback(cb: (String) -> Unit) {
        clipboardCallback = cb
    }

    fun start() {
        startedAt = nowMs()
        transport.setSendDelay(1) // mosh client: Send keystrokes as quickly as possible (the default 8 ms is server-side semantics)
        // The initial window size is reported with the first frame (initial resize is queued).
        events.trySend(Event.Resize(initialCols, initialRows))
        launchReader(sendSocket)
        loopJob =
            scope.launch(Dispatchers.Default) {
                try {
                    loop()
                } catch (e: Exception) {
                    if (active) {
                        active = false
                        TermLog.e("mosh") { "会话异常: ${e.stackTraceToString()}" }
                        onExit(MoshExitReason.SESSION_ERROR)
                    }
                }
            }
    }

    /** One packet-receiving coroutine per socket: blocking on read; after delivery, the event loop handles it uniformly. */
    private fun launchReader(s: MoshUdpSocket) {
        val job =
            scope.launch(ioDispatcher()) {
                try {
                    // Cancel check: `receive` returns `null` on timeout after `close`; if this check is removed,
                    // the coroutine idles for up to 60 seconds after the session closes (coroutineContext.isActive:
                    // (Avoid naming conflicts with the isActive() member function in this class.)
                    while (coroutineContext.isActive) {
                        val dg = s.receive(60_000) ?: continue
                        events.send(Event.Packet(dg.data, dg.congestionExperienced))
                    }
                } catch (_: Exception) {
                    // Socket closed or error: The event loop exits via a timer/heartbeat; no additional notification is required.
                }
            }
        socketJobs.addLast(job)
    }

    private suspend fun loop() {
        while (active) {
            val waitMs =
                transport.waitTime().let {
                    when {
                        it == Int.MAX_VALUE -> 1000L // Wake up periodically to trigger a heartbeat when no timer is required
                        it <= 0 -> 0L
                        else -> it.coerceAtMost(1000).toLong()
                    }
                }
            // Do not sleep when send/ack has expired (waitMs==0): withTimeoutOrNull(1) in a coroutine
            // The next scheduling will be rounded to the nearest few milliseconds; don't wait (mosh select(0) returns immediately).
            val ev =
                if (waitMs <= 0L) {
                    events.tryReceive().getOrNull()
                } else {
                    withTimeoutOrNull(waitMs) { events.receive() }
                }
            when (ev) {
                is Event.Packet -> {
                    val pkt = transport.decryptDatagram(ev.data)
                    if (pkt != null) {
                        transport.processPacket(pkt, ev.congestionExperienced)
                        if (!peerSeen) {
                            peerSeen = true
                            // First valid packet = connection established (prior to this, the UI remained at “Connecting”)
                            onPeerConnected()
                        }
                    }
                }
                is Event.Input -> {
                    transport.pushBytes(ev.data)
                    // Local prediction echo: When RTT is high, key presses are first rendered on the confirmation state branch,
                    // echo_ack: incorporated after confirmation—a simplified version of the prediction engine
                    if (prediction.onUserInput(ev.data, transport.sendIntervalMs(), transport.lastSentNum())) {
                        prediction.currentForDisplay()?.let { pushToUi(it) }
                    }
                }
                is Event.Resize -> {
                    transport.pushResize(ev.cols, ev.rows)
                    prediction.reset() // Geometric failure; awaiting remote confirmation (mosh process_resize → reset)
                }
                is Event.Close -> transport.startShutdown()
                null -> {} // Timeout, tick
            }
            transport.tick()
            maybeHopPort()

            // Prediction indicates that the request will be pending for too long (the server has not confirmed or there was output in the meantime): Discard and return to the confirmed state
            if (prediction.glitchTimedOut()) {
                prediction.dropPrediction()
                pushToUi(transport.latestRemote)
            }

            // mosh Roaming Core: Once there is a connection, the client will [never] actively timeout and exit
            // 15s shutdown applies only to `still_connecting` (never received a status from the other end). During network outages, rely on
            // Transport retry (within the ACTIVE_RETRY_TIMEOUT window) + port rotation to maintain connectivity; network resumption
            // (WiFi ↔ cellular toggling, sleep/wake up, tunneling) means seamless resume; the server also never experiences a timeout.
            // (The 60s in mosh-server applies only when no client has ever connected.)

            // Link health reporting (simplified version of mosh NotificationEngine "Last contact Ns ago"):
            // In idle mode, loop ≤1s and wake up once; callback only triggered when the count changes; both sides’ heartbeats are approximately 3s, with a normal range of 0–3.
            if (peerSeen) {
                val lostSecs = ((nowMs() - transport.lastHeardMs()) / 1000).toInt()
                if (lostSecs != lastReportedLostSecs) {
                    lastReportedLostSecs = lostSecs
                    onLinkStatus(lostSecs)
                }
            }

            // If there is no response from the other end within 15 seconds, the connection is considered a failure (e.g., UDP port unreachable; applies only when a connection cannot be established).
            if (!peerSeen && nowMs() - startedAt > 15_000) {
                active = false
                cleanup()
                onExit(MoshExitReason.CONNECT_TIMEOUT)
                return
            }
            // Normal shutdown path: The other end has acknowledged our shutdown, or we have confirmed the other end’s shutdown—
            // All are considered clean exits (SSP shutdown handshake semantics: shutdown_acknowledged /
            // counterparty_shutdown_ack_sent)
            if ((transport.shutdownInProgress && transport.shutdownAcknowledged()) ||
                transport.counterpartyShutdownAckSent()
            ) {
                active = false
                cleanup()
                onExit(MoshExitReason.NORMAL)
                return
            }
            if (transport.shutdownAckTimedOut()) {
                active = false
                cleanup()
                onExit(MoshExitReason.NORMAL)
                return
            }
        }
    }

    /**
     * Client port rotation for mosh Connection::send:
     * If no acknowledgment is received from the peer within 10 seconds and the source port has not changed within 10 seconds → create a new socket; the old socket continues to receive packets,
     * Clear old sockets (prune_sockets) after the new port has been stable for 60 seconds; allow a maximum of 10 open sockets simultaneously.
     */
    private fun maybeHopPort() {
        val now = nowMs()
        // First, filter out: If the connection remains stable for more than 60 seconds after the last hop, it indicates that the link has resumed; only keep the latest port.
        if (sockets.size > 1 && now - lastHopAt >= MAX_OLD_SOCKET_AGE) {
            while (sockets.size > 1) {
                val old = sockets.removeFirst()
                socketJobs.removeFirst()?.cancel()
                try {
                    old.close()
                } catch (_: Exception) {
                }
            }
        }
        while (sockets.size > MAX_PORTS_OPEN) {
            val old = sockets.removeFirst()
            socketJobs.removeFirst()?.cancel()
            try {
                old.close()
            } catch (_: Exception) {
            }
        }

        if (transport.shutdownInProgress) return
        if (now - lastHopAt < PORT_HOP_INTERVAL) return
        if (now - transport.sentStateAckedTimestamp() < PORT_HOP_INTERVAL) return

        val next = MoshUdpSocket(remoteIp, remotePort)
        sockets.addLast(next)
        launchReader(next)
        sendSocket = next
        lastHopAt = now
    }

    private fun cleanup() {
        for (s in sockets) {
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
        for (j in socketJobs) {
            j.cancel()
        }
    }

    fun isActive(): Boolean = active

    fun resize(
        columns: Int,
        rows: Int,
    ) {
        if (active) events.trySend(Event.Resize(columns, rows))
    }

    fun sendData(data: ByteArray) {
        if (active) events.trySend(Event.Input(data))
    }

    fun close() {
        if (!active) return
        if (!peerSeen) {
            // Never received any data packets from the other end: no handshake occurred; the connection terminated immediately.
            active = false
            cleanup()
            onExit(MoshExitReason.NORMAL)
            return
        }
        events.trySend(Event.Close)
    }
}
