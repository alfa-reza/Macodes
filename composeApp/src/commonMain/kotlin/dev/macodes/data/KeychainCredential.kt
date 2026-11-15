package dev.macodes.data

import kotlinx.serialization.Serializable

/** Supported reusable credential types stored in Keychain. */
@Serializable
enum class CredentialKind {
    PRIVATE_KEY,
    PASSWORD,
}

/**
 * Reusable credential metadata stored in [HostRepository].
 * The underlying secret value (private key PEM or password) lives strictly in [SecretStore].
 */
@Serializable
data class KeychainCredential(
    val id: String,
    val name: String,
    val kind: CredentialKind,
    val usernameHint: String = "",
    val updatedAt: Long = 0L,
)
