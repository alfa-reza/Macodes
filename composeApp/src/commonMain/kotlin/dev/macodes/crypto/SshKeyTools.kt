package dev.macodes.crypto

enum class SshKeyAlgorithm(
    val displayLabel: String,
) {
    ED25519("Ed25519"),
    ECDSA_256("ECDSA (256-bit)"),
    ECDSA_384("ECDSA (384-bit)"),
    ECDSA_521("ECDSA (521-bit)"),
    RSA_2048("RSA (2048-bit)"),
    RSA_3072("RSA (3072-bit)"),
    RSA_4096("RSA (4096-bit)"),
}

sealed interface SshKeyValidationResult {
    data class Valid(
        val algorithmLabel: String,
        val sshKeyType: String,
        val publicKeyLine: String,
        val publicKeyBlob: ByteArray,
        val fingerprintSha256: String,
        val encrypted: Boolean,
    ) : SshKeyValidationResult

    data object NeedsPassphrase : SshKeyValidationResult

    data object WrongPassphrase : SshKeyValidationResult

    data class Invalid(
        val reason: String,
    ) : SshKeyValidationResult
}

/** Generate an SSH key pair compatible with the active SSH engine. */
expect fun generateSshKeyPair(
    algorithm: SshKeyAlgorithm,
    passphrase: String = "",
    comment: String = "macodes",
): GeneratedSshKey

/** Validate and inspect an SSH private key (OpenSSH v1, PKCS#8, or traditional PEM). */
expect fun inspectSshPrivateKey(
    pem: String,
    passphrase: String? = null,
): SshKeyValidationResult
