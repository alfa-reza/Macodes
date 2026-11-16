package dev.macodes.data

import kotlinx.serialization.Serializable

/** Supported reusable credential types stored in Credentials. */
@Serializable
enum class CredentialKind {
    PRIVATE_KEY,
    CERTIFICATE,
    PASSWORD,
}

/**
 * Reusable credential metadata stored in [HostRepository].
 * The underlying secret value (private key PEM, certificate, passphrase, or password) lives strictly in [SecretStore].
 */
@Serializable
data class KeychainCredential(
    val id: String,
    val name: String,
    val kind: CredentialKind,
    val usernameHint: String = "",
    val updatedAt: Long = 0L,
    val isActive: Boolean = true,
    val keyAlgorithm: String = "",
    val fingerprintSha256: String = "",
    val linkedPrivateKeyId: String? = null,
    val savePassphrase: Boolean = false,
)
