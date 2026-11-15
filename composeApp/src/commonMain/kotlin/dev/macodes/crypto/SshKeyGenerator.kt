package dev.macodes.crypto

import dev.macodes.util.base64Encode

/** Cryptographically random byte generator provided by the host platform. */
expect fun secureRandomBytes(size: Int): ByteArray

/** Newly generated Ed25519 key pair in unencrypted OpenSSH v1 private format + authorized_keys public line. */
data class GeneratedSshKey(
    val privateKeyPem: String,
    val publicKeyLine: String,
)

/** Generate an unencrypted OpenSSH v1 Ed25519 key pair compatible with sshd and sshj. */
fun generateOpenSshEd25519KeyPair(comment: String = "macodes"): GeneratedSshKey {
    val cleanComment = comment.trim().ifEmpty { "macodes" }
    val seed = secureRandomBytes(32)
    val checkBytes = secureRandomBytes(4)
    return try {
        val (privSeed, publicKey) = Ed25519.keyPairFromSeed(seed)
        val publicBlob = encodePublicBlob(publicKey)
        val privateSection = encodePrivateSection(checkBytes, publicKey, privSeed, cleanComment)
        try {
            val payload =
                buildByteBuffer {
                    writeRaw("openssh-key-v1\u0000".encodeToByteArray())
                    writeSshString("none")
                    writeSshString("none")
                    writeSshBytes(ByteArray(0))
                    writeUInt32(1)
                    writeSshBytes(publicBlob)
                    writeSshBytes(privateSection)
                }
            val body = base64Encode(payload).chunked(70).joinToString("\n")
            val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$body\n-----END OPENSSH PRIVATE KEY-----\n"
            val pubLine = "ssh-ed25519 ${base64Encode(publicBlob)} $cleanComment"
            payload.fill(0)
            GeneratedSshKey(privateKeyPem = pem, publicKeyLine = pubLine)
        } finally {
            privateSection.fill(0)
        }
    } finally {
        seed.fill(0)
        checkBytes.fill(0)
    }
}

private fun encodePublicBlob(publicKey: ByteArray): ByteArray =
    buildByteBuffer {
        writeSshString("ssh-ed25519")
        writeSshBytes(publicKey)
    }

private fun encodePrivateSection(
    checkBytes: ByteArray,
    publicKey: ByteArray,
    privSeed: ByteArray,
    comment: String,
): ByteArray {
    val raw =
        buildByteBuffer {
            writeRaw(checkBytes)
            writeRaw(checkBytes)
            writeSshString("ssh-ed25519")
            writeSshBytes(publicKey)
            writeSshBytes(privSeed + publicKey)
            writeSshString(comment)
        }
    val rem = raw.size % 8
    val padCount = if (rem == 0) 0 else 8 - rem
    if (padCount == 0) return raw
    val padded = ByteArray(raw.size + padCount)
    raw.copyInto(padded)
    for (i in 0 until padCount) {
        padded[raw.size + i] = (i + 1).toByte()
    }
    raw.fill(0)
    return padded
}

private inline fun buildByteBuffer(block: ByteBufferWriter.() -> Unit): ByteArray = ByteBufferWriter().apply(block).toByteArray()

private class ByteBufferWriter {
    private val out = ArrayList<Byte>(256)

    fun writeRaw(bytes: ByteArray) {
        for (b in bytes) out.add(b)
    }

    fun writeUInt32(value: Int) {
        out.add(((value ushr 24) and 0xff).toByte())
        out.add(((value ushr 16) and 0xff).toByte())
        out.add(((value ushr 8) and 0xff).toByte())
        out.add((value and 0xff).toByte())
    }

    fun writeSshBytes(bytes: ByteArray) {
        writeUInt32(bytes.size)
        writeRaw(bytes)
    }

    fun writeSshString(value: String) {
        writeSshBytes(value.encodeToByteArray())
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}
