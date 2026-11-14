package dev.macodes.voice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Volcano Engine “Large Model Streaming Speech Recognition API” binary WebSocket protocol (pure Kotlin, zero platform dependencies).
 *
 * Documentation: https://docs.volcengine.com/docs/6561/2630027 (bidirectional streaming speech recognition WebSocket, bigmodel_async)
 *
 * Frame format (integers are always big-endian):
 *   - Client request: Header (4B) + Payload size (4B) + Payload
 *   - Service response: Header (4B) + Sequence (4B) + Payload size (4B) + Payload
 *
 * Header single-byte bit fields:
 *   byte0: Protocol version (4b) = 0001 | Header size (4b) = 0001 (actual header byte count = value × 4)
 *   byte1: Message type(4b) | Message type specific flags(4b)
 *   byte2: Serialization(4b) | Compression(4b)
 *   byte3: Reserved(8b)
 *
 * Message type: 0001 = full client request; 0010 = audio only request;
 *             1001 = full server response; 1111 = server error.
 * flags: 0000 = regular package; 0010 = last package (negative package).
 */
object VolcAsrProtocol {
    private val responseJson = Json { ignoreUnknownKeys = true }

    /** Bidirectional streaming (optimized version) interface address. */
    const val WSS_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"

    /** Default resource ID: Doubao Streaming Speech Recognition Model 2.0 Hourly Edition (recommended by the new console). */
    const val DEFAULT_RESOURCE_ID = "volc.seedasr.sauc.duration"

    /** All available resource IDs (selection from the settings page). */
    val RESOURCE_IDS =
        listOf(
            "volc.seedasr.sauc.duration",
            "volc.seedasr.sauc.concurrent",
            "volc.bigasr.sauc.duration",
            "volc.bigasr.sauc.concurrent",
        )

    // ---- header bytes ----

    /** Full client request: JSON-serialized, uncompressed. */
    fun fullRequestHeader(): ByteArray = byteArrayOf(0x11, 0x10, 0x10, 0x00)

    /** Audio-only request; [last] = the last packet (negative packet, flags=0010). */
    fun audioHeader(last: Boolean): ByteArray = if (last) byteArrayOf(0x11, 0x22, 0x00, 0x00) else byteArrayOf(0x11, 0x20, 0x00, 0x00)

    /** Frame structure: header + big-endian payload size + payload. */
    fun frame(
        header: ByteArray,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArray(8 + payload.size)
        header.copyInto(out, 0)
        val n = payload.size
        out[4] = (n ushr 24).toByte()
        out[5] = (n ushr 16).toByte()
        out[6] = (n ushr 8).toByte()
        out[7] = n.toByte()
        payload.copyInto(out, 8)
        return out
    }

    /**
     * Full client request JSON payload: audio metadata + request parameters.
     * Unify audio to pcm_s16le / 16 kHz / mono (ensure this format is used during recording).
     */
    fun fullRequestJson(uid: String): String {
        val params =
            buildJsonObject {
                // enable_punc defaults to true (punctuation makes commands/text more readable); enable_itn defaults to true
                put("model_name", "bigmodel")
                put("enable_punc", true)
                put("enable_itn", true)
            }
        return buildString {
            append("{\"user\":{\"uid\":\"")
            append(uid)
            append("\"},\"audio\":{\"format\":\"pcm\",\"rate\":16000,\"bits\":16,\"channel\":1},\"request\":")
            append(params.toString())
            append('}')
        }
    }

    // ---- Response Parsing ----

    /** Message type (the upper 4 bits of byte1). */
    fun messageType(bytes: ByteArray): Int = (bytes[1].toInt() ushr 4) and 0xF

    private fun be32(
        bytes: ByteArray,
        offset: Int,
    ): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)

    @Serializable
    data class AsrResponse(
        /** Field is missing on success (tested: `bigmodel_async` has no code); a non-zero value indicates an error. */
        val code: Int? = null,
        val event: Int? = null,
        @SerialName("is_last_package") val isLastPackage: Boolean = false,
        @SerialName("payload_sequence") val payloadSequence: Int = 0,
        @SerialName("audio_info") val audioInfo: JsonElement? = null,
        /**
         * Tested with bigmodel_async: the result is a **top-level object** {text, utterances, additions};
         * The old interface (bigmodel) is in `payload_msg.result` (list)—both are compatible.
         */
        val result: JsonElement? = null,
        @SerialName("payload_msg") val payloadMsg: AsrPayloadMsg? = null,
    )

    @Serializable
    data class AsrPayloadMsg(
        val message: String? = null,
        /** List of clauses in the legacy interface (bigmodel); JsonElement supports both object and list structures. */
        val result: JsonElement? = null,
    )

    /** Parse result: Final package marker (frame flags=3 or response is_last_package). */
    data class ParsedServerFrame(
        val isLastPackage: Boolean,
        val response: AsrResponse,
    )

    /**
     * Parse the complete service frame (type=1001).
     * Frame layout: Header (4B) + [Sequence (4B)] + Payload size (4B) + Payload.
     * Whether "sequence" appears is determined by the header flags: flags=0 means no "sequence" (first response),
     * flags=1/3 includes sequence (subsequent packages) — tested: bigmodel_async sends both.
     * The final packet frame has flags=0x3 (byte1=0x93), and the response body does not contain the `is_last_package` field.
     */
    fun parseServerFrame(bytes: ByteArray): ParsedServerFrame {
        val flags = bytes[1].toInt() and 0x0f
        val hasSequence = flags == 1 || flags == 3
        val sizeOffset = if (hasSequence) 8 else 4
        val payloadOffset = sizeOffset + 4
        val size = be32(bytes, sizeOffset).toInt().coerceAtMost(bytes.size - payloadOffset)
        val jsonStr = bytes.decodeToString(payloadOffset, payloadOffset + size)
        val resp = responseJson.decodeFromString<AsrResponse>(jsonStr)
        return ParsedServerFrame(isLastPackage = flags == 3 || resp.isLastPackage, response = resp)
    }

    /**
     * Extract the final text from the recognition response.
     * Tested structure: The top-level `result` is an object `{text: "..."}` (`bigmodel_async`); the old interface is
     * payload_msg.result clause list—both are compatible (the list concatenates all elements in order).
     */
    fun finalText(resp: AsrResponse): String? {
        val result = resp.result ?: resp.payloadMsg?.result ?: return null
        val text =
            when (result) {
                is JsonObject -> result["text"]?.jsonPrimitive?.contentOrNull
                is JsonArray ->
                    result
                        .mapNotNull { el ->
                            (el as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                        }.joinToString("")
                else -> null
            }
        return text?.takeIf { it.isNotBlank() }
    }

    /**
     * Parse the server-side error frame (type=1111) into readable text.
     * Frame layout: Header (4B) + Error code (4B) + Error size (4B) + Error message (UTF-8);
     * Some implementations use code + payload; both shapes are tolerated here.
     */
    fun parseErrorFrame(bytes: ByteArray): String {
        val code = be32(bytes, 4)
        var message = ""
        if (bytes.size >= 12) {
            val size = be32(bytes, 8).toInt()
            if (size in 1..(bytes.size - 12)) {
                message = bytes.decodeToString(12, 12 + size)
            } else {
                message = bytes.decodeToString(8, bytes.size)
            }
        }
        return if (message.isBlank()) "server error code=$code" else "$message (code=$code)"
    }
}
