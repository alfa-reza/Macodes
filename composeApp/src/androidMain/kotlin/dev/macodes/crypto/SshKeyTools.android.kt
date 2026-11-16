package dev.macodes.crypto

import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import com.hierynomus.sshj.userauth.keyprovider.bcrypt.BCrypt
import dev.macodes.ssh.isEncryptedPem
import dev.macodes.util.base64Encode
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider

private val bcProvider by lazy { BouncyCastleProvider() }

actual fun generateSshKeyPair(
    algorithm: SshKeyAlgorithm,
    passphrase: String,
    comment: String,
): GeneratedSshKey {
    val cleanComment = comment.trim().ifEmpty { "macodes" }
    if (algorithm == SshKeyAlgorithm.ED25519 && passphrase.isEmpty()) {
        return generateOpenSshEd25519KeyPair(cleanComment)
    }
    val keyPair = generateJcaKeyPair(algorithm)
    val pubBlob = encodeSshPublicKeyBlob(keyPair.public)
    val sshType = KeyType.fromKey(keyPair.public).toString()
    val payload =
        if (algorithm == SshKeyAlgorithm.ED25519) {
            val encodedPlain = OpenSSHPrivateKeyUtil.encodePrivateKey(PrivateKeyFactory.createKey(keyPair.private.encoded))
            if (passphrase.isEmpty()) {
                encodedPlain
            } else {
                encryptOpenSshV1Payload(encodedPlain, passphrase).also { encodedPlain.fill(0) }
            }
        } else {
            encodeJcaToOpenSshV1(keyPair, pubBlob, sshType, cleanComment, passphrase)
        }
    val body = base64Encode(payload).chunked(70).joinToString("\n")
    payload.fill(0)
    val privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$body\n-----END OPENSSH PRIVATE KEY-----\n"
    val pubLine = "$sshType ${base64Encode(pubBlob)} $cleanComment"
    return GeneratedSshKey(privateKeyPem = privateKeyPem, publicKeyLine = pubLine)
}

private fun encodeJcaToOpenSshV1(
    keyPair: KeyPair,
    pubBlob: ByteArray,
    sshType: String,
    comment: String,
    passphrase: String,
): ByteArray {
    val checkBytes = ByteArray(4).also { SecureRandom().nextBytes(it) }
    val privSection = ByteArrayOutputStream(512)

    fun writeUInt32(
        out: ByteArrayOutputStream,
        v: Int,
    ) {
        out.write((v ushr 24) and 0xff)
        out.write((v ushr 16) and 0xff)
        out.write((v ushr 8) and 0xff)
        out.write(v and 0xff)
    }

    fun writeBytes(
        out: ByteArrayOutputStream,
        b: ByteArray,
    ) {
        writeUInt32(out, b.size)
        out.write(b)
    }

    fun writeStr(
        out: ByteArrayOutputStream,
        s: String,
    ) = writeBytes(out, s.encodeToByteArray())

    fun writeMpint(
        out: ByteArrayOutputStream,
        v: BigInteger,
    ) = writeBytes(out, if (v.signum() == 0) ByteArray(0) else v.toByteArray())

    privSection.write(checkBytes)
    privSection.write(checkBytes)
    writeStr(privSection, sshType)
    when (val priv = keyPair.private) {
        is ECPrivateKey -> {
            val pub = keyPair.public as ECPublicKey
            val curveName =
                when (sshType) {
                    "ecdsa-sha2-nistp256" -> "nistp256"
                    "ecdsa-sha2-nistp384" -> "nistp384"
                    "ecdsa-sha2-nistp521" -> "nistp521"
                    else -> error("Unsupported EC curve $sshType")
                }
            val coordLen = (pub.params.curve.field.fieldSize + 7) / 8
            val xBytes = toFixedUnsigned(pub.w.affineX, coordLen)
            val yBytes = toFixedUnsigned(pub.w.affineY, coordLen)
            val point = ByteArray(1 + coordLen * 2)
            point[0] = 0x04
            xBytes.copyInto(point, 1)
            yBytes.copyInto(point, 1 + coordLen)
            writeStr(privSection, curveName)
            writeBytes(privSection, point)
            writeMpint(privSection, priv.s)
        }
        is RSAPrivateCrtKey -> {
            writeMpint(privSection, priv.modulus)
            writeMpint(privSection, priv.publicExponent)
            writeMpint(privSection, priv.privateExponent)
            writeMpint(privSection, priv.crtCoefficient)
            writeMpint(privSection, priv.primeP)
            writeMpint(privSection, priv.primeQ)
        }
        else -> error("Unsupported private key type: ${priv.javaClass.name}")
    }
    writeStr(privSection, comment)

    val rawPriv = privSection.toByteArray()
    val blockSize = if (passphrase.isEmpty()) 8 else 16
    val rem = rawPriv.size % blockSize
    val padCount = if (rem == 0) 0 else blockSize - rem
    val paddedPriv = ByteArray(rawPriv.size + padCount)
    rawPriv.copyInto(paddedPriv)
    for (i in 0 until padCount) paddedPriv[rawPriv.size + i] = (i + 1).toByte()
    rawPriv.fill(0)

    val out = ByteArrayOutputStream(paddedPriv.size + pubBlob.size + 128)
    out.write("openssh-key-v1\u0000".encodeToByteArray())
    if (passphrase.isEmpty()) {
        writeStr(out, "none")
        writeStr(out, "none")
        writeBytes(out, ByteArray(0))
        writeUInt32(out, 1)
        writeBytes(out, pubBlob)
        writeBytes(out, paddedPriv)
    } else {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val rounds = 16
        val keyAndIv = ByteArray(48)
        BCrypt().pbkdf(passphrase.encodeToByteArray(), salt, rounds, keyAndIv)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyAndIv.copyOfRange(0, 32), "AES"),
            IvParameterSpec(keyAndIv.copyOfRange(32, 48)),
        )
        val encryptedPriv = cipher.doFinal(paddedPriv)
        keyAndIv.fill(0)
        val kdfOpts =
            ByteArrayOutputStream(24)
                .apply {
                    writeBytes(this, salt)
                    writeUInt32(this, rounds)
                }.toByteArray()
        writeStr(out, "aes256-ctr")
        writeStr(out, "bcrypt")
        writeBytes(out, kdfOpts)
        writeUInt32(out, 1)
        writeBytes(out, pubBlob)
        writeBytes(out, encryptedPriv)
    }
    paddedPriv.fill(0)
    return out.toByteArray()
}

private fun toFixedUnsigned(
    value: BigInteger,
    length: Int,
): ByteArray {
    val raw = value.toByteArray()
    val out = ByteArray(length)
    val srcStart = (raw.size - length).coerceAtLeast(0)
    val copyLen = (raw.size - srcStart).coerceAtMost(length)
    raw.copyInto(out, length - copyLen, srcStart, srcStart + copyLen)
    return out
}

private fun encryptOpenSshV1Payload(
    plainPayload: ByteArray,
    passphrase: String,
): ByteArray {
    // Parse unencrypted openssh-key-v1 payload:
    // magic(15) | string cipher("none") | string kdf("none") | string kdfopts("") | uint32(1) | string pub | string priv
    var off = 15

    fun readInt(): Int {
        val v =
            ((plainPayload[off].toInt() and 0xff) shl 24) or
                ((plainPayload[off + 1].toInt() and 0xff) shl 16) or
                ((plainPayload[off + 2].toInt() and 0xff) shl 8) or
                (plainPayload[off + 3].toInt() and 0xff)
        off += 4
        return v
    }

    fun readBytes(): ByteArray {
        val len = readInt()
        return plainPayload.copyOfRange(off, off + len).also { off += len }
    }
    readBytes() // cipher
    readBytes() // kdf
    readBytes() // kdfopts
    readInt() // numKeys (1)
    val pubBlob = readBytes()
    val rawPriv = readBytes()
    // Re-pad private section to 16-byte AES block boundary
    val unpaddedLen =
        run {
            var end = rawPriv.size
            // BouncyCastle pads with 1,2,3,... to 8-byte boundary; strip trailing 1..k padding if present
            val last = rawPriv.last().toInt() and 0xff
            if (last in 1..8 && end >= last && (0 until last).all { (rawPriv[end - last + it].toInt() and 0xff) == it + 1 }) {
                end -= last
            }
            end
        }
    val rem = unpaddedLen % 16
    val padCount = if (rem == 0) 0 else 16 - rem
    val paddedPriv = ByteArray(unpaddedLen + padCount)
    rawPriv.copyInto(paddedPriv, 0, 0, unpaddedLen)
    for (i in 0 until padCount) paddedPriv[unpaddedLen + i] = (i + 1).toByte()

    val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val rounds = 16
    val keyAndIv = ByteArray(48)
    BCrypt().pbkdf(passphrase.encodeToByteArray(), salt, rounds, keyAndIv)
    val aesKey = SecretKeySpec(keyAndIv.copyOfRange(0, 32), "AES")
    val iv = IvParameterSpec(keyAndIv.copyOfRange(32, 48))
    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, aesKey, iv)
    val encryptedPriv = cipher.doFinal(paddedPriv)
    keyAndIv.fill(0)
    paddedPriv.fill(0)

    val out = ByteArrayOutputStream(plainPayload.size + 64)

    fun writeUInt32(v: Int) {
        out.write((v ushr 24) and 0xff)
        out.write((v ushr 16) and 0xff)
        out.write((v ushr 8) and 0xff)
        out.write(v and 0xff)
    }

    fun writeBytes(b: ByteArray) {
        writeUInt32(b.size)
        out.write(b)
    }

    fun writeStr(s: String) = writeBytes(s.encodeToByteArray())

    val kdfOpts =
        ByteArrayOutputStream(24)
            .apply {
                val sLen = salt.size
                write((sLen ushr 24) and 0xff)
                write((sLen ushr 16) and 0xff)
                write((sLen ushr 8) and 0xff)
                write(sLen and 0xff)
                write(salt)
                write((rounds ushr 24) and 0xff)
                write((rounds ushr 16) and 0xff)
                write((rounds ushr 8) and 0xff)
                write(rounds and 0xff)
            }.toByteArray()

    out.write("openssh-key-v1\u0000".encodeToByteArray())
    writeStr("aes256-ctr")
    writeStr("bcrypt")
    writeBytes(kdfOpts)
    writeUInt32(1)
    writeBytes(pubBlob)
    writeBytes(encryptedPriv)
    return out.toByteArray()
}

actual fun inspectSshPrivateKey(
    pem: String,
    passphrase: String?,
): SshKeyValidationResult {
    val trimmed = pem.trim()
    if (trimmed.isEmpty()) {
        return SshKeyValidationResult.Invalid("Private key is empty.")
    }
    if (!trimmed.contains("BEGIN ") || !trimmed.contains("PRIVATE KEY")) {
        return SshKeyValidationResult.Invalid("Unrecognized key format. Expected OpenSSH or PEM private key.")
    }
    val encrypted = isEncryptedPem(trimmed)
    if (encrypted && passphrase.isNullOrEmpty()) {
        return SshKeyValidationResult.NeedsPassphrase
    }
    val provider: FileKeyProvider =
        if (trimmed.contains("OPENSSH PRIVATE KEY")) {
            OpenSSHKeyV1KeyFile()
        } else {
            PKCS8KeyFile()
        }
    return try {
        val finder =
            if (encrypted && !passphrase.isNullOrEmpty()) {
                object : PasswordFinder {
                    override fun reqPassword(resource: Resource<*>?): CharArray = passphrase.toCharArray()

                    override fun shouldRetry(resource: Resource<*>?): Boolean = false
                }
            } else {
                null
            }
        provider.init(StringReader(trimmed), null, finder)
        val pubKey = provider.public
        val keyType = KeyType.fromKey(pubKey)
        if (keyType == KeyType.UNKNOWN) {
            return SshKeyValidationResult.Invalid("Unsupported SSH key algorithm.")
        }
        val pubBlob = encodeSshPublicKeyBlob(pubKey)
        val pubLine = "$keyType ${base64Encode(pubBlob)}"
        val fingerprint = sha256Fingerprint(pubBlob)
        SshKeyValidationResult.Valid(
            algorithmLabel = friendlyAlgorithmLabel(keyType, pubKey),
            sshKeyType = keyType.toString(),
            publicKeyLine = pubLine,
            publicKeyBlob = pubBlob,
            fingerprintSha256 = fingerprint,
            encrypted = encrypted,
        )
    } catch (e: Exception) {
        if (encrypted) {
            SshKeyValidationResult.WrongPassphrase
        } else {
            SshKeyValidationResult.Invalid(e.message ?: e.javaClass.simpleName)
        }
    }
}

private fun generateJcaKeyPair(algorithm: SshKeyAlgorithm): KeyPair =
    when (algorithm) {
        SshKeyAlgorithm.ED25519 ->
            KeyPairGenerator
                .getInstance("Ed25519", bcProvider)
                .apply {
                    initialize(256, SecureRandom())
                }.generateKeyPair()
        SshKeyAlgorithm.ECDSA_256 ->
            KeyPairGenerator
                .getInstance("EC", bcProvider)
                .apply {
                    initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
                }.generateKeyPair()
        SshKeyAlgorithm.ECDSA_384 ->
            KeyPairGenerator
                .getInstance("EC", bcProvider)
                .apply {
                    initialize(ECGenParameterSpec("secp384r1"), SecureRandom())
                }.generateKeyPair()
        SshKeyAlgorithm.ECDSA_521 ->
            KeyPairGenerator
                .getInstance("EC", bcProvider)
                .apply {
                    initialize(ECGenParameterSpec("secp521r1"), SecureRandom())
                }.generateKeyPair()
        SshKeyAlgorithm.RSA_2048 ->
            KeyPairGenerator
                .getInstance("RSA", bcProvider)
                .apply {
                    initialize(2048, SecureRandom())
                }.generateKeyPair()
        SshKeyAlgorithm.RSA_3072 ->
            KeyPairGenerator
                .getInstance("RSA", bcProvider)
                .apply {
                    initialize(3072, SecureRandom())
                }.generateKeyPair()
        SshKeyAlgorithm.RSA_4096 ->
            KeyPairGenerator
                .getInstance("RSA", bcProvider)
                .apply {
                    initialize(4096, SecureRandom())
                }.generateKeyPair()
    }

private fun encodeSshPublicKeyBlob(publicKey: PublicKey): ByteArray = Buffer.PlainBuffer().putPublicKey(publicKey).compactData

private fun friendlyAlgorithmLabel(
    keyType: KeyType,
    publicKey: PublicKey,
): String =
    when (keyType) {
        KeyType.ED25519 -> "Ed25519"
        KeyType.ECDSA256 -> "ECDSA-256"
        KeyType.ECDSA384 -> "ECDSA-384"
        KeyType.ECDSA521 -> "ECDSA-521"
        KeyType.RSA -> {
            val bits = (publicKey as? RSAPublicKey)?.modulus?.bitLength()
            if (bits != null) "RSA-$bits" else "RSA"
        }
        KeyType.DSA -> "DSA"
        else -> {
            val ecBits = (publicKey as? ECPublicKey)?.params?.order?.bitLength()
            if (ecBits != null) "ECDSA-$ecBits" else keyType.toString()
        }
    }
