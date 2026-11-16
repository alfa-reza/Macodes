package dev.macodes.crypto

import dev.macodes.util.base64Encode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenSshCertificateTest {
    @Test
    fun parsesValidEd25519CertificateAndChecksExpiryAndEmbeddedKeyFingerprint() {
        val pk = ByteArray(32) { (it + 1).toByte() }
        val caKey = ByteArray(48) { (it + 10).toByte() }
        val blob =
            buildCertBlob {
                writeString("ssh-ed25519-cert-v01@openssh.com")
                writeBytes(ByteArray(32) { 7 }) // nonce
                writeBytes(pk)
                writeUInt64(42UL) // serial
                writeUInt32(1) // user cert
                writeString("alice@prod") // keyId
                writeBytes(
                    buildCertBlob {
                        writeString("alice")
                        writeString("deploy")
                    },
                )
                writeUInt64(1_700_000_000UL) // validAfter
                writeUInt64(1_800_000_000UL) // validBefore
                writeBytes(ByteArray(0)) // critical options
                writeBytes(ByteArray(0)) // extensions
                writeBytes(ByteArray(0)) // reserved
                writeBytes(caKey)
                writeBytes(ByteArray(64) { 9 }) // signature
            }
        val line = "ssh-ed25519-cert-v01@openssh.com ${base64Encode(blob)} alice@prod"
        val res = parseOpenSshCertificate(line) as OpenSshCertificateParseResult.Valid
        val cert = res.certificate
        assertEquals("ssh-ed25519-cert-v01@openssh.com", cert.certType)
        assertEquals(42UL, cert.serial)
        assertTrue(cert.isUserCert)
        assertEquals("alice@prod", cert.keyId)
        assertEquals(listOf("alice", "deploy"), cert.principals)
        assertFalse(cert.isExpiredAt(1_750_000_000L))
        assertTrue(cert.isExpiredAt(1_850_000_000L))
        assertFalse(cert.isNotYetValidAt(1_750_000_000L))
        assertTrue(cert.isNotYetValidAt(1_600_000_000L))
        assertTrue(cert.embeddedPublicKeyFingerprintSha256.startsWith("SHA256:"))
        assertEquals(sha256Fingerprint(caKey), cert.caFingerprintSha256)
    }

    @Test
    fun rejectsX509AndMalformedCertificates() {
        assertTrue(
            parseOpenSshCertificate("-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----")
                is OpenSshCertificateParseResult.Invalid,
        )
        assertTrue(parseOpenSshCertificate("ssh-ed25519-cert-v01@openssh.com !!!bad!!!") is OpenSshCertificateParseResult.Invalid)
        assertTrue(parseOpenSshCertificate("") is OpenSshCertificateParseResult.Invalid)
    }

    private fun buildCertBlob(block: TestCertWriter.() -> Unit): ByteArray = TestCertWriter().apply(block).toByteArray()

    private class TestCertWriter {
        private val out = ArrayList<Byte>(256)

        fun writeUInt32(value: Int) {
            out.add(((value ushr 24) and 0xff).toByte())
            out.add(((value ushr 16) and 0xff).toByte())
            out.add(((value ushr 8) and 0xff).toByte())
            out.add((value and 0xff).toByte())
        }

        fun writeUInt64(value: ULong) {
            for (shift in 56 downTo 0 step 8) {
                out.add(((value shr shift) and 0xffUL).toByte())
            }
        }

        fun writeBytes(bytes: ByteArray) {
            writeUInt32(bytes.size)
            for (b in bytes) out.add(b)
        }

        fun writeString(value: String) = writeBytes(value.encodeToByteArray())

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
