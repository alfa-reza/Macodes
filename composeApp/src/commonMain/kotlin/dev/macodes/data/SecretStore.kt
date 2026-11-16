package dev.macodes.data

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
const val SECRET_SERVICE = "dev.macodes.secrets"

fun secretAccountFor(
    hostId: String,
    kind: String,
): String = "$hostId.$kind"

fun keychainNamespaceFor(credentialId: String): String = "keychain.$credentialId"

fun saveKeychainSecret(
    credentialId: String,
    kind: CredentialKind,
    secret: String,
) {
    val ns = keychainNamespaceFor(credentialId)
    when (kind) {
        CredentialKind.PASSWORD -> {
            SecretStore.set(SECRET_SERVICE, secretAccountFor(ns, "password"), secret)
            SecretStore.delete(SECRET_SERVICE, secretAccountFor(ns, "privateKey"))
            SecretStore.delete(SECRET_SERVICE, secretAccountFor(ns, "certificate"))
        }
        CredentialKind.PRIVATE_KEY -> {
            SecretStore.set(SECRET_SERVICE, secretAccountFor(ns, "privateKey"), secret)
            SecretStore.delete(SECRET_SERVICE, secretAccountFor(ns, "password"))
            SecretStore.delete(SECRET_SERVICE, secretAccountFor(ns, "certificate"))
        }
        CredentialKind.CERTIFICATE -> {
            SecretStore.set(SECRET_SERVICE, secretAccountFor(ns, "certificate"), secret)
            SecretStore.delete(SECRET_SERVICE, secretAccountFor(ns, "password"))
            SecretStore.delete(SECRET_SERVICE, secretAccountFor(ns, "privateKey"))
        }
    }
}

fun getKeychainSecret(
    credentialId: String,
    kind: CredentialKind,
): String? {
    val ns = keychainNamespaceFor(credentialId)
    val field =
        when (kind) {
            CredentialKind.PASSWORD -> "password"
            CredentialKind.PRIVATE_KEY -> "privateKey"
            CredentialKind.CERTIFICATE -> "certificate"
        }
    return SecretStore.get(SECRET_SERVICE, secretAccountFor(ns, field))
}

fun saveKeychainPassphrase(
    credentialId: String,
    passphrase: String?,
) {
    val account = secretAccountFor(keychainNamespaceFor(credentialId), "passphrase")
    if (passphrase.isNullOrEmpty()) {
        SecretStore.delete(SECRET_SERVICE, account)
    } else {
        SecretStore.set(SECRET_SERVICE, account, passphrase)
    }
}

fun getKeychainPassphrase(credentialId: String): String? = SecretStore.get(SECRET_SERVICE, secretAccountFor(keychainNamespaceFor(credentialId), "passphrase"))

fun deleteKeychainSecret(credentialId: String) {
    val ns = keychainNamespaceFor(credentialId)
    SecretStore.delete(SECRET_SERVICE, secretAccountFor(ns, "password"))
    SecretStore.delete(SECRET_SERVICE, secretAccountFor(ns, "privateKey"))
    SecretStore.delete(SECRET_SERVICE, secretAccountFor(ns, "certificate"))
    SecretStore.delete(SECRET_SERVICE, secretAccountFor(ns, "passphrase"))
}

data class ResolvedSshAuth(
    val password: String? = null,
    val privateKeyPem: String? = null,
    val privateKeyPassphrase: String? = null,
    val certificateText: String? = null,
)

/**
 * Resolve full SSH authentication material for [host], respecting inactive/draft credentials,
 * saved private key passphrases, and OpenSSH certificates linked to a private key.
 */
fun resolveSshAuth(
    host: Host,
    repository: HostRepository? = null,
): ResolvedSshAuth {
    val credId = host.credentialId?.takeIf { it.isNotBlank() }
    val cred = credId?.let { id -> repository?.getCredential(id) }
    if (cred != null && !cred.isActive) {
        return ResolvedSshAuth()
    }
    if (cred != null && cred.kind == CredentialKind.CERTIFICATE) {
        val certText = getKeychainSecret(cred.id, CredentialKind.CERTIFICATE)
        val linkedKeyId = cred.linkedPrivateKeyId?.takeIf { it.isNotBlank() }
        val linkedCred = linkedKeyId?.let { repository?.getCredential(it) }
        val keyPem =
            if (linkedKeyId != null && (linkedCred == null || linkedCred.isActive)) {
                getKeychainSecret(linkedKeyId, CredentialKind.PRIVATE_KEY)
            } else {
                null
            }
        val passphrase =
            if (linkedKeyId != null && linkedCred?.savePassphrase == true) {
                getKeychainPassphrase(linkedKeyId)
            } else {
                null
            }
        return when (host.authMethod) {
            HostAuthMethod.PASSWORD -> ResolvedSshAuth()
            HostAuthMethod.PRIVATE_KEY,
            HostAuthMethod.KEY_OR_PASSWORD,
            ->
                ResolvedSshAuth(
                    privateKeyPem = keyPem,
                    privateKeyPassphrase = passphrase,
                    certificateText = certText,
                )
        }
    }
    val (password, privateKey) = resolveCredentials(host)
    val passphrase =
        if (cred != null && cred.kind == CredentialKind.PRIVATE_KEY && cred.savePassphrase) {
            getKeychainPassphrase(cred.id)
        } else {
            null
        }
    return ResolvedSshAuth(
        password = password,
        privateKeyPem = privateKey,
        privateKeyPassphrase = passphrase,
    )
}

/** Parse the host authentication credentials from the platform's secure storage and return password / privateKey. */
fun resolveCredentials(host: Host): Pair<String?, String?> {
    val keychainNs = host.credentialId?.takeIf { it.isNotBlank() }?.let(::keychainNamespaceFor)
    val password =
        keychainNs?.let { SecretStore.get(SECRET_SERVICE, secretAccountFor(it, "password")) }
            ?: SecretStore.get(SECRET_SERVICE, secretAccountFor(host.id, "password"))
    val privateKey =
        keychainNs?.let { SecretStore.get(SECRET_SERVICE, secretAccountFor(it, "privateKey")) }
            ?: SecretStore.get(SECRET_SERVICE, secretAccountFor(host.id, "privateKey"))
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
