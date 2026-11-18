package dev.macodes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.macodes.crypto.OpenSshCertificateParseResult
import dev.macodes.crypto.ParsedOpenSshCertificate
import dev.macodes.crypto.SshKeyAlgorithm
import dev.macodes.crypto.SshKeyValidationResult
import dev.macodes.crypto.generateSshKeyPair
import dev.macodes.crypto.inspectSshPrivateKey
import dev.macodes.crypto.parseOpenSshCertificate
import dev.macodes.data.CredentialKind
import dev.macodes.data.Host
import dev.macodes.data.HostRepository
import dev.macodes.data.KeychainCredential
import dev.macodes.data.deleteKeychainSecret
import dev.macodes.data.newId
import dev.macodes.data.saveKeychainPassphrase
import dev.macodes.data.saveKeychainSecret
import dev.macodes.ui.theme.Alphas
import dev.macodes.ui.theme.Corners
import dev.macodes.ui.theme.Sizes
import dev.macodes.ui.theme.Spacing
import dev.macodes.util.monospaceFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

private const val MAX_KEY_FILE_BYTES = 256 * 1024

private enum class CredentialSheetStep {
    NONE,
    ROOT_MENU,
    SSH_KEY_MENU,
    SSH_KEY_PASTE,
    SSH_KEY_IMPORT,
    SSH_KEY_GENERATE,
    CERT_MENU,
    CERT_PASTE,
    CERT_IMPORT,
}

private enum class GenerateKeyFamily {
    ED25519,
    ECDSA,
    RSA,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeychainScreen(
    repository: HostRepository,
    hosts: List<Host>,
    onCredentialsChanged: () -> Unit,
    onBack: () -> Unit,
) {
    val s = LocalAppStrings.current
    val ws = s.workspace
    var credentials by remember { mutableStateOf(repository.listCredentials()) }
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var activeEditCredential by remember { mutableStateOf<KeychainCredential?>(null) }
    var passwordDialogTarget by remember { mutableStateOf<KeychainCredential?>(null) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var sheetStep by remember { mutableStateOf(CredentialSheetStep.NONE) }
    var pendingDelete by remember { mutableStateOf<List<KeychainCredential>>(emptyList()) }
    val searchFocusRequester = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val selectionMode = selectedIds.isNotEmpty()

    SensitiveScreenProtection(enabled = showPasswordDialog || sheetStep != CredentialSheetStep.NONE)

    LaunchedEffect(searchExpanded) {
        if (searchExpanded) {
            delay(150)
            searchFocusRequester.requestFocus()
        }
    }

    val usageByCredential =
        remember(hosts) {
            hosts
                .mapNotNull { it.credentialId }
                .groupingBy { it }
                .eachCount()
        }

    val q = query.trim()
    val filtered =
        credentials.filter { cred ->
            q.isEmpty() ||
                cred.name.contains(q, ignoreCase = true) ||
                cred.usernameHint.contains(q, ignoreCase = true) ||
                cred.keyAlgorithm.contains(q, ignoreCase = true) ||
                cred.fingerprintSha256.contains(q, ignoreCase = true)
        }

    val keyItems = filtered.filter { it.kind == CredentialKind.PRIVATE_KEY }
    val certItems = filtered.filter { it.kind == CredentialKind.CERTIFICATE }
    val passwordItems = filtered.filter { it.kind == CredentialKind.PASSWORD }

    fun refresh() {
        credentials = repository.listCredentials()
        onCredentialsChanged()
    }

    fun toggleSelection(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun openCredential(cred: KeychainCredential) {
        when (cred.kind) {
            CredentialKind.PRIVATE_KEY,
            CredentialKind.CERTIFICATE,
            -> activeEditCredential = cred
            CredentialKind.PASSWORD -> {
                passwordDialogTarget = cred
                showPasswordDialog = true
            }
        }
    }

    val currentEdit = activeEditCredential
    if (currentEdit != null) {
        when (currentEdit.kind) {
            CredentialKind.PRIVATE_KEY -> {
                CredentialEditKeyScreen(
                    credential = currentEdit,
                    onBack = { activeEditCredential = null },
                    onSaved = { updated ->
                        repository.upsertCredential(updated)
                        activeEditCredential = null
                        refresh()
                    },
                    onRequestDelete = { target -> pendingDelete = listOf(target) },
                    onMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                )
            }
            CredentialKind.CERTIFICATE -> {
                CredentialEditCertificateScreen(
                    credential = currentEdit,
                    privateKeyCredentials = credentials.filter { it.kind == CredentialKind.PRIVATE_KEY },
                    onBack = { activeEditCredential = null },
                    onSaved = { updated ->
                        repository.upsertCredential(updated)
                        activeEditCredential = null
                        refresh()
                    },
                    onRequestDelete = { target -> pendingDelete = listOf(target) },
                    onMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                )
            }
            CredentialKind.PASSWORD -> Unit
        }
    } else {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (selectionMode) {
                    WorkspaceSelectionHeader(
                        count = selectedIds.size,
                        onClose = { selectedIds = emptySet() },
                        onEdit = {
                            val target = credentials.firstOrNull { it.id in selectedIds }
                            if (target != null) {
                                selectedIds = emptySet()
                                openCredential(target)
                            }
                        },
                        onSelectAll = { selectedIds = filtered.map { it.id }.toSet() },
                        onRemove = {
                            val targets = credentials.filter { it.id in selectedIds }
                            if (targets.isNotEmpty()) pendingDelete = targets
                        },
                    )
                } else {
                    MacodesLargeHeader(
                        title = ws.keychainTitle,
                        onBack = onBack,
                        actions = {
                            IconButton(
                                onClick = {
                                    if (searchExpanded) {
                                        searchExpanded = false
                                        query = ""
                                    } else {
                                        searchExpanded = true
                                    }
                                },
                            ) {
                                Icon(
                                    if (searchExpanded) Icons.Filled.Close else Icons.Filled.Search,
                                    contentDescription = if (searchExpanded) s.navBack else ws.keychainSearch,
                                )
                            }
                        },
                    )
                }
            },
            floatingActionButton = {
                if (!selectionMode) {
                    FloatingActionButton(
                        onClick = { sheetStep = CredentialSheetStep.ROOT_MENU },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(Corners.Lg),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = ws.keychainAdd)
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                AnimatedVisibility(
                    visible = searchExpanded && !selectionMode,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    MacodesSearchPill(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = ws.keychainSearch,
                        modifier =
                            Modifier
                                .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm)
                                .focusRequester(searchFocusRequester),
                    )
                }

                if (filtered.isEmpty()) {
                    WorkspaceEmptyState(
                        icon = Icons.Default.VpnKey,
                        message = if (credentials.isEmpty()) ws.keychainEmpty else ws.keychainNoMatch,
                        actionLabel = if (credentials.isEmpty()) ws.keychainAdd else null,
                        onAction =
                            if (credentials.isEmpty()) {
                                { sheetStep = CredentialSheetStep.ROOT_MENU }
                            } else {
                                null
                            },
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = Spacing.Lg, vertical = Spacing.Sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    ) {
                        if (keyItems.isNotEmpty()) {
                            item(key = "header_keys") {
                                CredentialSectionHeader(ws.credentialsSectionKeys)
                            }
                            items(keyItems, key = { it.id }) { cred ->
                                KeychainCredentialCard(
                                    credential = cred,
                                    linkedCount = usageByCredential[cred.id] ?: 0,
                                    selected = cred.id in selectedIds,
                                    onClick = {
                                        if (selectionMode) toggleSelection(cred.id) else openCredential(cred)
                                    },
                                    onLongClick = { toggleSelection(cred.id) },
                                )
                            }
                        }
                        if (certItems.isNotEmpty()) {
                            item(key = "header_certs") {
                                CredentialSectionHeader(ws.credentialsSectionCertificates)
                            }
                            items(certItems, key = { it.id }) { cred ->
                                KeychainCredentialCard(
                                    credential = cred,
                                    linkedCount = usageByCredential[cred.id] ?: 0,
                                    selected = cred.id in selectedIds,
                                    onClick = {
                                        if (selectionMode) toggleSelection(cred.id) else openCredential(cred)
                                    },
                                    onLongClick = { toggleSelection(cred.id) },
                                )
                            }
                        }
                        if (passwordItems.isNotEmpty()) {
                            item(key = "header_passwords") {
                                CredentialSectionHeader(ws.credentialsSectionPasswords)
                            }
                            items(passwordItems, key = { it.id }) { cred ->
                                KeychainCredentialCard(
                                    credential = cred,
                                    linkedCount = usageByCredential[cred.id] ?: 0,
                                    selected = cred.id in selectedIds,
                                    onClick = {
                                        if (selectionMode) toggleSelection(cred.id) else openCredential(cred)
                                    },
                                    onLongClick = { toggleSelection(cred.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (sheetStep != CredentialSheetStep.NONE) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { sheetStep = CredentialSheetStep.NONE },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            CredentialModalSheetContent(
                step = sheetStep,
                existingCredentials = credentials,
                onStepChange = { sheetStep = it },
                onOpenPasswordDialog = {
                    sheetStep = CredentialSheetStep.NONE
                    passwordDialogTarget = null
                    showPasswordDialog = true
                },
                onSaveCredential = { cred, secret, passphrase ->
                    saveKeychainSecret(cred.id, cred.kind, secret)
                    if (cred.kind == CredentialKind.PRIVATE_KEY) {
                        saveKeychainPassphrase(cred.id, if (cred.savePassphrase) passphrase else null)
                    }
                    repository.upsertCredential(cred)
                    sheetStep = CredentialSheetStep.NONE
                    refresh()
                },
                onClose = { sheetStep = CredentialSheetStep.NONE },
            )
        }
    }

    if (showPasswordDialog) {
        PasswordEditDialog(
            existing = passwordDialogTarget,
            onDismiss = { showPasswordDialog = false },
            onSave = { cred, secret ->
                if (secret.isNotBlank()) {
                    saveKeychainSecret(cred.id, CredentialKind.PASSWORD, secret)
                }
                repository.upsertCredential(cred)
                showPasswordDialog = false
                refresh()
            },
        )
    }

    if (pendingDelete.isNotEmpty()) {
        val message =
            if (pendingDelete.size == 1) {
                val target = pendingDelete.first()
                ws.keychainDeleteConfirm(target.name, usageByCredential[target.id] ?: 0)
            } else {
                ws.keychainDeleteSelectedConfirm(pendingDelete.size)
            }
        AlertDialog(
            onDismissRequest = { pendingDelete = emptyList() },
            title = { Text(ws.actionRemove) },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete.forEach { cred ->
                            deleteKeychainSecret(cred.id)
                            repository.deleteCredential(cred.id)
                            if (activeEditCredential?.id == cred.id) {
                                activeEditCredential = null
                            }
                        }
                        selectedIds = emptySet()
                        pendingDelete = emptyList()
                        refresh()
                    },
                ) {
                    Text(ws.actionRemove, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = emptyList() }) {
                    Text(s.quickConnect.cancelAction)
                }
            },
        )
    }
}

@Composable
private fun CredentialSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.Sm, bottom = Spacing.Xs),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeychainCredentialCard(
    credential: KeychainCredential,
    linkedCount: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val ws = LocalAppStrings.current.workspace
    val kindLabel =
        when (credential.kind) {
            CredentialKind.PRIVATE_KEY -> credential.keyAlgorithm.ifBlank { ws.keychainKindKey }
            CredentialKind.CERTIFICATE -> ws.credentialsKindCertificate
            CredentialKind.PASSWORD -> ws.keychainKindPassword
        }
    val icon =
        when (credential.kind) {
            CredentialKind.PRIVATE_KEY -> Icons.Default.Key
            CredentialKind.CERTIFICATE -> Icons.Default.Badge
            CredentialKind.PASSWORD -> Icons.Default.Password
        }
    val linkLabel = if (linkedCount > 0) ws.keychainUsedByCount(linkedCount) else ws.keychainUnused
    val parts =
        buildList {
            if (credential.usernameHint.isNotBlank()) add(credential.usernameHint)
            if (credential.fingerprintSha256.isNotBlank()) {
                add(credential.fingerprintSha256.take(22) + "…")
            }
            add(linkLabel)
        }
    val subtitleText = parts.joinToString(" · ")

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Corners.Lg))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        shape = RoundedCornerShape(Corners.Lg),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = Alphas.SELECTION_CONTAINER)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
            ),
        border =
            BorderStroke(
                width = if (selected) 1.5.dp else Sizes.BorderThin,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                )
            } else {
                WorkspaceIconBadge(icon = icon)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
                ) {
                    Text(
                        text = credential.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    ProtocolBadge(label = kindLabel)
                    if (!credential.isActive) {
                        ProtocolBadge(label = ws.credentialsInactiveBadge)
                    }
                }
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CredentialModalSheetContent(
    step: CredentialSheetStep,
    existingCredentials: List<KeychainCredential>,
    onStepChange: (CredentialSheetStep) -> Unit,
    onOpenPasswordDialog: () -> Unit,
    onSaveCredential: (KeychainCredential, secret: String, passphrase: String?) -> Unit,
    onClose: () -> Unit,
) {
    val ws = LocalAppStrings.current.workspace
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Lg)
                .padding(bottom = Spacing.Xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        when (step) {
            CredentialSheetStep.NONE -> Unit
            CredentialSheetStep.ROOT_MENU -> {
                Text(
                    text = ws.keychainAdd,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                SheetActionOption(
                    icon = Icons.Default.Key,
                    label = ws.keychainNewSshKey,
                    onClick = { onStepChange(CredentialSheetStep.SSH_KEY_MENU) },
                )
                SheetActionOption(
                    icon = Icons.Default.Badge,
                    label = ws.credentialsNewCertificate,
                    onClick = { onStepChange(CredentialSheetStep.CERT_MENU) },
                )
                SheetActionOption(
                    icon = Icons.Default.Password,
                    label = ws.keychainNewPassword,
                    onClick = onOpenPasswordDialog,
                )
            }
            CredentialSheetStep.SSH_KEY_MENU -> {
                Text(
                    text = ws.keychainNewSshKey,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                SheetActionOption(
                    icon = Icons.Default.ContentPaste,
                    label = ws.credentialsPasteKey,
                    onClick = { onStepChange(CredentialSheetStep.SSH_KEY_PASTE) },
                )
                SheetActionOption(
                    icon = Icons.Default.FolderOpen,
                    label = ws.credentialsImportKey,
                    onClick = { onStepChange(CredentialSheetStep.SSH_KEY_IMPORT) },
                )
                SheetActionOption(
                    icon = Icons.Default.AutoAwesome,
                    label = ws.credentialsGenerateKey,
                    onClick = { onStepChange(CredentialSheetStep.SSH_KEY_GENERATE) },
                )
            }
            CredentialSheetStep.SSH_KEY_PASTE,
            CredentialSheetStep.SSH_KEY_IMPORT,
            -> {
                AddSshKeySheetBody(
                    autoLaunchPicker = step == CredentialSheetStep.SSH_KEY_IMPORT,
                    existingCredentials = existingCredentials,
                    onSave = onSaveCredential,
                    onCancel = onClose,
                )
            }
            CredentialSheetStep.SSH_KEY_GENERATE -> {
                GenerateSshKeySheetBody(
                    onSave = onSaveCredential,
                )
            }
            CredentialSheetStep.CERT_MENU -> {
                Text(
                    text = ws.credentialsNewCertificate,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                SheetActionOption(
                    icon = Icons.Default.ContentPaste,
                    label = ws.credentialsPasteCert,
                    onClick = { onStepChange(CredentialSheetStep.CERT_PASTE) },
                )
                SheetActionOption(
                    icon = Icons.Default.FolderOpen,
                    label = ws.credentialsImportCert,
                    onClick = { onStepChange(CredentialSheetStep.CERT_IMPORT) },
                )
            }
            CredentialSheetStep.CERT_PASTE,
            CredentialSheetStep.CERT_IMPORT,
            -> {
                AddCertificateSheetBody(
                    autoLaunchPicker = step == CredentialSheetStep.CERT_IMPORT,
                    existingCredentials = existingCredentials,
                    onSave = { cred, cert -> onSaveCredential(cred, cert, null) },
                    onQuickSaveKey = { keyCred, keySecret, passphrase ->
                        saveKeychainSecret(keyCred.id, CredentialKind.PRIVATE_KEY, keySecret)
                        if (keyCred.savePassphrase && !passphrase.isNullOrEmpty()) {
                            saveKeychainPassphrase(keyCred.id, passphrase)
                        }
                    },
                    onCancel = onClose,
                )
            }
        }
    }
}

@Composable
private fun SheetActionOption(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Corners.Md),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun AddSshKeySheetBody(
    autoLaunchPicker: Boolean,
    existingCredentials: List<KeychainCredential>,
    onSave: (KeychainCredential, secret: String, passphrase: String?) -> Unit,
    onCancel: () -> Unit,
) {
    val s = LocalAppStrings.current
    val ws = s.workspace
    val clipboard = LocalClipboardManager.current

    var privateKeyText by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var savePassphrase by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf<SshKeyValidationResult?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var importedFileName by remember { mutableStateOf<String?>(null) }
    var pickerLaunched by remember { mutableStateOf(false) }

    fun runValidate(
        text: String = privateKeyText,
        pass: String = passphrase,
    ) {
        fileError = null
        validation = inspectSshPrivateKey(text, pass.takeIf { it.isNotEmpty() })
    }

    val pickKeyFile =
        rememberFilePicker { picked ->
            when (val result = readPickedKeyText(picked)) {
                is KeyImportResult.Loaded -> {
                    privateKeyText = result.content
                    importedFileName = picked.name
                    if (name.isBlank()) {
                        name = picked.name.substringBeforeLast('.').ifBlank { picked.name }
                    }
                    runValidate(result.content, passphrase)
                }
                KeyImportResult.TooLarge -> fileError = ws.keychainImportTooLarge
                KeyImportResult.Failed -> fileError = ws.keychainImportFailed
            }
        }

    LaunchedEffect(autoLaunchPicker) {
        if (autoLaunchPicker && !pickerLaunched) {
            pickerLaunched = true
            pickKeyFile()
        }
    }

    Text(
        text = if (autoLaunchPicker) ws.credentialsImportKey else ws.credentialsPasteKey,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )

    if (autoLaunchPicker) {
        OutlinedButton(
            onClick = pickKeyFile,
            shape = RoundedCornerShape(Corners.Md),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Text(ws.keychainImportAction)
            }
        }
        importedFileName?.let { fname ->
            Text(
                text = ws.keychainImportedFileLabel(fname),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    OutlinedTextField(
        value = privateKeyText,
        onValueChange = {
            privateKeyText = it
            validation = null
            fileError = null
        },
        label = { Text(ws.keychainSecretKeyLabel) },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = monospaceFontFamily()),
        minLines = 4,
        maxLines = 8,
        trailingIcon = {
            IconButton(
                onClick = {
                    val clip = clipboard.getText()?.text.orEmpty()
                    if (clip.isNotBlank()) {
                        privateKeyText = clip
                        validation = null
                        fileError = null
                    }
                },
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = ws.credentialsPasteButton)
            }
        },
        shape = RoundedCornerShape(Corners.Md),
        modifier = Modifier.fillMaxWidth(),
    )

    val needsPassphrase =
        validation is SshKeyValidationResult.NeedsPassphrase ||
            validation is SshKeyValidationResult.WrongPassphrase

    if (needsPassphrase || passphrase.isNotEmpty()) {
        OutlinedTextField(
            value = passphrase,
            onValueChange = {
                passphrase = it
                if (it.isEmpty()) savePassphrase = false
            },
            label = { Text(ws.credentialsPassphraseLabel) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(Corners.Md),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = savePassphrase,
                enabled = passphrase.isNotEmpty(),
                onCheckedChange = { savePassphrase = it },
            )
            Text(ws.credentialsSavePassphrase, style = MaterialTheme.typography.bodyMedium)
        }
    }

    OutlinedButton(
        enabled = privateKeyText.isNotBlank(),
        onClick = { runValidate() },
        shape = RoundedCornerShape(Corners.Md),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(ws.credentialsValidateButton)
    }

    fileError?.let {
        Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    when (val res = validation) {
        null -> Unit
        SshKeyValidationResult.NeedsPassphrase -> {
            Text(
                text = ws.credentialsPassphraseRequiredHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        SshKeyValidationResult.WrongPassphrase -> {
            Text(
                text = ws.credentialsWrongPassphraseError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        is SshKeyValidationResult.Invalid -> {
            Text(
                text = res.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Corners.Md),
                ) {
                    Text(s.quickConnect.cancelAction)
                }
                OutlinedButton(
                    onClick = {
                        val label = name.trim().ifEmpty { "Draft SSH Key" }
                        onSave(
                            KeychainCredential(
                                id = newId(),
                                name = label,
                                kind = CredentialKind.PRIVATE_KEY,
                                updatedAt = Clock.System.now().toEpochMilliseconds(),
                                isActive = false,
                            ),
                            privateKeyText.trim(),
                            null,
                        )
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Corners.Md),
                ) {
                    Text(ws.credentialsSaveAnyway)
                }
            }
        }
        is SshKeyValidationResult.Valid -> {
            val duplicate =
                existingCredentials.any {
                    it.kind == CredentialKind.PRIVATE_KEY && it.fingerprintSha256 == res.fingerprintSha256
                }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(ws.keychainNameLabel) },
                placeholder = { Text(res.algorithmLabel) },
                singleLine = true,
                shape = RoundedCornerShape(Corners.Md),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "${ws.credentialsKeyTypeLabel}: ${res.algorithmLabel}\n${ws.credentialsFingerprintLabel}: ${res.fingerprintSha256}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = monospaceFontFamily()),
                color = MaterialTheme.colorScheme.primary,
            )
            if (duplicate) {
                Text(
                    text = ws.credentialsDuplicateWarning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = {
                    val finalName = name.trim().ifEmpty { res.algorithmLabel }
                    onSave(
                        KeychainCredential(
                            id = newId(),
                            name = finalName,
                            kind = CredentialKind.PRIVATE_KEY,
                            updatedAt = Clock.System.now().toEpochMilliseconds(),
                            isActive = true,
                            keyAlgorithm = res.algorithmLabel,
                            fingerprintSha256 = res.fingerprintSha256,
                            savePassphrase = savePassphrase && passphrase.isNotEmpty(),
                        ),
                        privateKeyText.trim(),
                        passphrase.takeIf { savePassphrase && it.isNotEmpty() },
                    )
                },
                shape = RoundedCornerShape(Corners.Md),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(s.editSave)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenerateSshKeySheetBody(
    onSave: (KeychainCredential, secret: String, passphrase: String?) -> Unit,
) {
    val ws = LocalAppStrings.current.workspace
    var name by remember { mutableStateOf("SSH Key") }
    var family by remember { mutableStateOf(GenerateKeyFamily.ED25519) }
    var ecdsaBits by remember { mutableStateOf(256) }
    var rsaBits by remember { mutableStateOf(3072) }
    var passphrase by remember { mutableStateOf("") }
    var savePassphrase by remember { mutableStateOf(false) }

    Text(
        text = ws.credentialsGenerateKey,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )

    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(ws.keychainNameLabel) },
        singleLine = true,
        shape = RoundedCornerShape(Corners.Md),
        modifier = Modifier.fillMaxWidth(),
    )

    Text(
        text = ws.credentialsKeyTypeLabel,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
        FilterChip(
            selected = family == GenerateKeyFamily.ED25519,
            onClick = { family = GenerateKeyFamily.ED25519 },
            label = { Text("Ed25519") },
        )
        FilterChip(
            selected = family == GenerateKeyFamily.ECDSA,
            onClick = { family = GenerateKeyFamily.ECDSA },
            label = { Text("ECDSA") },
        )
        FilterChip(
            selected = family == GenerateKeyFamily.RSA,
            onClick = { family = GenerateKeyFamily.RSA },
            label = { Text("RSA") },
        )
    }

    if (family == GenerateKeyFamily.ECDSA) {
        Text(
            text = ws.credentialsCurveSizeLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
            listOf(256, 384, 521).forEach { bits ->
                FilterChip(
                    selected = ecdsaBits == bits,
                    onClick = { ecdsaBits = bits },
                    label = { Text("$bits-bit") },
                )
            }
        }
    }

    if (family == GenerateKeyFamily.RSA) {
        Text(
            text = ws.credentialsKeySizeLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
            listOf(3072, 4096, 2048).forEach { bits ->
                FilterChip(
                    selected = rsaBits == bits,
                    onClick = { rsaBits = bits },
                    label = { Text("$bits-bit") },
                )
            }
        }
    }

    OutlinedTextField(
        value = passphrase,
        onValueChange = {
            passphrase = it
            if (it.isEmpty()) savePassphrase = false
        },
        label = { Text(ws.credentialsPassphraseLabel) },
        placeholder = { Text(ws.credentialsPassphraseOptionalHint) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        shape = RoundedCornerShape(Corners.Md),
        modifier = Modifier.fillMaxWidth(),
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = savePassphrase,
            enabled = passphrase.isNotEmpty(),
            onCheckedChange = { savePassphrase = it },
        )
        Text(
            text = ws.credentialsSavePassphrase,
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (passphrase.isNotEmpty()) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }

    Button(
        enabled = name.isNotBlank(),
        onClick = {
            val algo =
                when (family) {
                    GenerateKeyFamily.ED25519 -> SshKeyAlgorithm.ED25519
                    GenerateKeyFamily.ECDSA ->
                        when (ecdsaBits) {
                            384 -> SshKeyAlgorithm.ECDSA_384
                            521 -> SshKeyAlgorithm.ECDSA_521
                            else -> SshKeyAlgorithm.ECDSA_256
                        }
                    GenerateKeyFamily.RSA ->
                        when (rsaBits) {
                            4096 -> SshKeyAlgorithm.RSA_4096
                            2048 -> SshKeyAlgorithm.RSA_2048
                            else -> SshKeyAlgorithm.RSA_3072
                        }
                }
            val pair = generateSshKeyPair(algorithm = algo, passphrase = passphrase)
            val inspected = inspectSshPrivateKey(pair.privateKeyPem, passphrase.takeIf { it.isNotEmpty() })
            val valid = inspected as? SshKeyValidationResult.Valid
            onSave(
                KeychainCredential(
                    id = newId(),
                    name = name.trim().ifEmpty { "SSH Key" },
                    kind = CredentialKind.PRIVATE_KEY,
                    updatedAt = Clock.System.now().toEpochMilliseconds(),
                    isActive = true,
                    keyAlgorithm = valid?.algorithmLabel ?: algo.displayLabel,
                    fingerprintSha256 = valid?.fingerprintSha256.orEmpty(),
                    savePassphrase = savePassphrase && passphrase.isNotEmpty(),
                ),
                pair.privateKeyPem,
                passphrase.takeIf { savePassphrase && it.isNotEmpty() },
            )
        },
        shape = RoundedCornerShape(Corners.Md),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(ws.keychainModeGenerate)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddCertificateSheetBody(
    autoLaunchPicker: Boolean,
    existingCredentials: List<KeychainCredential>,
    onSave: (KeychainCredential, certText: String) -> Unit,
    onQuickSaveKey: (KeychainCredential, secret: String, passphrase: String?) -> Unit,
    onCancel: () -> Unit,
) {
    val s = LocalAppStrings.current
    val ws = s.workspace
    val clipboard = LocalClipboardManager.current

    val savedKeys = remember(existingCredentials) { existingCredentials.filter { it.kind == CredentialKind.PRIVATE_KEY } }
    var extraKey by remember { mutableStateOf<KeychainCredential?>(null) }
    val allKeys = remember(savedKeys, extraKey) { listOfNotNull(extraKey) + savedKeys }

    var certText by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var selectedKeyId by remember { mutableStateOf<String?>(null) }
    var parseResult by remember { mutableStateOf<OpenSshCertificateParseResult?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var pickerLaunched by remember { mutableStateOf(false) }

    fun validateCert(text: String = certText) {
        fileError = null
        val res = parseOpenSshCertificate(text)
        parseResult = res
        if (res is OpenSshCertificateParseResult.Valid) {
            val cert = res.certificate
            if (name.isBlank()) {
                name = cert.keyId.ifBlank { cert.comment }.ifBlank { "SSH Certificate" }
            }
            val matched = allKeys.firstOrNull { it.fingerprintSha256 == cert.embeddedPublicKeyFingerprintSha256 }
            if (matched != null) {
                selectedKeyId = matched.id
            }
        }
    }

    val pickCertFile =
        rememberFilePicker { picked ->
            when (val result = readPickedKeyText(picked)) {
                is KeyImportResult.Loaded -> {
                    certText = result.content
                    if (name.isBlank()) {
                        name = picked.name.substringBeforeLast('.').ifBlank { picked.name }
                    }
                    validateCert(result.content)
                }
                KeyImportResult.TooLarge -> fileError = ws.keychainImportTooLarge
                KeyImportResult.Failed -> fileError = ws.keychainImportFailed
            }
        }

    val pickMatchingKeyFile =
        rememberFilePicker { picked ->
            when (val result = readPickedKeyText(picked)) {
                is KeyImportResult.Loaded -> {
                    val inspected = inspectSshPrivateKey(result.content)
                    if (inspected is SshKeyValidationResult.Valid) {
                        val keyCred =
                            KeychainCredential(
                                id = newId(),
                                name = picked.name.substringBeforeLast('.').ifBlank { inspected.algorithmLabel },
                                kind = CredentialKind.PRIVATE_KEY,
                                updatedAt = Clock.System.now().toEpochMilliseconds(),
                                isActive = true,
                                keyAlgorithm = inspected.algorithmLabel,
                                fingerprintSha256 = inspected.fingerprintSha256,
                            )
                        onQuickSaveKey(keyCred, result.content.trim(), null)
                        extraKey = keyCred
                        selectedKeyId = keyCred.id
                    } else {
                        fileError = ws.keychainImportFailed
                    }
                }
                KeyImportResult.TooLarge -> fileError = ws.keychainImportTooLarge
                KeyImportResult.Failed -> fileError = ws.keychainImportFailed
            }
        }

    LaunchedEffect(autoLaunchPicker) {
        if (autoLaunchPicker && !pickerLaunched) {
            pickerLaunched = true
            pickCertFile()
        }
    }

    Text(
        text = if (autoLaunchPicker) ws.credentialsImportCert else ws.credentialsPasteCert,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )

    if (autoLaunchPicker) {
        OutlinedButton(
            onClick = pickCertFile,
            shape = RoundedCornerShape(Corners.Md),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Text(ws.credentialsImportCert)
            }
        }
    }

    OutlinedTextField(
        value = certText,
        onValueChange = {
            certText = it
            parseResult = null
            fileError = null
        },
        label = { Text(ws.credentialsKindCertificate) },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = monospaceFontFamily()),
        minLines = 3,
        maxLines = 6,
        trailingIcon = {
            IconButton(
                onClick = {
                    val clip = clipboard.getText()?.text.orEmpty()
                    if (clip.isNotBlank()) {
                        certText = clip
                        parseResult = null
                        fileError = null
                    }
                },
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = ws.credentialsPasteButton)
            }
        },
        shape = RoundedCornerShape(Corners.Md),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedButton(
        enabled = certText.isNotBlank(),
        onClick = { validateCert() },
        shape = RoundedCornerShape(Corners.Md),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(ws.credentialsValidateButton)
    }

    fileError?.let {
        Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    when (val res = parseResult) {
        null -> Unit
        is OpenSshCertificateParseResult.Invalid -> {
            Text(text = res.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        is OpenSshCertificateParseResult.Valid -> {
            val cert: ParsedOpenSshCertificate = res.certificate
            val nowSec = Clock.System.now().epochSeconds
            val expired = cert.isExpiredAt(nowSec)
            val selectedKey = allKeys.firstOrNull { it.id == selectedKeyId }
            val mismatch =
                selectedKey != null &&
                    selectedKey.fingerprintSha256.isNotBlank() &&
                    selectedKey.fingerprintSha256 != cert.embeddedPublicKeyFingerprintSha256

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(ws.keychainNameLabel) },
                singleLine = true,
                shape = RoundedCornerShape(Corners.Md),
                modifier = Modifier.fillMaxWidth(),
            )

            Surface(
                shape = RoundedCornerShape(Corners.Md),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.Md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                ) {
                    Text(
                        text = ws.credentialsCertValidStatus,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "${ws.credentialsKeyTypeLabel}: ${cert.certType}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (cert.principals.isNotEmpty()) {
                        Text(
                            text = "${ws.credentialsCertPrincipalsLabel}: ${cert.principals.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        text = "${ws.credentialsCertValidityLabel}: ${formatCertValidity(cert.validAfterEpochSec, cert.validBeforeEpochSec)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${ws.credentialsFingerprintLabel}: ${cert.embeddedPublicKeyFingerprintSha256}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = monospaceFontFamily()),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = ws.credentialsLinkedKeyLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (allKeys.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                ) {
                    allKeys.forEach { keyCred ->
                        FilterChip(
                            selected = selectedKeyId == keyCred.id,
                            onClick = { selectedKeyId = if (selectedKeyId == keyCred.id) null else keyCred.id },
                            label = { Text(keyCred.name) },
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = pickMatchingKeyFile,
                shape = RoundedCornerShape(Corners.Md),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(Sizes.IconSmall))
                    Text(ws.credentialsImportKey)
                }
            }

            if (mismatch) {
                Text(
                    text = ws.credentialsCertMismatchError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (expired) {
                Text(
                    text = ws.credentialsCertExpiredWarning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (selectedKeyId == null) {
                Text(
                    text = ws.credentialsCertNeedsKeyWarning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val isFullyActive = !expired && selectedKeyId != null && !mismatch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Corners.Md),
                ) {
                    Text(s.quickConnect.cancelAction)
                }
                Button(
                    enabled = !mismatch && name.isNotBlank(),
                    onClick = {
                        onSave(
                            KeychainCredential(
                                id = newId(),
                                name = name.trim(),
                                kind = CredentialKind.CERTIFICATE,
                                usernameHint = cert.principals.firstOrNull().orEmpty(),
                                updatedAt = Clock.System.now().toEpochMilliseconds(),
                                isActive = isFullyActive,
                                keyAlgorithm = cert.certType,
                                fingerprintSha256 = cert.embeddedPublicKeyFingerprintSha256,
                                linkedPrivateKeyId = selectedKeyId,
                            ),
                            cert.normalizedLine,
                        )
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Corners.Md),
                ) {
                    Text(if (isFullyActive) s.editSave else ws.credentialsSaveAnyway)
                }
            }
        }
    }
}

@Composable
private fun PasswordEditDialog(
    existing: KeychainCredential?,
    onDismiss: () -> Unit,
    onSave: (KeychainCredential, secret: String) -> Unit,
) {
    val s = LocalAppStrings.current
    val ws = s.workspace
    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var usernameHint by remember(existing) { mutableStateOf(existing?.usernameHint ?: "") }
    var secret by remember(existing) { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    val canSave = name.isNotBlank() && (existing != null || secret.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing != null) ws.keychainEdit else ws.keychainNewPassword) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
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
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text(ws.keychainSecretPasswordLabel) },
                    placeholder =
                        if (existing != null) {
                            { Text(ws.keychainSecretKeepHint) }
                        } else {
                            null
                        },
                    singleLine = true,
                    visualTransformation =
                        if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector =
                                    if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                    shape = RoundedCornerShape(Corners.Md),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        KeychainCredential(
                            id = existing?.id ?: newId(),
                            name = name.trim(),
                            kind = CredentialKind.PASSWORD,
                            usernameHint = usernameHint.trim(),
                            updatedAt = Clock.System.now().toEpochMilliseconds(),
                        ),
                        secret.trim(),
                    )
                },
            ) {
                Text(s.editSave)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(s.quickConnect.cancelAction)
            }
        },
    )
}

private sealed interface KeyImportResult {
    data class Loaded(
        val content: String,
    ) : KeyImportResult

    data object TooLarge : KeyImportResult

    data object Failed : KeyImportResult
}

private fun readPickedKeyText(file: PickedFile): KeyImportResult {
    return try {
        if (file.size > MAX_KEY_FILE_BYTES) {
            KeyImportResult.TooLarge
        } else {
            val chunks = ArrayList<ByteArray>(4)
            var total = 0
            while (true) {
                val chunk = file.readChunk() ?: break
                total += chunk.size
                if (total > MAX_KEY_FILE_BYTES) {
                    return KeyImportResult.TooLarge
                }
                chunks.add(chunk)
            }
            val combined = ByteArray(total)
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(combined, offset)
                offset += chunk.size
            }
            val text = combined.decodeToString()
            if (text.isBlank()) KeyImportResult.Failed else KeyImportResult.Loaded(text)
        }
    } catch (_: Exception) {
        KeyImportResult.Failed
    } finally {
        file.close()
    }
}
