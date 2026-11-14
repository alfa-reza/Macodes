package dev.macodes.mosh

/**
 * OCB3 (RFC 7253) certified encryption, pure Kotlin implementation, alignment with mosh’s ae (AES-128-OCB) parameters:
 * 12-byte nonce, 16-byte tag, no additional data (AD).
 *
 * Implementation of the full message interfaces AE_ENCRYPT and AE_DECRYPT required by mosh.
 */
internal class Ocb(
    key: ByteArray,
) {
    private val aes = Aes128(key)

    /** L_* = double(double(zero)) = ENC(0) doubled twice; see below for L_$ and L[0..]. */
    private val lStar = ByteArray(16)
    private val lDollar = ByteArray(16)
    private val l = Array(64) { ByteArray(16) }

    init {
        val zero = ByteArray(16)
        aes.encryptBlock(zero, 0, lStar, 0)
        doubleBlock(lStar, lDollar)
        doubleBlock(lStar, l[0])
        for (i in 1 until l.size) {
            doubleBlock(l[i - 1], l[i])
        }
    }

    /** Multiply by 2 in GF(2^128): Shift one bit to the left; if the most significant bit overflows, XOR with 0x87. */
    private fun doubleBlock(
        input: ByteArray,
        output: ByteArray,
    ) {
        var carry = 0
        for (i in 15 downTo 0) {
            val v = input[i].toInt() and 0xff
            output[i] = ((v shl 1) or carry).toByte()
            carry = (v ushr 7) and 1
        }
        if (carry != 0) output[15] = (output[15].toInt() xor 0x87).toByte()
    }

    private fun ntz(n: Int): Int {
        var x = n
        var c = 0
        while (x and 1 == 0) {
            x = x ushr 1
            c++
        }
        return c
    }

    private fun xorBlock(
        a: ByteArray,
        aOff: Int,
        b: ByteArray,
        out: ByteArray,
        outOff: Int,
    ) {
        for (i in 0..15) out[outOff + i] = (a[aOff + i].toInt() xor b[i].toInt()).toByte()
    }

    private fun xor3(
        a: ByteArray,
        aOff: Int,
        b: ByteArray,
        c: ByteArray,
        out: ByteArray,
        outOff: Int,
    ) {
        for (i in 0..15) out[outOff + i] = (a[aOff + i].toInt() xor b[i].toInt() xor c[i].toInt()).toByte()
    }

    /**
     * Calculate a 24-bit prefix + Ktop from a 12-byte nonce to produce offset_0.
     * RFC 7253 §4.2: Nonce = 0^{120-len} || 1 || nonce (len=96 → 24-bit header all zeros || 1 || 96-bit nonce,
     * exactly 16 bytes), bottom = the lower 6 bits, Ktop = ENC(Nonce high 128–6 bits << 6).
     */
    private fun initialOffset(nonce: ByteArray): ByteArray {
        require(nonce.size == 12)
        val nonceBlock = ByteArray(16)
        nonceBlock[3] = 1 // 0^23 || 1 falls at the end of the 4th byte (when len=96, the first 24 bits of the stretch header are all zeros)
        nonce.copyInto(nonceBlock, 4)
        val bottom = nonceBlock[15].toInt() and 0x3f
        // Ktop = ENC(first 122 bits of the nonce || 6 zeros) (mask the lower 6 bits, no shift)
        val masked = nonceBlock.copyOf()
        masked[15] = (masked[15].toInt() and 0xC0).toByte()
        val ktop = ByteArray(16)
        aes.encryptBlock(masked, 0, ktop, 0)
        // Offset = (Ktop[0..8] << bottom) | (Ktop[9..15] >> (8-bottom)), taking the top 128 bits
        val stretch = ByteArray(24)
        ktop.copyInto(stretch, 0, 0, 16)
        for (i in 0..7) {
            stretch[16 + i] = (ktop[i].toInt() xor ktop[i + 1].toInt()).toByte()
        }
        val offset = ByteArray(16)
        val byteShift = bottom / 8
        val bitShift = bottom % 8
        for (i in 0..15) {
            val idx = i + byteShift
            val b1 = stretch[idx].toInt() and 0xff
            val b2 = if (idx + 1 < 24) stretch[idx + 1].toInt() and 0xff else 0
            offset[i] =
                if (bitShift == 0) {
                    b1.toByte()
                } else {
                    (((b1 shl bitShift) and 0xff) or (b2 ushr (8 - bitShift))).toByte()
                }
        }
        return offset
    }

    /**
     * Encryption: plaintext → ciphertext || tag (16B).
     */
    fun encrypt(
        nonce: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val out = ByteArray(plaintext.size + 16)
        val offset = initialOffset(nonce)
        val checksum = ByteArray(16)
        val tmp = ByteArray(16)
        val blockCount = plaintext.size / 16

        for (i in 0 until blockCount) {
            xorInto(offset, l[ntz(i + 1) + 1]) // The L table is offset by one position (L[0] = double(L_$))
            xorBlock(plaintext, i * 16, offset, tmp, 0)
            aes.encryptBlock(tmp, 0, tmp, 0)
            xorBlock(tmp, 0, offset, out, i * 16)
            xorInto(checksum, plaintext, i * 16)
        }

        // Less than one block at the end
        val rem = plaintext.size % 16
        if (rem != 0) {
            xorInto(offset, lStar)
            val pad = ByteArray(16)
            aes.encryptBlock(offset.copyOf(), 0, pad, 0)
            for (i in 0 until rem) {
                out[blockCount * 16 + i] = (plaintext[blockCount * 16 + i].toInt() xor pad[i].toInt()).toByte()
                checksum[i] = (checksum[i].toInt() xor plaintext[blockCount * 16 + i].toInt()).toByte()
            }
            checksum[rem] = (checksum[rem].toInt() xor 0x80).toByte()
        }

        // tag = ENC(checksum ^ offset ^ L_$)
        xor3(checksum, 0, offset, lDollar, tmp, 0)
        aes.encryptBlock(tmp, 0, tmp, 0)
        tmp.copyInto(out, plaintext.size, 0, 16)
        return out
    }

    /**
     * Decryption and validation of the tag; failure returns null (corresponding to AE_INVALID in ae_decrypt).
     * Input is ciphertext || tag (16B).
     */
    fun decrypt(
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
    ): ByteArray? {
        require(ciphertextAndTag.size >= 16)
        val ctLen = ciphertextAndTag.size - 16
        val plain = ByteArray(ctLen)
        val offset = initialOffset(nonce)
        val checksum = ByteArray(16)
        val tmp = ByteArray(16)
        val blockCount = ctLen / 16

        for (i in 0 until blockCount) {
            xorInto(offset, l[ntz(i + 1) + 1])
            xorBlock(ciphertextAndTag, i * 16, offset, tmp, 0)
            aes.decryptBlock(tmp, 0, tmp, 0)
            xorBlock(tmp, 0, offset, plain, i * 16)
            xorInto(checksum, plain, i * 16)
        }

        val rem = ctLen % 16
        if (rem != 0) {
            xorInto(offset, lStar)
            val pad = ByteArray(16)
            aes.encryptBlock(offset.copyOf(), 0, pad, 0)
            for (i in 0 until rem) {
                val p = ciphertextAndTag[blockCount * 16 + i].toInt() xor pad[i].toInt()
                plain[blockCount * 16 + i] = p.toByte()
                checksum[i] = (checksum[i].toInt() xor p).toByte()
            }
            checksum[rem] = (checksum[rem].toInt() xor 0x80).toByte()
        }

        xor3(checksum, 0, offset, lDollar, tmp, 0)
        aes.encryptBlock(tmp, 0, tmp, 0)
        for (i in 0..15) {
            if (tmp[i] != ciphertextAndTag[ctLen + i]) return null
        }
        return plain
    }

    private fun xorInto(
        acc: ByteArray,
        other: ByteArray,
    ) {
        for (i in 0..15) acc[i] = (acc[i].toInt() xor other[i].toInt()).toByte()
    }

    private fun xorInto(
        acc: ByteArray,
        other: ByteArray,
        otherOff: Int,
    ) {
        for (i in 0..15) acc[i] = (acc[i].toInt() xor other[otherOff + i].toInt()).toByte()
    }
}
