package dev.macodes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import dev.macodes.crypto.OpenSshCertificateParseResult
import dev.macodes.crypto.SshKeyValidationResult
import dev.macodes.crypto.inspectSshPrivateKey
import dev.macodes.crypto.parseOpenSshCertificate
import dev.macodes.data.CredentialKind
import dev.macodes.data.KeychainCredential
import dev.macodes.data.getKeychainPassphrase
import dev.macodes.data.getKeychainSecret
import dev.macodes.data.saveKeychainPassphrase
import dev.macodes.data.saveKeychainSecret
import dev.macodes.ui.theme.Alphas
import dev.macodes.ui.theme.Corners
import dev.macodes.ui.theme.Sizes
import dev.macodes.ui.theme.Spacing
import dev.macodes.util.monospaceFontFamily
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
internal fun CredentialEditKeyScreen(
    credential: KeychainCredential,
    onBack: () -> Unit,
    onSaved: (KeychainCredential) -> Unit,
    onRequestDelete: (KeychainCredential) -> Unit,
    onMessage: (String) -> Unit,
) {
    val s = LocalAppStrings.current
    val ws = s.workspace
    val clipboard = LocalClipboardManager.current
    val authenticateDevice = rememberDeviceAuthenticator()
    val exportTextFile =
        rememberTextFileExporter { ok ->
            if (ok) onMessage(ws.credentialsExportSuccess)
        }

    val storedPrivateKey = remember(credential.id) { getKeychainSecret(credential.id, CredentialKind.PRIVATE_KEY).orEmpty() }
    val storedPassphrase = remember(credential.id) { getKeychainPassphrase(credential.id).orEmpty() }

    var name by remember(credential.id) { mutableStateOf(credential.name) }
    var usernameHint by remember(credential.id) { mutableStateOf(credential.usernameHint) }
    var privateKeyText by remember(credential.id) { mutableStateOf(storedPrivateKey) }
    var passphraseInput by remember(credential.id) { mutableStateOf(storedPassphrase) }
    var savePassphrase by remember(credential.id) { mutableStateOf(credential.savePassphrase && storedPassphrase.isNotEmpty()) }

    var privateKeyRevealed by remember(credential.id) { mutableStateOf(false) }
    var passphraseRevealed by remember(credential.id) { mutableStateOf(false) }
    var publicKeyRevealed by remember(credential.id) { mutableStateOf(true) }

    val initialInspect =
        remember(credential.id, storedPrivateKey, storedPassphrase) {
            inspectSshPrivateKey(storedPrivateKey, storedPassphrase.takeIf { it.isNotEmpty() })
        }
    var inspected by remember(credential.id) { mutableStateOf(initialInspect) }

    val validInspect = inspected as? SshKeyValidationResult.Valid
    val isEncryptedKey =
        inspected is SshKeyValidationResult.NeedsPassphrase ||
            inspected is SshKeyValidationResult.WrongPassphrase ||
            validInspect?.encrypted == true ||
            storedPassphrase.isNotEmpty()

    SensitiveScreenProtection()

    Scaffold(
        topBar = {
            MacodesLargeHeader(
                title = ws.keychainEdit,
                onBack = onBack,
                actions = {
                    IconButton(onClick = { onRequestDelete(credential) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = ws.actionRemove,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            // Key type & SHA-256 fingerprint summary card
            Surface(
                shape = RoundedCornerShape(Corners.Lg),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.Md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    ) {
                        Text(
                            text = validInspect?.algorithmLabel ?: credential.keyAlgorithm.ifBlank { ws.keychainKindKey },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (!credential.isActive && validInspect == null) {
                            ProtocolBadge(label = ws.credentialsInactiveBadge)
                        }
                    }
                    val fp = validInspect?.fingerprintSha256 ?: credential.fingerprintSha256
                    if (fp.isNotBlank()) {
                        Text(
                            text = "${ws.credentialsFingerprintLabel}: $fp",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = monospaceFontFamily()),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!credential.isActive && validInspect == null) {
                        val err =
                            when (val res = inspected) {
                                SshKeyValidationResult.NeedsPassphrase -> ws.credentialsPassphraseRequiredHint
                                SshKeyValidationResult.WrongPassphrase -> ws.credentialsWrongPassphraseError
                                is SshKeyValidationResult.Invalid -> res.reason
                                is SshKeyValidationResult.Valid -> null
                            }
                        if (err != null) {
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(ws.keychainNameLabel) },
                singleLine = true,
                shape = RoundedCornerShape(Corners.Md),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = usernameHint,
                onValueChange = { usernameHint = it },
                label = { Text(ws.keychainUsernameHintLabel) },
                singleLine = true,
                shape = RoundedCornerShape(Corners.Md),
                modifier = Modifier.fillMaxWidth(),
            )

            // Private key (hidden by default; 3-dot Reveal / Hide / Copy)
            SensitiveCredentialField(
                label = ws.credentialsPrivateKeyLabel,
                value = privateKeyText,
                revealed = privateKeyRevealed,
                maskedPlaceholder = "••••••••••••••••••••••••••••••••",
                onToggleReveal = { privateKeyRevealed = !privateKeyRevealed },
                onCopy = {
                    if (privateKeyText.isNotBlank()) {
                        clipboard.setText(AnnotatedString(privateKeyText))
                        onMessage(ws.credentialsCopiedValue)
                    }
                },
                editable = !credential.isActive,
                onValueChange = {
                    privateKeyText = it
                    inspected = inspectSshPrivateKey(it, passphraseInput.takeIf { p -> p.isNotEmpty() })
                },
            )

            // Passphrase section (when key is encrypted or passphrase saved)
            if (isEncryptedKey) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = passphraseInput,
                            onValueChange = {
                                passphraseInput = it
                                if (it.isEmpty()) savePassphrase = false
                                inspected = inspectSshPrivateKey(privateKeyText, it.takeIf { p -> p.isNotEmpty() })
                            },
                            label = { Text(ws.credentialsPassphraseLabel) },
                            singleLine = true,
                            visualTransformation =
                                if (passphraseRevealed) VisualTransformation.None else PasswordVisualTransformation(),
                            shape = RoundedCornerShape(Corners.Md),
                            modifier = Modifier.weight(1f),
                        )
                        ThreeDotSecretMenu(
                            revealed = passphraseRevealed,
                            onReveal = {
                                if (storedPassphrase.isNotEmpty() && !passphraseRevealed) {
                                    authenticateDevice(
                                        ws.credentialsAuthPromptTitle,
                                        ws.credentialsAuthPromptSubtitle,
                                    ) { ok ->
                                        if (ok) {
                                            passphraseRevealed = true
                                        } else {
                                            onMessage(ws.credentialsAuthFailed)
                                        }
                                    }
                                } else {
                                    passphraseRevealed = true
                                }
                            },
                            onHide = { passphraseRevealed = false },
                            onCopy = {
                                if (passphraseInput.isNotEmpty()) {
                                    if (storedPassphrase.isNotEmpty() && !passphraseRevealed) {
                                        authenticateDevice(
                                            ws.credentialsAuthPromptTitle,
                                            ws.credentialsAuthPromptSubtitle,
                                        ) { ok ->
                                            if (ok) {
                                                clipboard.setText(AnnotatedString(passphraseInput))
                                                onMessage(ws.credentialsCopiedValue)
                                            } else {
                                                onMessage(ws.credentialsAuthFailed)
                                            }
                                        }
                                    } else {
                                        clipboard.setText(AnnotatedString(passphraseInput))
                                        onMessage(ws.credentialsCopiedValue)
                                    }
                                }
                            },
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = savePassphrase,
                            enabled = passphraseInput.isNotEmpty(),
                            onCheckedChange = { checked -> savePassphrase = checked },
                        )
                        Text(
                            text = ws.credentialsSavePassphrase,
                            style = MaterialTheme.typography.bodyMedium,
                            color =
                                if (passphraseInput.isNotEmpty()) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
            }

            // Public Key + Export Public Key
            val publicKeyText = validInspect?.publicKeyLine
            if (!publicKeyText.isNullOrBlank()) {
                SensitiveCredentialField(
                    label = ws.keychainPublicKeyLabel,
                    value = publicKeyText,
                    revealed = publicKeyRevealed,
                    maskedPlaceholder = "••••••••••••••••••••••••••••••••",
                    onToggleReveal = { publicKeyRevealed = !publicKeyRevealed },
                    onCopy = {
                        clipboard.setText(AnnotatedString(publicKeyText))
                        onMessage(ws.keychainPublicKeyCopied)
                    },
                )
                OutlinedButton(
                    onClick = {
                        val safeName = name.trim().ifEmpty { "id_key" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
                        exportTextFile("$safeName.pub", "$publicKeyText\n")
                    },
                    shape = RoundedCornerShape(Corners.Md),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(Sizes.IconSmall))
                        Text(ws.credentialsExportPublicKey)
                    }
                }
            }

            Spacer(Modifier.size(Spacing.Xs))

            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    val finalInspect = inspectSshPrivateKey(privateKeyText, passphraseInput.takeIf { it.isNotEmpty() })
                    val finalValid = finalInspect as? SshKeyValidationResult.Valid
                    if (privateKeyText.isNotBlank()) {
                        saveKeychainSecret(credential.id, CredentialKind.PRIVATE_KEY, privateKeyText.trim())
                    }
                    if (savePassphrase && passphraseInput.isNotEmpty()) {
                        saveKeychainPassphrase(credential.id, passphraseInput)
                    } else {
                        saveKeychainPassphrase(credential.id, null)
                    }
                    val updated =
                        credential.copy(
                            name = name.trim(),
                            usernameHint = usernameHint.trim(),
                            updatedAt = Clock.System.now().toEpochMilliseconds(),
                            isActive = finalValid != null || (credential.isActive && isEncryptedKey && passphraseInput.isEmpty()),
                            keyAlgorithm = finalValid?.algorithmLabel ?: credential.keyAlgorithm,
                            fingerprintSha256 = finalValid?.fingerprintSha256 ?: credential.fingerprintSha256,
                            savePassphrase = savePassphrase && passphraseInput.isNotEmpty(),
                        )
                    onSaved(updated)
                },
                shape = RoundedCornerShape(Corners.Md),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(ws.credentialsSaveChanges)
            }

            OutlinedButton(
                onClick = { onRequestDelete(credential) },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.error.copy(alpha = Alphas.BORDER_ACCENT)),
                shape = RoundedCornerShape(Corners.Md),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(ws.actionRemove)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CredentialEditCertificateScreen(
    credential: KeychainCredential,
    privateKeyCredentials: List<KeychainCredential>,
    onBack: () -> Unit,
    onSaved: (KeychainCredential) -> Unit,
    onRequestDelete: (KeychainCredential) -> Unit,
    onMessage: (String) -> Unit,
) {
    val s = LocalAppStrings.current
    val ws = s.workspace
    val clipboard = LocalClipboardManager.current

    val storedCert = remember(credential.id) { getKeychainSecret(credential.id, CredentialKind.CERTIFICATE).orEmpty() }
    var name by remember(credential.id) { mutableStateOf(credential.name) }
    var usernameHint by remember(credential.id) { mutableStateOf(credential.usernameHint) }
    var certRevealed by remember(credential.id) { mutableStateOf(false) }
    var selectedKeyId by remember(credential.id) { mutableStateOf(credential.linkedPrivateKeyId) }

    val parsedResult = remember(storedCert) { parseOpenSshCertificate(storedCert) }
    val parsed = (parsedResult as? OpenSshCertificateParseResult.Valid)?.certificate
    val nowSec = Clock.System.now().epochSeconds
    val expired = parsed?.isExpiredAt(nowSec) == true

    // Verify key match if a private key is selected
    val keyMismatchError =
        remember(selectedKeyId, parsed) {
            if (selectedKeyId == null || parsed == null) {
                null
            } else {
                val selectedCred = privateKeyCredentials.firstOrNull { it.id == selectedKeyId }
                if (selectedCred != null &&
                    selectedCred.fingerprintSha256.isNotBlank() &&
                    selectedCred.fingerprintSha256 != parsed.embeddedPublicKeyFingerprintSha256
                ) {
                    ws.credentialsCertMismatchError
                } else {
                    null
                }
            }
        }

    SensitiveScreenProtection()

    Scaffold(
        topBar = {
            MacodesLargeHeader(
                title = ws.keychainEdit,
                onBack = onBack,
                actions = {
                    IconButton(onClick = { onRequestDelete(credential) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = ws.actionRemove,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            if (parsed != null) {
                Surface(
                    shape = RoundedCornerShape(Corners.Lg),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.Md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                        ) {
                            Text(
                                text = parsed.keyId.ifBlank { parsed.certType },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (expired || selectedKeyId == null || keyMismatchError != null) {
                                ProtocolBadge(label = ws.credentialsInactiveBadge)
                            }
                        }
                        Text(
                            text = "${ws.credentialsKeyTypeLabel}: ${parsed.certType}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (parsed.principals.isNotEmpty()) {
                            Text(
                                text = "${ws.credentialsCertPrincipalsLabel}: ${parsed.principals.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = "${ws.credentialsCertValidityLabel}: ${formatCertValidity(parsed.validAfterEpochSec, parsed.validBeforeEpochSec)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${ws.credentialsFingerprintLabel}: ${parsed.embeddedPublicKeyFingerprintSha256}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = monospaceFontFamily()),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(ws.keychainNameLabel) },
                singleLine = true,
                shape = RoundedCornerShape(Corners.Md),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = usernameHint,
                onValueChange = { usernameHint = it },
                label = { Text(ws.keychainUsernameHintLabel) },
                singleLine = true,
                shape = RoundedCornerShape(Corners.Md),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = ws.credentialsLinkedKeyLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (privateKeyCredentials.isEmpty()) {
                Text(
                    text = ws.credentialsNoPrivateKeysHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                ) {
                    privateKeyCredentials.forEach { keyCred ->
                        FilterChip(
                            selected = selectedKeyId == keyCred.id,
                            onClick = { selectedKeyId = if (selectedKeyId == keyCred.id) null else keyCred.id },
                            label = { Text(keyCred.name) },
                        )
                    }
                }
            }
            if (keyMismatchError != null) {
                Text(
                    text = keyMismatchError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SensitiveCredentialField(
                label = ws.credentialsKindCertificate,
                value = storedCert,
                revealed = certRevealed,
                maskedPlaceholder = "••••••••••••••••••••••••••••••••",
                onToggleReveal = { certRevealed = !certRevealed },
                onCopy = {
                    if (storedCert.isNotBlank()) {
                        clipboard.setText(AnnotatedString(storedCert))
                        onMessage(ws.credentialsCopiedValue)
                    }
                },
            )

            Button(
                enabled = name.isNotBlank() && keyMismatchError == null,
                onClick = {
                    val active = parsed != null && !expired && selectedKeyId != null && keyMismatchError == null
                    onSaved(
                        credential.copy(
                            name = name.trim(),
                            usernameHint = usernameHint.trim(),
                            updatedAt = Clock.System.now().toEpochMilliseconds(),
                            linkedPrivateKeyId = selectedKeyId,
                            isActive = active,
                        ),
                    )
                },
                shape = RoundedCornerShape(Corners.Md),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(ws.credentialsSaveChanges)
            }

            OutlinedButton(
                onClick = { onRequestDelete(credential) },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.error.copy(alpha = Alphas.BORDER_ACCENT)),
                shape = RoundedCornerShape(Corners.Md),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(ws.actionRemove)
            }
        }
    }
}

@Composable
private fun SensitiveCredentialField(
    label: String,
    value: String,
    revealed: Boolean,
    maskedPlaceholder: String,
    onToggleReveal: () -> Unit,
    onCopy: () -> Unit,
    editable: Boolean = false,
    onValueChange: ((String) -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ThreeDotSecretMenu(
                revealed = revealed,
                onReveal = { if (!revealed) onToggleReveal() },
                onHide = { if (revealed) onToggleReveal() },
                onCopy = onCopy,
            )
        }
        if (editable && revealed && onValueChange != null) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = monospaceFontFamily()),
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(Corners.Md),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Surface(
                shape = RoundedCornerShape(Corners.Md),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (revealed) value else maskedPlaceholder,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = monospaceFontFamily()),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (revealed) 8 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(Spacing.Md),
                )
            }
        }
    }
}

@Composable
private fun ThreeDotSecretMenu(
    revealed: Boolean,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    onCopy: () -> Unit,
) {
    val ws = LocalAppStrings.current.workspace
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (!revealed) {
                DropdownMenuItem(
                    text = { Text(ws.credentialsRevealAction) },
                    leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onReveal()
                    },
                )
            } else {
                DropdownMenuItem(
                    text = { Text(ws.credentialsHideAction) },
                    leadingIcon = { Icon(Icons.Default.VisibilityOff, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onHide()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(ws.credentialsCopyAction) },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                onClick = {
                    expanded = false
                    onCopy()
                },
            )
        }
    }
}

internal fun formatCertValidity(
    validAfter: ULong,
    validBefore: ULong,
): String {
    fun fmt(epoch: ULong): String {
        if (epoch == 0UL || epoch == ULong.MAX_VALUE) return "∞"
        val dt = Instant.fromEpochSeconds(epoch.toLong()).toLocalDateTime(TimeZone.currentSystemDefault())
        return "${dt.year}-${dt.monthNumber.toString().padStart(2, '0')}-${dt.dayOfMonth.toString().padStart(2, '0')}"
    }
    return "${fmt(validAfter)} → ${fmt(validBefore)}"
}
