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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.macodes.crypto.generateOpenSshEd25519KeyPair
import dev.macodes.data.CredentialKind
import dev.macodes.data.Host
import dev.macodes.data.HostRepository
import dev.macodes.data.KeychainCredential
import dev.macodes.data.deleteKeychainSecret
import dev.macodes.data.newId
import dev.macodes.data.saveKeychainSecret
import dev.macodes.ui.theme.Corners
import dev.macodes.ui.theme.Sizes
import dev.macodes.ui.theme.Spacing
import dev.macodes.util.monospaceFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

private const val MAX_KEY_FILE_BYTES = 256 * 1024

private enum class SshKeySourceMode {
    PASTE,
    IMPORT,
    GENERATE,
}

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
    var editing by remember { mutableStateOf<KeychainCredential?>(null) }
    var editorInitialKind by remember { mutableStateOf(CredentialKind.PRIVATE_KEY) }
    var showEditor by remember { mutableStateOf(false) }
    var fabMenuExpanded by remember { mutableStateOf(false) }
    var showKindChooser by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<List<KeychainCredential>>(emptyList()) }
    val searchFocusRequester = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val selectionMode = selectedIds.isNotEmpty()

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
                cred.usernameHint.contains(q, ignoreCase = true)
        }

    fun refresh() {
        credentials = repository.listCredentials()
        onCredentialsChanged()
    }

    fun toggleSelection(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun openNewEditor(kind: CredentialKind) {
        editing = null
        editorInitialKind = kind
        showEditor = true
    }

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
                            editing = target
                            editorInitialKind = target.kind
                            selectedIds = emptySet()
                            showEditor = true
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
                Box {
                    FloatingActionButton(
                        onClick = { fabMenuExpanded = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(Corners.Lg),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = ws.keychainAdd)
                    }
                    DropdownMenu(
                        expanded = fabMenuExpanded,
                        onDismissRequest = { fabMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(ws.keychainNewSshKey) },
                            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                            onClick = {
                                fabMenuExpanded = false
                                openNewEditor(CredentialKind.PRIVATE_KEY)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(ws.keychainNewPassword) },
                            leadingIcon = { Icon(Icons.Default.Password, contentDescription = null) },
                            onClick = {
                                fabMenuExpanded = false
                                openNewEditor(CredentialKind.PASSWORD)
                            },
                        )
                    }
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
                            { showKindChooser = true }
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
                    items(filtered, key = { it.id }) { cred ->
                        val linkedCount = usageByCredential[cred.id] ?: 0
                        KeychainCredentialCard(
                            credential = cred,
                            linkedCount = linkedCount,
                            selected = cred.id in selectedIds,
                            onClick = {
                                if (selectionMode) {
                                    toggleSelection(cred.id)
                                } else {
                                    editing = cred
                                    editorInitialKind = cred.kind
                                    showEditor = true
                                }
                            },
                            onLongClick = { toggleSelection(cred.id) },
                        )
                    }
                }
            }
        }
    }

    if (showKindChooser) {
        AlertDialog(
            onDismissRequest = { showKindChooser = false },
            title = { Text(ws.keychainAdd) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                    OutlinedButton(
                        onClick = {
                            showKindChooser = false
                            openNewEditor(CredentialKind.PRIVATE_KEY)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Corners.Md),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null)
                            Text(ws.keychainNewSshKey)
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            showKindChooser = false
                            openNewEditor(CredentialKind.PASSWORD)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Corners.Md),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Password, contentDescription = null)
                            Text(ws.keychainNewPassword)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showKindChooser = false }) {
                    Text(s.quickConnect.cancelAction)
                }
            },
        )
    }

    if (showEditor) {
        KeychainEditDialog(
            existing = editing,
            initialKind = editorInitialKind,
            onMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
            onDismiss = { showEditor = false },
            onSave = { cred, secret ->
                if (secret.isNotBlank()) {
                    saveKeychainSecret(cred.id, cred.kind, secret)
                }
                repository.upsertCredential(cred)
                showEditor = false
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
        if (credential.kind == CredentialKind.PRIVATE_KEY) {
            ws.keychainKindKey
        } else {
            ws.keychainKindPassword
        }
    val icon =
        if (credential.kind == CredentialKind.PRIVATE_KEY) {
            Icons.Default.Key
        } else {
            Icons.Default.Password
        }
    val linkLabel = if (linkedCount > 0) ws.keychainUsedByCount(linkedCount) else ws.keychainUnused
    val subtitleText =
        if (credential.usernameHint.isNotBlank()) {
            "${credential.usernameHint} · $linkLabel"
        } else {
            linkLabel
        }

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
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
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
private fun KeychainEditDialog(
    existing: KeychainCredential?,
    initialKind: CredentialKind,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (KeychainCredential, secret: String) -> Unit,
) {
    val s = LocalAppStrings.current
    val ws = s.workspace
    val clipboard = LocalClipboardManager.current
    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    val kind = existing?.kind ?: initialKind
    var usernameHint by remember(existing) { mutableStateOf(existing?.usernameHint ?: "") }
    var secret by remember(existing) { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var keySourceMode by remember(existing) { mutableStateOf(SshKeySourceMode.PASTE) }
    var importedFileName by remember(existing) { mutableStateOf<String?>(null) }
    var importError by remember(existing) { mutableStateOf<String?>(null) }
    var generatedPublicKey by remember(existing) { mutableStateOf<String?>(null) }

    val pickKeyFile =
        rememberFilePicker { picked ->
            when (val result = readPickedKeyText(picked)) {
                is KeyImportResult.Loaded -> {
                    secret = result.content
                    importedFileName = picked.name
                    importError = null
                    generatedPublicKey = null
                    if (name.isBlank()) {
                        name = picked.name.substringBeforeLast('.').ifBlank { picked.name }
                    }
                }
                KeyImportResult.TooLarge -> {
                    importError = ws.keychainImportTooLarge
                }
                KeyImportResult.Failed -> {
                    importError = ws.keychainImportFailed
                }
            }
        }

    val canSave = name.isNotBlank() && (existing != null || secret.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    existing != null -> ws.keychainEdit
                    kind == CredentialKind.PRIVATE_KEY -> ws.keychainNewSshKey
                    else -> ws.keychainNewPassword
                },
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
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
                if (kind == CredentialKind.PRIVATE_KEY) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                        FilterChip(
                            selected = keySourceMode == SshKeySourceMode.PASTE,
                            onClick = { keySourceMode = SshKeySourceMode.PASTE },
                            label = { Text(ws.keychainModePaste) },
                        )
                        FilterChip(
                            selected = keySourceMode == SshKeySourceMode.IMPORT,
                            onClick = { keySourceMode = SshKeySourceMode.IMPORT },
                            label = { Text(ws.keychainModeImport) },
                        )
                        FilterChip(
                            selected = keySourceMode == SshKeySourceMode.GENERATE,
                            onClick = { keySourceMode = SshKeySourceMode.GENERATE },
                            label = { Text(ws.keychainModeGenerate) },
                        )
                    }
                    when (keySourceMode) {
                        SshKeySourceMode.PASTE -> {
                            OutlinedTextField(
                                value = secret,
                                onValueChange = { secret = it },
                                label = { Text(ws.keychainSecretKeyLabel) },
                                placeholder =
                                    if (existing != null) {
                                        { Text(ws.keychainSecretKeepHint) }
                                    } else {
                                        null
                                    },
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = monospaceFontFamily()),
                                minLines = 3,
                                maxLines = 6,
                                shape = RoundedCornerShape(Corners.Md),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        SshKeySourceMode.IMPORT -> {
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
                            importError?.let { err ->
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        SshKeySourceMode.GENERATE -> {
                            OutlinedButton(
                                onClick = {
                                    val comment = usernameHint.trim().ifEmpty { "macodes" }
                                    val pair = generateOpenSshEd25519KeyPair(comment = comment)
                                    secret = pair.privateKeyPem
                                    generatedPublicKey = pair.publicKeyLine
                                    importedFileName = null
                                    importError = null
                                    if (name.isBlank()) {
                                        name = "Ed25519"
                                    }
                                },
                                shape = RoundedCornerShape(Corners.Md),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                                    Text(
                                        if (generatedPublicKey == null) {
                                            ws.keychainGenerateAction
                                        } else {
                                            ws.keychainRegenerateAction
                                        },
                                    )
                                }
                            }
                            generatedPublicKey?.let { pubLine ->
                                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                                    Text(
                                        text = ws.keychainPublicKeyLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(Corners.Md),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(
                                                        start = Spacing.Md,
                                                        end = Spacing.Xs,
                                                        top = Spacing.Xs,
                                                        bottom = Spacing.Xs,
                                                    ),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                                        ) {
                                            Text(
                                                text = pubLine,
                                                style =
                                                    MaterialTheme.typography.bodySmall.copy(
                                                        fontFamily = monospaceFontFamily(),
                                                    ),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            IconButton(
                                                onClick = {
                                                    clipboard.setText(AnnotatedString(pubLine))
                                                    onMessage(ws.keychainPublicKeyCopied)
                                                },
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.ContentCopy,
                                                    contentDescription = ws.keychainPublicKeyCopied,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(Sizes.IconSmall),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
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
                                        if (passwordVisible) {
                                            Icons.Default.VisibilityOff
                                        } else {
                                            Icons.Default.Visibility
                                        },
                                    contentDescription = null,
                                )
                            }
                        },
                        shape = RoundedCornerShape(Corners.Md),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val cred =
                        KeychainCredential(
                            id = existing?.id ?: newId(),
                            name = name.trim(),
                            kind = kind,
                            usernameHint = usernameHint.trim(),
                            updatedAt = Clock.System.now().toEpochMilliseconds(),
                        )
                    onSave(cred, secret.trim())
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
