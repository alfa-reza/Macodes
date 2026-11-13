package dev.termish.screen

import dev.termish.mosh.MoshUdpSocket
import dev.termish.util.TermLog
import dev.termish.util.ioDispatcher
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock

/** Heartbeat packet magic (phone → relay): after the relay identifies the client, it updates the client's address and does not forward to ffmpeg. */
val SCREEN_HEARTBEAT_MAGIC = byteArrayOf(0x54, 0x48, 0x42, 0x01) // "THB\x01"

/**
 * Reload packet magic (first packet of a new session): when the relay receives it, restart ffmpeg (re-reads the streaming parameters).
 * Path for toggling image quality/frame rate—otherwise, the old FFmpeg instance will never restart under roaming semantics,
 * Even if you write the `conf`, you won't be able to read it yourself (the UDP version no longer automatically switches to a new ffmpeg instance on each connection, unlike the old TCP version).
 */
val SCREEN_RELOAD_MAGIC = byteArrayOf(0x54, 0x48, 0x42, 0x02) // "THB\x02"

/** Control packet magic (phone → relay): remote operation (touch → mouse/scroll wheel). "THC1" */
const val SCREEN_CONTROL_MAGIC = "THC1"

/** Status packet magic (relay → phone): "THS1" + 1 byte status (0 = permission OK, 1 = missing accessibility permission). */
const val SCREEN_STATUS_MAGIC = "THS1"

/** Remote-operation control packet: type + normalized coordinates (0-1) + delta. */
object ScreenControlPacket {
    const val TYPE_MOVE = 0
    const val TYPE_DOWN = 1
    const val TYPE_UP = 2
    const val TYPE_SCROLL = 3

    /** Text input (on-screen soft keyboard characters/Chinese IME): payload = UTF-8 characters. */
    const val TYPE_TEXT = 4

    /** Key codes (special toolbar keys/composition keys): extra = macOS virtual key codes, x = modifier mask. */
    const val TYPE_KEY = 5

    /** Right-click down/up (virtual right-click area). */
    const val TYPE_RIGHT_DOWN = 6
    const val TYPE_RIGHT_UP = 7

    /** Virtual mouse atomic tap: Tap at the target location and then resume the physical mouse position on the Mac. */
    const val TYPE_CLICK = 8
    const val TYPE_RIGHT_CLICK = 9

    /** Virtual mouse long press and drag: A dedicated sequence allows Relay to use the platform’s native drag events and resume control of the physical mouse after the operation is complete. */
    const val TYPE_VIRTUAL_LEFT_DOWN = 10
    const val TYPE_VIRTUAL_LEFT_DRAG = 11
    const val TYPE_VIRTUAL_LEFT_UP = 12

    /** TCP owner lease heartbeat; relay only updates the time and does not perform any local input injection. */
    const val TYPE_HEARTBEAT = 13

    /** Modifier mask (the x field of TYPE_KEY). */
    const val MOD_COMMAND = 1
    const val MOD_SHIFT = 2
    const val MOD_CONTROL = 4
    const val MOD_OPTION = 8

    /** macOS virtual key codes (Carbon kVK, used by CGEvent relay). */
    object KeyCode {
        const val C = 8
        const val V = 9
        const val Q = 12
        const val RETURN = 36
        const val TAB = 48
        const val SPACE = 49
        const val BACKSPACE = 51
        const val ESC = 53
        const val CAPS = 57
        const val LEFT = 123
        const val RIGHT = 124
        const val DOWN = 125
        const val UP = 126
    }

    private fun f4(v: Float): ByteArray {
        val b = v.toRawBits()
        return byteArrayOf((b shr 24).toByte(), (b shr 16).toByte(), (b shr 8).toByte(), b.toByte())
    }

    private fun i4(v: Int): ByteArray = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    /** 17B: magic(4) + type(1) + x f32(4) + y f32(4) + extra i32(4). */
    fun encode(
        type: Int,
        x: Float,
        y: Float,
        extra: Int = 0,
    ): ByteArray =
        SCREEN_CONTROL_MAGIC.encodeToByteArray() +
            byteArrayOf(type.toByte()) +
            f4(x.coerceIn(0f, 1f)) +
            f4(y.coerceIn(0f, 1f)) +
            i4(extra)

    /** Text-key packet: magic + type(4) + UTF-8 characters (no coordinates; x/y are 0). */
    fun encodeText(text: String): ByteArray =
        SCREEN_CONTROL_MAGIC.encodeToByteArray() +
            byteArrayOf(TYPE_TEXT.toByte()) +
            f4(0f) +
            f4(0f) +
            i4(0) +
            text.encodeToByteArray()

    /** Key codes and key sets: extra = macOS virtual key codes, x = modifier mask (float bit field). */
    fun encodeKey(
        keyCode: Int,
        mods: Int,
    ): ByteArray =
        SCREEN_CONTROL_MAGIC.encodeToByteArray() +
            byteArrayOf(TYPE_KEY.toByte()) +
            f4(mods.toFloat()) +
            f4(0f) +
            i4(keyCode)
}

/** Status packet parsing: returns the status value (see [SCREEN_STATUS_MAGIC]); returns null for non-status packets. */
fun parseScreenStatus(data: ByteArray): Int? {
    if (data.size < 5 || !data.copyOfRange(0, 4).decodeToString().startsWith(SCREEN_STATUS_MAGIC)) return null
    return data[4].toInt()
}

/**
 * Heartbeat carrying frame-loss feedback: THB\x01 + 1 byte frame-loss rate (percent, 0-100).
 * The relay adapts its send rate accordingly (high frame loss → slow down → receive buffer not saturated → frame-loss rate drops),
 * Break the vicious cycle of “a high-bitrate data stream continuously performing Wi-Fi buffering → continuous frame drops.”
 */
fun heartbeatWithLoss(lossPercent: Int): ByteArray = byteArrayOf(0x54, 0x48, 0x42, 0x01, lossPercent.coerceIn(0, 100).toByte())

/**
 * UDP roaming sessions for screen streaming (Solution A): Move the video stream from SSH/TCP to UDP,
 * Gain Mosh-style roaming capabilities—don't show 'disconnected' when offline, resume transmission when the network recovers.
 *
 * Three Elements of Roaming (corresponding to mosh client semantics):
 * 1. **Heartbeat**: Send a magic packet every second to keep the relay informed of the phone’s latest address and maintain the NAT mapping.
 * 2. **Port Rotation**: If no video packets are received within 10 seconds, switch to a new source port and "open a hole" again.
 *   (NAT mapping for old ports may fail after losing network connectivity)
 * 3. **Never timeout**: once connected, never actively exit due to network loss; resume transmission as soon as the network recovers
 *
 * Relationship with [dev.termish.mosh.KmpMoshSession]: Reuses its [MoshUdpSocket] (UDP abstraction)
 * Unlike the format with fragmentation, video streams are unidirectional and do not require state sync; therefore, there is no transport/prediction layer here,
 * Received packets go directly into [ScreenStreamReceiver] for reassembly.
 *
 * Thread model: socket packet reception occurs in the `ioDispatcher` coroutine, while heartbeats run in a separate coroutine; the two coroutines pass data only through each other
 * @Volatile fields + socket interaction (unlike the single-threaded event loop in KmpMoshSession, video streams
 * No need to handle them in order; using multiple coroutines is simpler).
 */
class ScreenStreamUdpSession(
    private val ip: String,
    private val port: Int,
    private val scope: CoroutineScope,
    /** The reassembled video packet (decompressed bytes, fed to the player). */
    private val onVideoPacket: (ByteArray) -> Unit,
    /** Link health: The number of seconds since the last video packet was received; a callback is triggered only when the value changes. */
    private val onLinkStatus: (Int) -> Unit = {},
    /** Relay status packet callbacks (e.g., missing accessibility permission); callbacks only when values change. */
    private val onRelayStatus: (Int) -> Unit = {},
    /** Time source injection (for testing). */
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 1000L
        private const val PORT_HOP_INTERVAL_MS = 10_000L
    }

    private val receiver = ScreenStreamReceiver()

    @Volatile
    private var socket: MoshUdpSocket? = null

    private var receiveJob: Job? = null
    private var heartbeatJob: Job? = null

    @Volatile
    private var active = false

    @Volatile
    private var lastHeard = -1L

    private var lastHeartbeat = 0L
    private var lastHopAt = 0L
    private var lastReportedLostSecs = 0

    @Volatile private var lastReportedStatus = -1

    // Reassembly complete/dropped counters (feeds heartbeat frame-drop feedback; not printed — hot path)
    @Volatile private var fragOk = 0

    @Volatile private var fragDrop = 0

    /**
     * Count of fragments discarded due to slow local consumption (inbound queue full → discard old fragments).
     * Separate from network packet loss: When a local packet loss occurs during fragmentation, subsequent reassembly failures are attributed to local issues (decoding/handling of fragments
     * (can't keep up), do not feed AIMD—otherwise, decoding bottlenecks will falsely report network congestion, causing the relay to incorrectly throttle down to
     * 2MB/s floor, image quality collapse (remaining issue #1: false positives in frame-level substitution).
     */
    @Volatile private var localFragDrops = 0

    /** Network frame-loss rate over the most recent statistics window (percent; feeds the heartbeat feedback). */
    private fun recentLossPercent(): Int {
        val ok = fragOk
        val drop = fragDrop
        val total = ok + drop
        return if (total == 0) 0 else drop * 100 / total
    }

    /**
     * Send remote control commands (touch → mouse/scroll wheel). Send in real time via the same socket as the heartbeat:
     * No queuing, no retransmission (loss tolerance—the next frame’s event includes complete coordinates, which is part of the positional semantics).
     */
    fun sendControl(
        type: Int,
        x: Float,
        y: Float,
        extra: Int = 0,
    ) {
        val s = socket ?: return
        runCatching { s.send(ScreenControlPacket.encode(type, x, y, extra)) }
    }

    /** Send raw control packets (packets with payloads, such as text keys). */
    fun sendRaw(data: ByteArray) {
        val s = socket ?: return
        runCatching { s.send(data) }
    }

    /** On the first packet, send "reload" (restart the remote FFmpeg to re-read the streaming parameters; clear the flag after success). */
    @Volatile private var reloadPending = true

    private val sockLock = Mutex()

    fun start() {
        if (active) return
        active = true
        lastHopAt = nowMs()
        // Creating a socket must be done in an io coroutine: Parse DNS and connect during the MoshUdpSocket construction,
        // Calling this on the main thread will throw a NetworkOnMainThreadException (Android; the caller
        // ScreenSession's scope is a coroutine on the Compose main thread.
        scope.launch(ioDispatcher()) { openSocket() }
        launchHeartbeat()
    }

    private suspend fun openSocket() {
        sockLock.withLock {
            closeSocketLocked()
            if (!active) return
            val s = MoshUdpSocket(ip, port)
            socket = s
            // Two-stage reception/handling: the socket thread only handles receive + enqueue (a hot path that handles thousands of packets per second,
            // Move reassembly/unpacking/zlib to a separate coroutine—single-threaded serial processing will max out the socket when consumption is slow
            // Buffering: Kernel `RcvbufErrors`—tested to result in +80 packet losses per second)
            val inbound = Channel<ByteArray>(2048)
            scope.launch(ioDispatcher()) {
                try {
                    while (active && coroutineContext.isActive) {
                        val dg = s.receive(1000) ?: continue
                        lastHeard = nowMs()
                        // Discard old, keep new: When handling cannot keep up, discard old fragments (semi-reliable semantics; discarding a fragment = discarding one frame).
                        while (!inbound.trySend(dg.data).isSuccess) {
                            if (inbound.tryReceive().getOrNull() == null) break
                            // Slow local consumption: Count-based attribution; do not feed AIMD (see [localFragDrops])
                            localFragDrops++
                        }
                    }
                } catch (_: Exception) {
                    // Socket closed or error: The heartbeat coroutine will be rebuilt as needed
                }
            }
            receiveJob =
                scope.launch(ioDispatcher()) {
                    try {
                        while (active && coroutineContext.isActive) {
                            val data = withTimeoutOrNull(1_000) { inbound.receive() } ?: continue
                            // Relay state packet (THS1): Does not participate in fragmentation reassembly; passes through to callbacks
                            val status = parseScreenStatus(data)
                            if (status != null) {
                                if (status != lastReportedStatus) {
                                    lastReportedStatus = status
                                    onRelayStatus(status)
                                }
                                continue
                            }
                            val pkt = receiver.onDatagram(data)
                            if (pkt != null) {
                                fragOk++
                                onVideoPacket(pkt)
                            } else if (receiver.lastAbandoned) {
                                receiver.lastAbandoned = false
                                // Reassembly abandonment: if a local fragment was dropped recently (inbound full), attribute it to the local side;
                                // Slow consumption (decoding can’t keep up); otherwise, it’s network packet loss—feed AIMD
                                if (localFragDrops > 0) {
                                    localFragDrops--
                                } else {
                                    fragDrop++
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
        }
    }

    private fun launchHeartbeat() {
        heartbeatJob =
            scope.launch(ioDispatcher()) {
                while (active && coroutineContext.isActive) {
                    val now = nowMs()
                    // Heartbeat: Send a "magic" packet every second to keep the connection alive and update the address; the first packet is a "reload."
                    // (Restart the remote FFmpeg to re-read the config; see [SCREEN_RELOAD_MAGIC])
                    val s = socket
                    if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS && s != null) {
                        val pkt =
                            if (reloadPending) {
                                SCREEN_RELOAD_MAGIC
                            } else {
                                heartbeatWithLoss(recentLossPercent())
                            }
                        runCatching { s.send(pkt) }
                        reloadPending = false
                        lastHeartbeat = now
                    }
                    // Port rotation: if no video packet is received for 10s → switch source port and re-punch the hole (roaming core)
                    if (lastHeard != -1L &&
                        now - lastHeard >= PORT_HOP_INTERVAL_MS &&
                        now - lastHopAt >= PORT_HOP_INTERVAL_MS
                    ) {
                        TermLog.w("screen") { "UDP 无包 ${(now - lastHeard) / 1000}s，换源端口重打洞" }
                        openSocket()
                        lastHopAt = now
                    }
                    // Link Health Reporting (Callback Only When Values Change)
                    val lostSecs = if (lastHeard == -1L) 0 else ((now - lastHeard) / 1000).toInt()
                    if (lostSecs != lastReportedLostSecs) {
                        lastReportedLostSecs = lostSecs
                        onLinkStatus(lostSecs)
                    }
                    delay(250)
                }
            }
    }

    private fun closeSocketLocked() {
        receiveJob?.cancel()
        receiveJob = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    fun isActive(): Boolean = active

    fun close() {
        active = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        scope.launch(ioDispatcher()) {
            sockLock.withLock { closeSocketLocked() }
        }
    }
}
