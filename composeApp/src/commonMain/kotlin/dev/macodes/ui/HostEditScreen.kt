package dev.macodes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.macodes.data.CloudProvider
import dev.macodes.data.ConnectionMode
import dev.macodes.data.CredentialKind
import dev.macodes.data.Host
import dev.macodes.data.HostAuthMethod
import dev.macodes.data.HostFolder
import dev.macodes.data.HostRepository
import dev.macodes.data.findPotentialDuplicateHosts
import dev.macodes.data.newId
import dev.macodes.ui.theme.Corners
import dev.macodes.ui.theme.Sizes
import dev.macodes.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HostEditScreen(
    existing: Host?,
    onSave: (Host, password: String, privateKey: String) -> Unit,
    onCancel: () -> Unit,
    /** Command snippet entry: Tap to open the snippet management/create page (global library, reuses across hosts). */
    repository: HostRepository = HostRepository(),
    folders: List<HostFolder> = emptyList(),
    existingTags: List<String> = emptyList(),
    initialFolderId: String? = null,
    existingHosts: List<Host> = emptyList(),
    initialTemplate: Host? = null,
) {
    val s = LocalAppStrings.current
    val ws = s.workspace
    SensitiveScreenProtection()
    val seed = existing ?: initialTemplate
    var name by remember {
        mutableStateOf(
            when {
                existing != null -> existing.name
                initialTemplate != null -> "${initialTemplate.name.ifBlank { initialTemplate.hostname }} (Copy)"
                else -> ""
            },
        )
    }
    var hostname by remember { mutableStateOf(seed?.hostname ?: "") }
    var port by remember { mutableStateOf((seed?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(seed?.username ?: "root") }
    var authMethod by remember { mutableStateOf(seed?.authMethod ?: HostAuthMethod.PASSWORD) }
    val credentials = remember(repository) { repository.listCredentials() }
    var credentialId by remember { mutableStateOf(seed?.credentialId?.takeIf { id -> credentials.any { it.id == id } }) }
    var connectionMode by remember { mutableStateOf(seed?.connectionMode ?: ConnectionMode.SSH) }
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf(seed?.tags ?: emptyList()) }
    var folderId by remember {
        mutableStateOf((seed?.folderId ?: initialFolderId)?.takeIf { fid -> folders.any { it.id == fid } })
    }
    var cloudProvider by remember { mutableStateOf(seed?.cloudProvider) }
    var isPinned by remember { mutableStateOf(existing?.isPinned ?: false) }
    var autoDetectInventory by remember { mutableStateOf(seed?.autoDetectInventory ?: false) }
    var showFolderPicker by remember { mutableStateOf(false) }
    // Command Snippet Management Page Overlay (Entry Point for Host Editor Page)
    var showSnippets by remember { mutableStateOf(false) }
    var startupCommand by remember { mutableStateOf(seed?.startupCommand ?: "") }
    var moshThemeSync by remember { mutableStateOf(seed?.moshThemeSync ?: false) }
    var moshUdpPort by remember { mutableStateOf((seed?.moshUdpPort ?: 0).toString()) }
    var pendingDuplicateHost by remember { mutableStateOf<Host?>(null) }

    fun buildHost(): Host {
        val id = existing?.id ?: newId()
        // Preserve repository-written runtime metadata (OS detection, TOFU fingerprint, lastConnectedAt, inventory).
        val latest = repository.getHost(id)
        return Host(
            id = id,
            name = name.ifBlank { hostname },
            hostname = hostname.trim(),
            port = port.toIntOrNull() ?: 22,
            username = username.ifBlank { "root" },
            system = latest?.system ?: existing?.system ?: "",
            authMethod = authMethod,
            connectionMode = connectionMode,
            tags = tags,
            createdAt =
                existing?.createdAt ?: kotlinx.datetime.Clock.System
                    .now()
                    .toEpochMilliseconds(),
            lastConnectedAt = latest?.lastConnectedAt ?: existing?.lastConnectedAt ?: 0L,
            knownHostFingerprint = latest?.knownHostFingerprint ?: existing?.knownHostFingerprint,
            startupCommand = startupCommand.trim(),
            moshThemeSync = moshThemeSync,
            moshUdpPort = moshUdpPort.toIntOrNull()?.takeIf { it in 1024..65535 } ?: 0,
            credentialId = credentialId,
            folderId = folderId?.takeIf { fid -> folders.any { it.id == fid } },
            isPinned = isPinned,
            cloudProvider = cloudProvider,
            autoDetectInventory = autoDetectInventory,
            inventory = latest?.inventory ?: existing?.inventory,
        )
    }

    Scaffold(
        topBar = {
            MacodesHeader(
                title = if (existing == null) s.editAddTitle else s.editEditTitle,
                onBack = onCancel,
                actions = {
                    TextButton(onClick = {
                        val candidate = buildHost()
                        val duplicates =
                            if (existingHosts.isEmpty()) {
                                emptyList()
                            } else {
                                findPotentialDuplicateHosts(candidate, existingHosts)
                            }
                        if (duplicates.isNotEmpty()) {
                            pendingDuplicateHost = candidate
                        } else {
                            onSave(candidate, password, privateKey)
                        }
                    }) { Text(s.editSave) }
                },
            )
        },
    ) { padding ->
        val fieldShape = RoundedCornerShape(Corners.Md)
        val chipColors =
            FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        // When `adjustNothing` is enabled, the keyboard does not scale the window: the IME inset pushes the entire form upward.
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.Lg),
        ) {
            // Section 1: Host & Connection Mode
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Corners.Lg),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(Spacing.Lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Md),
                ) {
                    OutlinedTextField(
                        name,
                        { name = it },
                        Modifier.fillMaxWidth(),
                        label = { Text(s.editName) },
                        singleLine = true,
                        shape = fieldShape,
                    )
                    OutlinedTextField(
                        hostname,
                        { hostname = it },
                        Modifier.fillMaxWidth(),
                        label = { Text(s.editHostname) },
                        singleLine = true,
                        shape = fieldShape,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                        OutlinedTextField(
                            port,
                            { port = it },
                            Modifier.weight(1f),
                            label = { Text(s.editPort) },
                            singleLine = true,
                            shape = fieldShape,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        OutlinedTextField(
                            username,
                            { username = it },
                            Modifier.weight(2f),
                            label = { Text(s.editUsername) },
                            singleLine = true,
                            shape = fieldShape,
                        )
                    }
                    Text(
                        s.editConnectionMode,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                        FilterChip(
                            selected = connectionMode == ConnectionMode.SSH,
                            onClick = { connectionMode = ConnectionMode.SSH },
                            label = { Text(s.editModeSsh) },
                            colors = chipColors,
                        )
                        FilterChip(
                            selected = connectionMode == ConnectionMode.MOSH,
                            onClick = { connectionMode = ConnectionMode.MOSH },
                            label = { Text("Mosh") },
                            colors = chipColors,
                        )
                    }
                    if (connectionMode == ConnectionMode.MOSH) {
                        Text(
                            s.editModeMosh,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Section 2: Authentication
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Corners.Lg),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(Spacing.Lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Md),
                ) {
                    Text(
                        s.editAuthMethod,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (credentials.isNotEmpty()) {
                        Text(
                            s.workspace.keychainHostPickerLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                        ) {
                            FilterChip(
                                selected = credentialId == null,
                                onClick = { credentialId = null },
                                label = { Text(s.workspace.keychainHostCustomOption) },
                                colors = chipColors,
                            )
                            credentials.forEach { cred ->
                                FilterChip(
                                    selected = credentialId == cred.id,
                                    enabled = cred.isActive,
                                    onClick = {
                                        credentialId = cred.id
                                        authMethod =
                                            when (cred.kind) {
                                                CredentialKind.PRIVATE_KEY,
                                                CredentialKind.CERTIFICATE,
                                                -> HostAuthMethod.PRIVATE_KEY
                                                CredentialKind.PASSWORD -> HostAuthMethod.PASSWORD
                                            }
                                        if ((username.isBlank() || username == "root") && cred.usernameHint.isNotBlank()) {
                                            username = cred.usernameHint
                                        }
                                    },
                                    label = {
                                        Text(
                                            if (cred.isActive) cred.name else "${cred.name} (${s.workspace.credentialsInactiveBadge})",
                                        )
                                    },
                                    colors = chipColors,
                                )
                            }
                        }
                    }
                    if (credentialId == null) {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                        ) {
                            FilterChip(
                                selected = authMethod == HostAuthMethod.PASSWORD,
                                onClick = { authMethod = HostAuthMethod.PASSWORD },
                                label = { Text(s.editAuthPassword) },
                                colors = chipColors,
                            )
                            FilterChip(
                                selected = authMethod == HostAuthMethod.PRIVATE_KEY,
                                onClick = { authMethod = HostAuthMethod.PRIVATE_KEY },
                                label = { Text(s.editAuthKey) },
                                colors = chipColors,
                            )
                            FilterChip(
                                selected = authMethod == HostAuthMethod.KEY_OR_PASSWORD,
                                onClick = { authMethod = HostAuthMethod.KEY_OR_PASSWORD },
                                label = { Text(s.editAuthKeyOrPassword) },
                                colors = chipColors,
                            )
                        }
                        if (authMethod != HostAuthMethod.PRIVATE_KEY) {
                            OutlinedTextField(
                                password,
                                { password = it },
                                Modifier.fillMaxWidth(),
                                label = { Text(if (existing == null) s.editPassword else s.editPasswordKeep) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                shape = fieldShape,
                            )
                        }
                        if (authMethod != HostAuthMethod.PASSWORD) {
                            OutlinedTextField(
                                privateKey,
                                { privateKey = it },
                                Modifier.fillMaxWidth(),
                                label = { Text(if (existing == null) s.editPrivateKey else s.editPrivateKeyKeep) },
                                minLines = 3,
                                shape = fieldShape,
                            )
                        }
                    }
                }
            }

            // Section 3: Folder, Tags, Snippets & Startup Command
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Corners.Lg),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(Spacing.Lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Md),
                ) {
                    if (folders.isNotEmpty()) {
                        val selectedFolderName =
                            folders.firstOrNull { it.id == folderId }?.name ?: s.workspace.hostsFolderRoot
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Corners.Md))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { showFolderPicker = true }
                                .padding(horizontal = Spacing.Lg, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(Spacing.Md))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.workspace.editFolder,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    selectedFolderName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = s.workspace.editFolder,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TagInputField(
                        tags = tags,
                        onTagsChange = { tags = it },
                        label = s.editTags,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (existingTags.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                            Text(
                                s.workspace.hostsSuggestedTags,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                                verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                            ) {
                                existingTags.forEach { tag ->
                                    val active = tags.any { it.equals(tag, ignoreCase = true) }
                                    FilterChip(
                                        selected = active,
                                        onClick = {
                                            tags =
                                                if (active) {
                                                    tags.filterNot { it.equals(tag, ignoreCase = true) }
                                                } else {
                                                    tags + tag
                                                }
                                        },
                                        label = { Text("#$tag") },
                                        colors = chipColors,
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Corners.Md))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { showSnippets = true }
                            .padding(horizontal = Spacing.Lg, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(Spacing.Md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.snippetsTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                s.snippetsEmptyHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = s.snippetsTitle,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        startupCommand,
                        { startupCommand = it },
                        Modifier.fillMaxWidth(),
                        label = { Text(s.editStartupCommand) },
                        placeholder = { Text(s.editStartupPlaceholder) },
                        singleLine = true,
                        shape = fieldShape,
                    )
                }
            }

            // Section 4: Cloud Provider, Pin & VPS Inventory Detection
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Corners.Lg),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(Spacing.Lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Md),
                ) {
                    Text(
                        text = ws.hostOrg.cloudProviderLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                    ) {
                        FilterChip(
                            selected = cloudProvider == null,
                            onClick = { cloudProvider = null },
                            label = { Text(ws.hostOrg.providerNotSpecified) },
                            colors = chipColors,
                        )
                        CloudProvider.entries.forEach { provider ->
                            FilterChip(
                                selected = cloudProvider == provider,
                                onClick = { cloudProvider = if (cloudProvider == provider) null else provider },
                                label = { Text(cloudProviderDisplayLabel(provider, ws)) },
                                colors = chipColors,
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            ws.hostOrg.pinToggleLabel,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(
                            checked = isPinned,
                            onCheckedChange = { isPinned = it },
                            colors = macodesSwitchColors(),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                ws.hostOrg.inventoryAutoDetectLabel,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                ws.hostOrg.inventoryAutoDetectHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = autoDetectInventory,
                            onCheckedChange = { autoDetectInventory = it },
                            colors = macodesSwitchColors(),
                        )
                    }
                }
            }

            if (connectionMode == ConnectionMode.MOSH) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Corners.Lg),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(Spacing.Lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Md),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                s.editMoshThemeSync,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Switch(
                                checked = moshThemeSync,
                                onCheckedChange = { moshThemeSync = it },
                                colors = macodesSwitchColors(),
                            )
                        }
                        OutlinedTextField(
                            moshUdpPort,
                            { moshUdpPort = it },
                            Modifier.fillMaxWidth(),
                            label = { Text(s.editMoshUdpPort) },
                            placeholder = { Text("0") },
                            supportingText = { Text(s.editMoshUdpPortHint) },
                            singleLine = true,
                            shape = fieldShape,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }
            }
            Spacer(Modifier.size(Spacing.Lg))
        }
    }

    if (showFolderPicker) {
        FolderPickerDialog(
            title = s.workspace.editFolder,
            folders = folders,
            selectedFolderId = folderId,
            onSelect = {
                folderId = it
                showFolderPicker = false
            },
            onDismiss = { showFolderPicker = false },
        )
    }

    pendingDuplicateHost?.let { duplicateCandidate ->
        AlertDialog(
            onDismissRequest = { pendingDuplicateHost = null },
            title = { Text(ws.hostOrg.duplicateSaveTitle) },
            text = {
                Text(
                    ws.hostOrg.duplicateSaveMessage(
                        "${duplicateCandidate.username}@${duplicateCandidate.hostname}:${duplicateCandidate.port}",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDuplicateHost = null
                        onSave(duplicateCandidate, password, privateKey)
                    },
                ) {
                    Text(ws.hostOrg.saveAnyway)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDuplicateHost = null }) {
                    Text(s.terminalCancel)
                }
            },
        )
    }

    // Intercept system responses when the overlay is open: first close the overlay page and back to the host to edit the form,
    // Instead of navigating directly to the AppRoot and jumping to the home page (which would cause the form to be discarded).
    PlatformBackHandler(enabled = showSnippets) { showSnippets = false }

    // Command snippet overlay: Tap here to enter (Global Library Management/Create; back to continue editing the host)
    AnimatedVisibility(
        visible = showSnippets,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(240)),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(200)),
    ) {
        SnippetManageScreen(
            repository = repository,
            onBack = { showSnippets = false },
        )
    }
}

/**
 * "chip" tag input field: After typing, press Enter or a comma → it turns into a tag bubble that you can delete (like Gmail recipients).
 * - Press Enter or a comma to commit the current input as a tag (supports pasting multiple comma-separated tags at once)
 * - When the input field is empty, press the Backspace key to delete the last tag
 * - Do not commit IME composing states (such as Pinyin); only committed text will be converted to a tag.
 * - Automatically remove duplicates (case-insensitive)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagInputField(
    tags: List<String>,
    onTagsChange: (List<String>) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var state by remember { mutableStateOf(TextFieldValue("")) }
    val input = state.text
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val fieldShape = RoundedCornerShape(Corners.Md)

    // Reading the live state (the onDone callback for the soft keyboard occurs after the last input, so val may be caught and return an outdated value)
    fun commit() {
        val current = state.text
        // Supports input or pasting multiple terms at once (comma-separated); removes duplicates (ignores case).
        val parts = current.split(',', '\n').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }
        val merged = tags.toMutableList()
        for (p in parts) {
            if (merged.none { it.equals(p, ignoreCase = true) }) merged.add(p)
        }
        if (merged != tags) onTagsChange(merged)
        state = TextFieldValue("")
    }

    Box(
        modifier
            .heightIn(min = Sizes.FormFieldMinHeight)
            .border(
                Sizes.BorderThin,
                if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                fieldShape,
            ).clickable { focusRequester.requestFocus() }
            .padding(horizontal = Spacing.Lg)
            .padding(vertical = Spacing.Sm),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            tags.forEach { t ->
                InputChip(
                    selected = true,
                    onClick = {},
                    label = { Text(t) },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .size(InputChipDefaults.IconSize)
                                    .clickable { onTagsChange(tags - t) },
                        )
                    },
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            BasicTextField(
                value = state,
                onValueChange = { new ->
                    // Composing state (pinyin candidates): view updates only, do not commit
                    if (new.composition != null) {
                        state = new
                        return@BasicTextField
                    }
                    state = new
                    if (new.text.contains(',') || new.text.contains('\n')) {
                        commit()
                    }
                },
                // Single line: Do not perform any line breaks for the cursor or placeholder; the width should adapt to the content.
                // (When set to 120dp, the Chinese placeholder wraps to two rows, stretching the height of the input field.)
                singleLine = true,
                // Pressing Enter on the on-screen keyboard triggers the IME action (single-line text boxes do not generate key events),
                // onPreviewKeyEvent only handles hardware keyboards
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                interactionSource = interactionSource,
                modifier =
                    Modifier
                        .widthIn(min = 100.dp)
                        .align(Alignment.CenterVertically)
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown) {
                                when (ev.key) {
                                    Key.Enter, Key.NumPadEnter -> {
                                        commit()
                                        true
                                    }
                                    Key.Backspace -> {
                                        // If the input is empty, pressing the Backspace key deletes the last tag.
                                        if (state.text.isEmpty() && tags.isNotEmpty()) {
                                            onTagsChange(tags.dropLast(1))
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    else -> false
                                }
                            } else {
                                false
                            }
                        },
                // Placeholders and input text share the same `textStyle`: consistent line height, cursor/text alignment
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box {
                        if (tags.isEmpty() && input.isEmpty()) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }
}
