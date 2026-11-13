package dev.termish.mosh

import dev.termish.util.base64Decode
import dev.termish.util.base64Encode

/**
 * mosh data datagram encryption session: AES-128-OCB, nonce = 4 bytes of zeros || 8-byte big-endian number,
 * The most significant bit of the sequence number indicates the direction (TO_CLIENT=1). Wire format:
 *   8B nonce (low-order bits) || OCB ciphertext || 16B tag
 * Plaintext format: 2B timestamp || 2B timestamp_reply || fragmentation of the payload.
 */
internal class MoshCryptoSession(
    keyBase64: String,
) {
    private val ocb: Ocb

    /** Encryption block count (protocol: session terminates upon reaching 2^47 blocks to prevent OCB birthday attacks). */
    private var blocksEncrypted = 0L

    init {
        // mosh Base64Key: 22 characters (16-byte key without == filling), and must follow encoding specification,
        // Reject pseudo-keys with a non-zero bit at the end (same validation as Base64Key).
        require(keyBase64.length == 22) { "mosh key 必须为 22 字符 base64" }
        val key = base64Decode(keyBase64)
        require(key.size == 16) { "mosh key 必须解码为 16 字节" }
        require(base64Encode(key).startsWith(keyBase64)) { "mosh key 编码不规范" }
        ocb = Ocb(key)
    }

    class PlainPacket(
        val seq: ULong,
        val timestamp: Int, // uint16
        val timestampReply: Int, // uint16
        val payload: ByteArray,
    )

    /** Apply encryption to a data packet to be sent (client-bound, direction bit 0). */
    fun encrypt(
        seq: ULong,
        timestamp: Int,
        timestampReply: Int,
        payload: ByteArray,
    ): ByteArray {
        require(seq and DIRECTION_BIT == 0uL) { "客户端发往服务器方向位必须为 0" }
        val plain = ByteArray(4 + payload.size)
        plain[0] = (timestamp shr 8).toByte()
        plain[1] = timestamp.toByte()
        plain[2] = (timestampReply shr 8).toByte()
        plain[3] = timestampReply.toByte()
        payload.copyInto(plain, 4)
        val nonce = nonceBytes(seq and SEQUENCE_MASK)
        val ct = ocb.encrypt(nonce, plain)
        blocksEncrypted += (plain.size + 15) / 16
        if (blocksEncrypted shr 47 != 0L) {
            throw IllegalStateException("OCB 加密块数达到 2^47 上限，会话终止")
        }
        val out = ByteArray(8 + ct.size)
        nonce.copyInto(out, 0, 4, 12) // Low 8-byte sequence number
        ct.copyInto(out, 8)
        return out
    }

    /** Decryption of a received datagram; failure of the tag check results in null. */
    fun decrypt(datagram: ByteArray): PlainPacket? {
        if (datagram.size < 24) return null
        val nonce = ByteArray(12)
        datagram.copyInto(nonce, 4, 0, 8)
        val plain = ocb.decrypt(nonce, datagram.copyOfRange(8, datagram.size)) ?: return null
        if (plain.size < 4) return null
        val raw = (0..7).fold(0uL) { acc, i -> (acc shl 8) or (datagram[i].toInt() and 0xff).toULong() }
        // The client only accepts TO_CLIENT (most significant bit = 1); the monotonically increasing sequence number is obtained after masking the direction bit.
        // The direction bit check corresponds to `dos_assert(direction == expected_direction)` in `mosh recv_one`,
        // Prevent the client from replaying its own previously sent packets as valid data.
        if (raw and DIRECTION_BIT == 0uL) return null
        val seq = raw and SEQUENCE_MASK
        return PlainPacket(
            seq = seq,
            timestamp = ((plain[0].toInt() and 0xff) shl 8) or (plain[1].toInt() and 0xff),
            timestampReply = ((plain[2].toInt() and 0xff) shl 8) or (plain[3].toInt() and 0xff),
            payload = plain.copyOfRange(4, plain.size),
        )
    }

    private fun nonceBytes(seq: ULong): ByteArray {
        val n = ByteArray(12)
        for (i in 0..7) {
            n[4 + i] = (seq shr (56 - 8 * i)).toByte()
        }
        return n
    }

    companion object {
        /** The most significant bit of the data packet sequence number is the direction bit (protocol direction bit: TO_SERVER=0 / TO_CLIENT=1). */
        private val DIRECTION_BIT: ULong = 1uL shl 63
        private val SEQUENCE_MASK: ULong = ULong.MAX_VALUE xor DIRECTION_BIT
    }
}
