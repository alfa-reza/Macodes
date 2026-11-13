package dev.termish.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.termish.util.TermLog
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.setActive
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDelegateProtocol
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionWebSocketCloseCodeNormalClosure
import platform.Foundation.NSURLSessionWebSocketDelegateProtocol
import platform.Foundation.NSURLSessionWebSocketMessage
import platform.Foundation.NSURLSessionWebSocketMessageTypeData
import platform.Foundation.NSURLSessionWebSocketTask
import platform.Foundation.create
import platform.darwin.NSInteger
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy

@Composable
actual fun rememberMicPermissionRequester(): MicPermissionRequester {
    // The iOS permission dialog is driven by `AVAudioSession.requestRecordPermission` (which can be called from any thread),
    // No involvement from the Compose side is required; a singleton is returned here.
    return remember { IosMicPermission }
}

private object IosMicPermission : MicPermissionRequester {
    override fun request(onResult: (Boolean) -> Unit) {
        AVAudioSession.sharedInstance().requestRecordPermission { granted ->
            // The callback thread is uncertain; consistently return to the main thread (UI state update).
            dispatch_async(dispatch_get_main_queue()) { onResult(granted) }
        }
    }
}

// ---- Audio recording: AVAudioEngine → hardware sample rate float32 → linearly resampled to 16 kHz → PCM16 ----

private const val SAMPLE_RATE = 16000
private const val FRAME_MS = 200
private const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000

/**
 * iOS audio recording: input node taps the hardware format (usually 48k/44.1k float32), linear-interpolation resampled to
 * 16 kHz, convert to PCM16 little-endian (Volcano ASR requires pcm_s16le); one callback block every 200 ms.
 */
@OptIn(ExperimentalForeignApi::class)
actual class MicrophoneRecorder actual constructor() {
    private var engine: AVAudioEngine? = null

    @Volatile private var running = false
    private val pending = ArrayList<Short>()

    actual fun start(
        onData: (ByteArray) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        if (running) return true
        val session = AVAudioSession.sharedInstance()
        // AVAudioSessionRecordPermissionGranted
        if (session.recordPermission() != 1uL) {
            onError("麦克风权限未授予")
            return false
        }
        val eng = AVAudioEngine()
        val input = eng.inputNode
        val hwFmt = input.inputFormatForBus(0u)
        val rate = hwFmt.sampleRate
        if (rate <= 0) {
            onError("麦克风不可用")
            return false
        }
        engine = eng
        running = true
        pending.clear()
        session.setCategory(AVAudioSessionCategoryRecord, null)
        session.setActive(true, null)
        input.installTapOnBus(0u, (FRAME_SAMPLES * 2).toUInt(), hwFmt) { pcm, _ ->
            val buf = pcm ?: return@installTapOnBus
            if (!running) return@installTapOnBus
            try {
                val frames = buf.frameLength.toInt()
                if (frames <= 0) return@installTapOnBus
                // floatChannelData: float** → CPointer<CPointer<FloatVar>?>; takes channel [0]
                val ch0 = buf.floatChannelData!![0]!!
                // Linear interpolation resampling: Input rate Hz → 16 kHz
                val step = rate / SAMPLE_RATE.toDouble()
                var outCount = (frames / step).toInt()
                if (outCount <= 0) return@installTapOnBus
                // Allow a small margin to prevent index out of bounds errors (cumulative floating-point error).
                if (outCount > frames) outCount = frames
                for (i in 0 until outCount) {
                    val srcPos = i * step
                    val i0 = srcPos.toInt()
                    val frac = srcPos - i0
                    val s0 = if (i0 < frames) ch0[i0].toDouble() else 0.0
                    val s1 = if (i0 + 1 < frames) ch0[i0 + 1].toDouble() else s0
                    val v = (s0 * (1 - frac) + s1 * frac) * 32767.0
                    pending.add(v.toInt().coerceIn(-32768, 32767).toShort())
                }
                // Callback in 200ms blocks
                while (pending.size >= FRAME_SAMPLES) {
                    val chunk = ShortArray(FRAME_SAMPLES)
                    for (i in 0 until FRAME_SAMPLES) chunk[i] = pending[i]
                    pending.subList(0, FRAME_SAMPLES).clear()
                    onData(shortToBytes(chunk))
                }
            } catch (e: Exception) {
                if (running) {
                    TermLog.w("voice") { "ios record error: $e" }
                    onError("录音失败")
                }
            }
        }
        eng.prepare()
        val ok = eng.startAndReturnError(null)
        if (!ok) {
            running = false
            engine = null
            input.removeTapOnBus(0u)
            onError("录音启动失败")
            return false
        }
        return true
    }

    actual fun stop() {
        running = false
        engine?.stop()
        engine?.inputNode?.removeTapOnBus(0u)
        engine = null
        pending.clear()
        try {
            AVAudioSession.sharedInstance().setActive(false, null)
        } catch (_: Exception) {
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun shortToBytes(shorts: ShortArray): ByteArray {
    val out = ByteArray(shorts.size * 2)
    for (i in shorts.indices) {
        val v = shorts[i].toInt()
        out[i * 2] = (v and 0xff).toByte()
        out[i * 2 + 1] = ((v ushr 8) and 0xff).toByte()
    }
    return out
}

// ---- WebSocket: NSURLSessionWebSocketTask ----

actual fun createVoiceWebSocket(): VoiceWebSocket = IosVoiceWebSocket()

private class IosVoiceWebSocket : VoiceWebSocket {
    private var task: NSURLSessionWebSocketTask? = null
    private var session: NSURLSession? = null

    override fun connect(
        url: String,
        headers: Map<String, String>,
        onOpen: () -> Unit,
        onMessage: (ByteArray) -> Unit,
        onError: (String) -> Unit,
        onClosed: () -> Unit,
    ) {
        val nsUrl =
            NSURL.URLWithString(url) ?: run {
                onError("URL 无效")
                return
            }
        val config = NSURLSessionConfiguration.defaultSessionConfiguration()
        config.timeoutIntervalForRequest = 10.0
        // WebSocket handshake authentication headers: The HTTPAdditionalHeaders configured in `session` are sent during the handshake
        config.HTTPAdditionalHeaders = headers.toMap() as Map<Any?, *>
        val delegate =
            object :
                NSObject(),
                NSURLSessionWebSocketDelegateProtocol,
                NSURLSessionDelegateProtocol {
                override fun URLSession(
                    session: NSURLSession,
                    webSocketTask: NSURLSessionWebSocketTask,
                    didOpenWithProtocol: String?,
                ) {
                    onOpen()
                }

                override fun URLSession(
                    session: NSURLSession,
                    webSocketTask: NSURLSessionWebSocketTask,
                    didCloseWithCode: NSInteger,
                    reason: NSData?,
                ) {
                    onClosed()
                }

                override fun URLSession(
                    session: NSURLSession,
                    task: NSURLSessionTask,
                    didCompleteWithError: NSError?,
                ) {
                    if (didCompleteWithError != null) {
                        onError(didCompleteWithError.localizedDescription ?: "网络错误")
                    }
                }
            }
        val s = NSURLSession.sessionWithConfiguration(config, delegate, null)
        session = s
        val req = NSMutableURLRequest(uRL = nsUrl)
        val t = s.webSocketTaskWithRequest(req)
        task = t
        t.resume()
        receiveLoop(t, onMessage)
    }

    private fun receiveLoop(
        t: NSURLSessionWebSocketTask,
        onMessage: (ByteArray) -> Unit,
    ) {
        t.receiveMessageWithCompletionHandler { message, error ->
            if (error != null || message == null) return@receiveMessageWithCompletionHandler
            if (message.type == NSURLSessionWebSocketMessageTypeData) {
                message.data?.let { data ->
                    onMessage(nsDataToBytes(data))
                }
            }
            receiveLoop(t, onMessage)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun nsDataToBytes(data: NSData): ByteArray {
        val len = data.length.toInt()
        if (len <= 0) return ByteArray(0)
        val out = ByteArray(len)
        data.bytes?.let { src ->
            out.usePinned { dst ->
                memcpy(dst.addressOf(0), src, len.toULong())
            }
        }
        return out
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun send(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val data =
            bytes.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
            }
        task?.sendMessage(NSURLSessionWebSocketMessage(data)) { _ -> }
    }

    override fun close() {
        task?.cancelWithCloseCode(NSURLSessionWebSocketCloseCodeNormalClosure, null)
        task = null
        session?.invalidateAndCancel()
        session = null
    }
}
