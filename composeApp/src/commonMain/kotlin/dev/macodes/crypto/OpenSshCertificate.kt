package dev.macodes.crypto

import dev.macodes.util.base64Decode
import dev.macodes.util.base64Encode

data class ParsedOpenSshCertificate(
    val normalizedLine: String,
    val certType: String,
    val serial: ULong,
    val isUserCert: Boolean,
    val keyId: String,
    val principals: List<String>,
    val validAfterEpochSec: ULong,
    val validBeforeEpochSec: ULong,
    val embeddedPublicKeyBlob: ByteArray,
    val embeddedPublicKeyFingerprintSha256: String,
    val caFingerprintSha256: String,
    val comment: String,
) {
    fun isExpiredAt(nowEpochSec: Long): Boolean {
        if (validBeforeEpochSec == ULong.MAX_VALUE) return false
        val now = nowEpochSec.coerceAtLeast(0L).toULong()
        return now > validBeforeEpochSec
    }

    fun isNotYetValidAt(nowEpochSec: Long): Boolean {
        if (validAfterEpochSec == 0UL) return false
        val now = nowEpochSec.coerceAtLeast(0L).toULong()
        return now < validAfterEpochSec
    }
}

sealed interface OpenSshCertificateParseResult {
    data class Valid(
        val certificate: ParsedOpenSshCertificate,
    ) : OpenSshCertificateParseResult

    data class Invalid(
        val reason: String,
    ) : OpenSshCertificateParseResult
}

private val SUPPORTED_CERT_TYPES =
    mapOf(
        "ssh-ed25519-cert-v01@openssh.com" to "ssh-ed25519",
        "ecdsa-sha2-nistp256-cert-v01@openssh.com" to "ecdsa-sha2-nistp256",
        "ecdsa-sha2-nistp384-cert-v01@openssh.com" to "ecdsa-sha2-nistp384",
        "ecdsa-sha2-nistp521-cert-v01@openssh.com" to "ecdsa-sha2-nistp521",
        "ssh-rsa-cert-v01@openssh.com" to "ssh-rsa",
        "ssh-dss-cert-v01@openssh.com" to "ssh-dss",
    )

/** Parse and structurally validate an OpenSSH certificate (`*-cert-v01@openssh.com`). */
fun parseOpenSshCertificate(rawText: String): OpenSshCertificateParseResult {
    val trimmed = rawText.trim()
    if (trimmed.isEmpty()) {
        return OpenSshCertificateParseResult.Invalid("Certificate is empty.")
    }
    if (trimmed.contains("BEGIN CERTIFICATE") || trimmed.contains("BEGIN X509")) {
        return OpenSshCertificateParseResult.Invalid("X.509/TLS certificates are not supported. Paste an OpenSSH certificate.")
    }
    val line =
        trimmed
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
    val parts = line.split(Regex("\\s+"), limit = 3)
    if (parts.size < 2) {
        return OpenSshCertificateParseResult.Invalid("Expected '<cert-type> <base64-blob> [comment]'.")
    }
    val headerType = parts[0]
    val baseKeyType =
        SUPPORTED_CERT_TYPES[headerType]
            ?: return OpenSshCertificateParseResult.Invalid("Unsupported certificate type: $headerType")
    val comment = parts.getOrNull(2)?.trim().orEmpty()

    val blob =
        try {
            base64Decode(parts[1])
        } catch (_: Exception) {
            return OpenSshCertificateParseResult.Invalid("Invalid base64 certificate payload.")
        }

    return try {
        val reader = CertByteReader(blob)
        val wireType = reader.readString()
        if (wireType != headerType) {
            return OpenSshCertificateParseResult.Invalid("Certificate type mismatch ($wireType vs $headerType).")
        }
        reader.readBytes() // nonce
        val pubFields =
            when (baseKeyType) {
                "ssh-ed25519" -> listOf(reader.readBytes())
                "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521" ->
                    listOf(reader.readBytes(), reader.readBytes())
                "ssh-rsa" -> listOf(reader.readBytes(), reader.readBytes())
                "ssh-dss" -> listOf(reader.readBytes(), reader.readBytes(), reader.readBytes(), reader.readBytes())
                else -> return OpenSshCertificateParseResult.Invalid("Unsupported key type: $baseKeyType")
            }
        val embeddedPubBlob = buildPublicKeyBlob(baseKeyType, pubFields)
        val serial = reader.readUInt64()
        val certKind = reader.readUInt32()
        if (certKind != 1 && certKind != 2) {
            return OpenSshCertificateParseResult.Invalid("Invalid OpenSSH certificate role ($certKind).")
        }
        val keyId = reader.readString()
        val principalsBytes = reader.readBytes()
        val principals = parsePackedStrings(principalsBytes)
        val validAfter = reader.readUInt64()
        val validBefore = reader.readUInt64()
        reader.readBytes() // critical options
        reader.readBytes() // extensions
        reader.readBytes() // reserved
        val signatureKey = reader.readBytes()
        val signature = reader.readBytes()
        if (signatureKey.isEmpty() || signature.isEmpty() || reader.remaining() != 0) {
            return OpenSshCertificateParseResult.Invalid("Malformed OpenSSH certificate structure.")
        }

        OpenSshCertificateParseResult.Valid(
            ParsedOpenSshCertificate(
                normalizedLine = line,
                certType = headerType,
                serial = serial,
                isUserCert = certKind == 1,
                keyId = keyId,
                principals = principals,
                validAfterEpochSec = validAfter,
                validBeforeEpochSec = validBefore,
                embeddedPublicKeyBlob = embeddedPubBlob,
                embeddedPublicKeyFingerprintSha256 = sha256Fingerprint(embeddedPubBlob),
                caFingerprintSha256 = sha256Fingerprint(signatureKey),
                comment = comment,
            ),
        )
    } catch (_: Exception) {
        OpenSshCertificateParseResult.Invalid("Corrupted or truncated OpenSSH certificate.")
    }
}

fun sha256Fingerprint(publicKeyBlob: ByteArray): String = "SHA256:" + base64Encode(Sha256.digest(publicKeyBlob)).trimEnd('=')

private fun buildPublicKeyBlob(
    keyType: String,
    fields: List<ByteArray>,
): ByteArray {
    val typeBytes = keyType.encodeToByteArray()
    val totalSize = 4 + typeBytes.size + fields.sumOf { 4 + it.size }
    val out = ByteArray(totalSize)
    var off = 0

    fun writeSshBytes(bytes: ByteArray) {
        val len = bytes.size
        out[off++] = ((len ushr 24) and 0xff).toByte()
        out[off++] = ((len ushr 16) and 0xff).toByte()
        out[off++] = ((len ushr 8) and 0xff).toByte()
        out[off++] = (len and 0xff).toByte()
        bytes.copyInto(out, off)
        off += len
    }
    writeSshBytes(typeBytes)
    fields.forEach(::writeSshBytes)
    return out
}

private fun parsePackedStrings(bytes: ByteArray): List<String> {
    if (bytes.isEmpty()) return emptyList()
    val reader = CertByteReader(bytes)
    val result = ArrayList<String>()
    while (reader.remaining() > 0) {
        result.add(reader.readString())
    }
    return result
}

private class CertByteReader(
    private val data: ByteArray,
) {
    private var offset = 0

    fun remaining(): Int = data.size - offset

    fun readUInt32(): Int {
        require(remaining() >= 4)
        val v =
            ((data[offset].toInt() and 0xff) shl 24) or
                ((data[offset + 1].toInt() and 0xff) shl 16) or
                ((data[offset + 2].toInt() and 0xff) shl 8) or
                (data[offset + 3].toInt() and 0xff)
        offset += 4
        return v
    }

    fun readUInt64(): ULong {
        require(remaining() >= 8)
        var v = 0UL
        for (i in 0 until 8) {
            v = (v shl 8) or (data[offset + i].toUByte().toULong())
        }
        offset += 8
        return v
    }

    fun readBytes(): ByteArray {
        val len = readUInt32()
        require(len in 0..remaining())
        val out = data.copyOfRange(offset, offset + len)
        offset += len
        return out
    }

    fun readString(): String = readBytes().decodeToString()
}
