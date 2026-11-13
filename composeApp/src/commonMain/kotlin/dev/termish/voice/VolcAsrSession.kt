package dev.termish.voice

import dev.termish.util.TermLog
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Volcano Engine “Large Model Streaming Speech Recognition” (bigmodel_async) engine implementation (AsrEngine).
 * A complete state machine for a “press-and-speak” recognition session:
 * start() → Establish connection + full client request → Stream-based sendPcm() → finish() Send negative packet →
 * Receive the final package callback [onFinalText] (or [onError]).
 *
 * The callback originates from a WebSocket platform thread; the UI layer must switch to the main thread on its own.
 */
class VolcStreamingAsrEngine(
    private val apiKey: String,
    private val resourceId: String,
    private val ws: VoiceWebSocket,
) : AsrEngine {
    override var onState: ((AsrEngine.State) -> Unit)? = null
    override var onPartial: ((String) -> Unit)? = null
    override var onFinalText: ((String) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    @Volatile
    override var state: AsrEngine.State = AsrEngine.State.IDLE
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var finalTimeout: Job? = null

    override fun start() {
        if (state != AsrEngine.State.IDLE) return
        state = AsrEngine.State.CONNECTING
        onState?.invoke(state)
        ws.connect(
            url = VolcAsrProtocol.WSS_URL,
            headers =
                mapOf(
                    "X-Api-Key" to apiKey,
                    "X-Api-Resource-Id" to resourceId,
                    "X-Api-Request-Id" to uuid4(),
                    "X-Api-Sequence" to "-1",
                ),
            onOpen = {
                if (state == AsrEngine.State.ERROR || state == AsrEngine.State.DONE) return@connect
                state = AsrEngine.State.LISTENING
                onState?.invoke(state)
                // The first package after establishing a connection must be a full client request.
                val payload = VolcAsrProtocol.fullRequestJson(uuid4()).encodeToByteArray()
                ws.send(VolcAsrProtocol.frame(VolcAsrProtocol.fullRequestHeader(), payload))
            },
            onMessage = { bytes -> handleMessage(bytes) },
            onError = { msg -> fail(msg) },
            onClosed = {
                // Remain silent after normal termination (DONE/ERROR); report an error upon unexpected termination (e.g., network interruption).
                if (state != AsrEngine.State.DONE && state != AsrEngine.State.ERROR) fail("连接已关闭")
            },
        )
    }

    override fun sendPcm(data: ByteArray) {
        if (state != AsrEngine.State.LISTENING) return
        ws.send(VolcAsrProtocol.frame(VolcAsrProtocol.audioHeader(last = false), data))
    }

    override fun finish() {
        if (state != AsrEngine.State.LISTENING) return
        state = AsrEngine.State.FINALIZING
        onState?.invoke(state)
        ws.send(VolcAsrProtocol.frame(VolcAsrProtocol.audioHeader(last = true), ByteArray(0)))
        finalTimeout =
            scope.launch {
                delay(FINAL_TIMEOUT_MS)
                if (state == AsrEngine.State.FINALIZING) fail("识别超时")
            }
    }

    override fun abort() {
        ws.close()
        finalTimeout?.cancel()
        state = AsrEngine.State.DONE
    }

    private fun handleMessage(bytes: ByteArray) {
        if (bytes.size < 8) return
        when (VolcAsrProtocol.messageType(bytes)) {
            0x9 -> {
                val parsed =
                    try {
                        VolcAsrProtocol.parseServerFrame(bytes)
                    } catch (e: Exception) {
                        TermLog.w("voice") { "asr response parse failed: $e" }
                        fail("响应解析失败")
                        return
                    }
                val resp = parsed.response
                if (resp.code != null && resp.code != 0) {
                    fail(resp.payloadMsg?.message ?: "服务错误 code=${resp.code}")
                    return
                }
                // The final package tag comes from frame flags=3 (as tested, the bigmodel_async response body does not contain
                // (`is_last_package` field); response body fields are provided solely as a fallback for legacy interfaces.
                if (parsed.isLastPackage) {
                    val text = VolcAsrProtocol.finalText(resp)
                    finalTimeout?.cancel()
                    if (text != null) {
                        state = AsrEngine.State.DONE
                        onState?.invoke(state)
                        ws.close()
                        onFinalText?.invoke(text)
                    } else {
                        fail("未识别到语音")
                    }
                } else {
                    // Intermediate result: Incremental and full-text updates, displayed in real-time (replacement-based updates, not appended)
                    VolcAsrProtocol.finalText(resp)?.let { text ->
                        onPartial?.invoke(text)
                    }
                }
            }
            0xF -> fail(VolcAsrProtocol.parseErrorFrame(bytes))
        }
    }

    private fun fail(message: String) {
        if (state == AsrEngine.State.DONE || state == AsrEngine.State.ERROR) return
        finalTimeout?.cancel()
        state = AsrEngine.State.ERROR
        TermLog.w("voice") { "asr error: $message" }
        onState?.invoke(state)
        ws.close()
        onError?.invoke(message)
    }

    private companion object {
        const val FINAL_TIMEOUT_MS = 8_000L
    }
}

/** UUID v4 (without any platform dependencies; used with the X-Api-Request-Id protocol header and uid). */
internal fun uuid4(): String {
    val bytes = ByteArray(16) { Random.nextBytes(1)[0] }
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = "0123456789abcdef"
    val sb = StringBuilder(36)
    for (i in bytes.indices) {
        val b = bytes[i].toInt() and 0xff
        sb.append(hex[b ushr 4]).append(hex[b and 0xf])
        if (i == 3 || i == 5 || i == 7 || i == 9) sb.append('-')
    }
    return sb.toString()
}
