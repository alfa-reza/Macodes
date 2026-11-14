package dev.termish.data

/**
 * Secure platform storage: passwords, private keys, and other secrets. Stored in the Android Keystore.
 */
expect object SecretStore {
    fun get(
        service: String,
        account: String,
    ): String?

    fun set(
        service: String,
        account: String,
        value: String,
    )

    fun delete(
        service: String,
        account: String,
    )
}

/** Service name used for secret storage. */
const val SECRET_SERVICE = "dev.termish.secrets"

fun secretAccountFor(
    hostId: String,
    kind: String,
): String = "$hostId.$kind"

/** Parse the host authentication credentials from the platform's secure storage and return password / privateKey. */
fun resolveCredentials(host: Host): Pair<String?, String?> {
    val password = SecretStore.get(SECRET_SERVICE, secretAccountFor(host.id, "password"))
    val privateKey = SecretStore.get(SECRET_SERVICE, secretAccountFor(host.id, "privateKey"))
    return when (host.authMethod) {
        HostAuthMethod.PASSWORD -> password to null
        HostAuthMethod.PRIVATE_KEY -> null to privateKey
        HostAuthMethod.KEY_OR_PASSWORD -> password to privateKey
    }
}

/** The storage account for the Volcano Engine Streaming Speech Recognition API Key (obtained from the API Key Management page in the new console). */
const val ASR_API_KEY_ACCOUNT = "asr.apiKey"

/** The key and account for the speech recognition service instance (at the provider level; see AsrProvider). */
fun asrKeyAccount(providerId: String): String = "asr.$providerId.apiKey"

/** Agent model provider API key (at the provider level; does not write to general Settings). */
fun agentProviderKeyAccount(providerId: String): String = "agent.provider.$providerId.apiKey"
