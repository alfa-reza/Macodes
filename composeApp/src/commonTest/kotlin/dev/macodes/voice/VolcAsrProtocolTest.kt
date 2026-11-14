package dev.macodes.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Response structure based on actual testing with Volcano’s bigmodel_async (verified on August 21, 2026):
 * - Response frame: Header (4B) + [Sequence (4B)] + Size (4B) + Payload; the first packet has no sequence (flags=0)
 * - Final packet frame flags=0x3 (byte1=0x93); the response body contains no "code" or "is_last_package" fields
 * - `payload_msg.result` is an **object** of type {text, utterances, additions}
 */
class VolcAsrProtocolTest {
    private fun be32(v: Int): ByteArray =
        byteArrayOf(
            (v ushr 24).toByte(),
            (v ushr 16).toByte(),
            (v ushr 8).toByte(),
            v.toByte(),
        )

    /** Constructing server-side frames: flags control the sequence segment and the final packet header. */
    private fun serverFrame(
        flags: Int,
        sequence: Int?,
        payload: String,
    ): ByteArray {
        val bytes = ByteArray(4) { 0 }
        bytes[0] = 0x11
        bytes[1] = ((0x9 shl 4) or flags).toByte()
        bytes[2] = 0x10 // JSON, no compression
        val body =
            if (sequence !=
                null
            ) {
                be32(sequence) + be32(payload.toByteArray().size)
            } else {
                be32(payload.toByteArray().size)
            }
        return bytes + body + payload.encodeToByteArray()
    }

    /** Tested bigmodel_async response: `result` is a top-level object (not wrapped in `payload_msg`). */
    private fun realPayload(
        text: String,
        definite: Boolean = false,
        prefetch: Boolean = false,
    ): String =
        """
        {"audio_info":{"duration":4889},"result":{"additions":{"log_id":"x"},"prefetch":$prefetch,
        "text":"$text","utterances":[{"additions":{"fixed_prefix_result":"","source":"stream"},
        "definite":$definite,"end_time":4660,"start_time":40,"text":"$text","words":[]}]}}
        """.trimIndent()

    @Test
    fun `frame prepends big-endian payload size`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val f = VolcAsrProtocol.frame(VolcAsrProtocol.audioHeader(last = false), payload)
        assertEquals(8 + 5, f.size)
        assertEquals(0x11, f[0].toInt() and 0xff)
        assertEquals(0x20, f[1].toInt() and 0xff) // audio only request, flags=0
        assertEquals(0x00, f[2].toInt() and 0xff) // no serialization / no compression
        assertEquals(0, (f[4].toInt() and 0xff)) // size big-endian
        assertEquals(0, (f[5].toInt() and 0xff))
        assertEquals(0, (f[6].toInt() and 0xff))
        assertEquals(5, (f[7].toInt() and 0xff))
        assertTrue(f.copyOfRange(8, 13).contentEquals(payload))
    }

    @Test
    fun `last audio frame uses negative-packet flag`() {
        val f = VolcAsrProtocol.frame(VolcAsrProtocol.audioHeader(last = true), ByteArray(0))
        assertEquals(0x22, f[1].toInt() and 0xff) // msg_type=2, flags=2 (negative package)
        assertEquals(0, (f[7].toInt() and 0xff))
    }

    @Test
    fun `full request header is JSON serialization`() {
        val h = VolcAsrProtocol.fullRequestHeader()
        assertEquals(0x11, h[0].toInt() and 0xff)
        assertEquals(0x10, h[1].toInt() and 0xff) // full client request
        assertEquals(0x10, h[2].toInt() and 0xff) // JSON
    }

    @Test
    fun `full request json carries pcm 16k mono params`() {
        val json = VolcAsrProtocol.fullRequestJson("uid-1")
        assertTrue(json.contains("\"format\":\"pcm\""))
        assertTrue(json.contains("\"rate\":16000"))
        assertTrue(json.contains("\"bits\":16"))
        assertTrue(json.contains("\"channel\":1"))
        assertTrue(json.contains("\"model_name\":\"bigmodel\""))
        assertTrue(json.contains("\"uid\":\"uid-1\""))
    }

    @Test
    fun `first response without sequence field parses`() {
        // flags=0: No sequence; size at offset 4 (first packet response)
        val payload = """{"result":{"additions":{"log_id":"x"}}}"""
        val frame = serverFrame(flags = 0, sequence = null, payload = payload)
        val parsed = VolcAsrProtocol.parseServerFrame(frame)
        assertFalse(parsed.isLastPackage)
        assertEquals(0x9, VolcAsrProtocol.messageType(frame))
    }

    @Test
    fun `real response object result parses final text`() {
        // Tested structure: flags=3; final package and result are objects; no "code" field
        val frame =
            serverFrame(
                flags = 3,
                sequence = 13,
                payload = realPayload("你好，这是语音识别功能测试，请把这句话转换成文字。", definite = true),
            )
        val parsed = VolcAsrProtocol.parseServerFrame(frame)
        assertTrue(parsed.isLastPackage) // flags=3 → Final package
        assertEquals(
            "你好，这是语音识别功能测试，请把这句话转换成文字。",
            VolcAsrProtocol.finalText(parsed.response),
        )
    }

    @Test
    fun `intermediate response is not final`() {
        val frame = serverFrame(flags = 1, sequence = 5, payload = realPayload("你好，这是语音"))
        val parsed = VolcAsrProtocol.parseServerFrame(frame)
        assertFalse(parsed.isLastPackage)
        assertEquals("你好，这是语音", VolcAsrProtocol.finalText(parsed.response))
    }

    @Test
    fun `legacy list result joins segments`() {
        // Backward compatibility: `result` is a list of clauses
        val payload = """{"code":0,"is_last_package":true,
            "payload_msg":{"result":[{"text":"ls -la "},{"text":"&& cd src"}]}}"""
        val frame = serverFrame(flags = 1, sequence = 2, payload = payload)
        val parsed = VolcAsrProtocol.parseServerFrame(frame)
        assertTrue(parsed.isLastPackage) // Response body: is_last_package=true (fallback)
        assertEquals("ls -la && cd src", VolcAsrProtocol.finalText(parsed.response))
    }

    @Test
    fun `error code is surfaced`() {
        val payload = """{"code":45000002,"payload_msg":{"message":"empty audio"}}"""
        val frame = serverFrame(flags = 1, sequence = 1, payload = payload)
        val resp = VolcAsrProtocol.parseServerFrame(frame).response
        assertEquals(45000002, resp.code)
        assertEquals("empty audio", resp.payloadMsg?.message)
    }

    @Test
    fun `error frame message is extracted`() {
        // Header(4) + code(4) + size(4) + message
        val msg = "requested resource not granted".encodeToByteArray()
        val bytes = byteArrayOf(0x11, 0xf0.toByte(), 0x10, 0x00) + be32(0x2a) + be32(msg.size) + msg
        val text = VolcAsrProtocol.parseErrorFrame(bytes)
        assertTrue(text.contains("requested resource not granted"))
        assertTrue(text.contains("code=42"))
    }

    @Test
    fun `error frame without size field still readable`() {
        val msg = "boom".encodeToByteArray()
        val bytes = byteArrayOf(0x11, 0xf0.toByte(), 0x10, 0x00) + be32(1) + msg
        val text = VolcAsrProtocol.parseErrorFrame(bytes)
        assertTrue(text.contains("boom"))
    }
}
