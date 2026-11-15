package dev.macodes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.macodes.data.ConnectionMode
import dev.macodes.data.Host
import dev.macodes.data.QuickConnectHistoryEntry
import dev.macodes.data.QuickConnectParseResult
import dev.macodes.data.QuickConnectParser
import dev.macodes.data.QuickConnectTarget
import dev.macodes.term.TerminalBuffer
import dev.macodes.ui.theme.Corners
import dev.macodes.ui.theme.Sizes
import dev.macodes.ui.theme.Spacing
import dev.macodes.ui.theme.StatusColors

/** Connections screen featuring Smart Quick Connect bar, recent passwordless history, and live sessions. */
@Composable
fun ConnectionsScreen(
    sessions: List<HostSessionItem>,
    history: List<QuickConnectHistoryEntry> = emptyList(),
    savedHosts: List<Host> = emptyList(),
    showSessionPreview: Boolean = true,
    showHistory: Boolean = true,
    showSftp: Boolean = true,
    onQuickConnect: (QuickConnectTarget) -> Unit = {},
    onSelectSavedHost: (Host) -> Unit = {},
    onSelectHistory: (QuickConnectHistoryEntry) -> Unit = {},
    onEditHistory: (QuickConnectHistoryEntry) -> Unit = {},
    onSaveHistoryToHosts: (QuickConnectHistoryEntry) -> Unit = {},
    onDeleteHistory: (QuickConnectHistoryEntry) -> Unit = {},
    onClearHistory: () -> Unit = {},
    onOpenSftp: () -> Unit = {},
    onReconnect: (HostSessionItem) -> Unit = {},
    onOpen: (HostSessionItem) -> Unit,
    onClose: (HostSessionItem) -> Unit,
) {
    val s = LocalAppStrings.current
    var commandInput by rememberSaveable { mutableStateOf("") }
    var parseError by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedHistoryKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDeleteHistory by remember { mutableStateOf<List<QuickConnectHistoryEntry>>(emptyList()) }
    var pendingRemoveSession by remember { mutableStateOf<HostSessionItem?>(null) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    val activeSessionSignatures =
        remember(sessions) {
            sessions
                .map { item ->
                    val h =
                        when (item) {
                            is HostSessionItem.Terminal -> item.controller.host
                            is HostSessionItem.Sftp -> item.host
                            is HostSessionItem.Agent -> item.controller.host
                        }
                    Triple(h.hostname, h.port, h.username)
                }.toSet()
        }
    val visibleHistory =
        remember(history, activeSessionSignatures) {
            history.filterNot { entry -> Triple(entry.host, entry.port, entry.username) in activeSessionSignatures }
        }
    val suggestions =
        remember(commandInput, savedHosts, history) {
            buildQuickConnectSuggestions(commandInput, savedHosts, history)
        }
    val selectionMode = selectedHistoryKeys.isNotEmpty()

    fun toggleHistorySelection(key: String) {
        selectedHistoryKeys =
            if (key in selectedHistoryKeys) selectedHistoryKeys - key else selectedHistoryKeys + key
    }

    fun submitQuickConnect(raw: String = commandInput) {
        when (val result = QuickConnectParser.parse(raw)) {
            is QuickConnectParseResult.Success -> {
                parseError = null
                onQuickConnect(result.target)
            }
            is QuickConnectParseResult.Error -> {
                parseError = formatQuickConnectParseError(result.error, s.quickConnect)
            }
        }
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                val singleTarget =
                    if (selectedHistoryKeys.size == 1) {
                        visibleHistory.firstOrNull { historySelectionKey(it) in selectedHistoryKeys }
                    } else {
                        null
                    }
                WorkspaceSelectionHeader(
                    count = selectedHistoryKeys.size,
                    onClose = { selectedHistoryKeys = emptySet() },
                    extraMenuItems = { dismiss ->
                        if (singleTarget != null) {
                            DropdownMenuItem(
                                text = { Text(s.quickConnect.editConnectionAction) },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = {
                                    dismiss()
                                    selectedHistoryKeys = emptySet()
                                    onEditHistory(singleTarget)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(s.quickConnect.saveToHostsAction) },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                onClick = {
                                    dismiss()
                                    selectedHistoryKeys = emptySet()
                                    onSaveHistoryToHosts(singleTarget)
                                },
                            )
                        }
                        if (visibleHistory.size > 1) {
                            DropdownMenuItem(
                                text = { Text(s.workspace.logsClearAction) },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                                onClick = {
                                    dismiss()
                                    confirmClearHistory = true
                                },
                            )
                        }
                    },
                    onSelectAll = { selectedHistoryKeys = visibleHistory.map(::historySelectionKey).toSet() },
                    onRemove = {
                        val targets = visibleHistory.filter { historySelectionKey(it) in selectedHistoryKeys }
                        if (targets.isNotEmpty()) pendingDeleteHistory = targets
                    },
                )
            } else {
                MacodesLargeHeader(title = s.appTabConnections)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Spacing.Lg, vertical = Spacing.Sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            item(key = "quick-connect-bar") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                    QuickConnectBar(
                        value = commandInput,
                        errorMessage = parseError,
                        onValueChange = {
                            commandInput = it
                            if (parseError != null) parseError = null
                        },
                        onSubmit = { submitQuickConnect() },
                    )
                    if (suggestions.isNotEmpty()) {
                        QuickConnectSuggestionsCard(
                            suggestions = suggestions,
                            onSelectSavedHost = { host ->
                                commandInput = ""
                                parseError = null
                                onSelectSavedHost(host)
                            },
                            onSelectHistory = { entry ->
                                commandInput = ""
                                parseError = null
                                onSelectHistory(entry)
                            },
                        )
                    }
                }
            }

            if (sessions.isNotEmpty()) {
                item(key = "sessions-group-header") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.Xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = s.connSectionTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = s.workspace.sessionCount(sessions.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(
                    items = sessions,
                    key = { item ->
                        when (item) {
                            is HostSessionItem.Terminal -> "term:${item.controller.host.id}:${item.createdAt}"
                            is HostSessionItem.Sftp -> "sftp:${item.host.id}:${item.createdAt}"
                            is HostSessionItem.Agent -> "agent:${item.controller.host.id}:${item.createdAt}"
                        }
                    },
                ) { item ->
                    SessionConnectionCard(
                        item = item,
                        showPreview = showSessionPreview,
                        onOpen = { onOpen(item) },
                        onReconnect = { onReconnect(item) },
                        onClose = { onClose(item) },
                        onRequestRemoveClosed = { pendingRemoveSession = item },
                    )
                }
            }

            if (showHistory && visibleHistory.isNotEmpty()) {
                item(key = "quick-connect-history") {
                    QuickConnectHistoryGroup(
                        history = visibleHistory,
                        selectedKeys = selectedHistoryKeys,
                        onSelect = { entry ->
                            if (selectionMode) {
                                toggleHistorySelection(historySelectionKey(entry))
                            } else {
                                onSelectHistory(entry)
                            }
                        },
                        onLongClick = { entry -> toggleHistorySelection(historySelectionKey(entry)) },
                    )
                }
            }

            if (sessions.isEmpty() && (!showHistory || visibleHistory.isEmpty())) {
                item(key = "connections-empty-note") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Corners.Lg),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Text(
                            text = s.connEmpty,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(Spacing.Lg),
                        )
                    }
                }
            }

            if (showSftp) {
                item(key = "connections-sftp-section") {
                    SftpQuickSection(onOpenSftp = onOpenSftp)
                }
            }
        }
    }

    if (pendingDeleteHistory.isNotEmpty()) {
        val ws = s.workspace
        val message =
            if (pendingDeleteHistory.size == 1) {
                val entry = pendingDeleteHistory.first()
                ws.connRemoveHistoryConfirm("${entry.username}@${entry.host}")
            } else {
                ws.connRemoveSelectedHistoryConfirm(pendingDeleteHistory.size)
            }
        AlertDialog(
            onDismissRequest = { pendingDeleteHistory = emptyList() },
            title = { Text(ws.actionRemove) },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteHistory.forEach(onDeleteHistory)
                        pendingDeleteHistory = emptyList()
                        selectedHistoryKeys = emptySet()
                    },
                ) {
                    Text(ws.actionRemove, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteHistory = emptyList() }) {
                    Text(s.quickConnect.cancelAction)
                }
            },
        )
    }

    if (confirmClearHistory) {
        val ws = s.workspace
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text(s.quickConnect.clearHistoryAction) },
            text = { Text(ws.connClearHistoryConfirm) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearHistory = false
                        selectedHistoryKeys = emptySet()
                        onClearHistory()
                    },
                ) {
                    Text(s.quickConnect.clearHistoryAction, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearHistory = false }) {
                    Text(s.quickConnect.cancelAction)
                }
            },
        )
    }

    pendingRemoveSession?.let { target ->
        val host =
            when (target) {
                is HostSessionItem.Terminal -> target.controller.host
                is HostSessionItem.Sftp -> target.host
                is HostSessionItem.Agent -> target.controller.host
            }
        AlertDialog(
            onDismissRequest = { pendingRemoveSession = null },
            title = { Text(s.connRemove) },
            text = { Text(s.workspace.connRemoveHistoryConfirm("${host.username}@${host.hostname}")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoveSession = null
                        onClose(target)
                    },
                ) {
                    Text(s.connRemove, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveSession = null }) {
                    Text(s.quickConnect.cancelAction)
                }
            },
        )
    }
}

private fun historySelectionKey(entry: QuickConnectHistoryEntry): String = "${entry.protocol.name}|${entry.username}@${entry.host}:${entry.port}|${entry.moshUdpPort ?: ""}|${entry.openSftp}"

@Composable
private fun SftpQuickSection(onOpenSftp: () -> Unit) {
    val s = LocalAppStrings.current.quickConnect
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.Xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        Text(
            text = s.sftpSectionTitle,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Corners.Lg))
                    .clickable(onClick = onOpenSftp),
            shape = RoundedCornerShape(Corners.Lg),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.Md, vertical = Spacing.Md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(Sizes.AvatarCard)
                            .clip(RoundedCornerShape(Corners.Md))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(Sizes.IconMedium),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = s.sftpOpenAction,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = s.sftpSectionSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Sizes.IconMedium),
                )
            }
        }
    }
}

@Composable
internal fun QuickConnectBar(
    value: String,
    errorMessage: String?,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val s = LocalAppStrings.current.quickConnect
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Corners.Lg),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border =
                BorderStroke(
                    Sizes.BorderThin,
                    if (errorMessage != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.Lg, end = Spacing.Xs, top = Spacing.Xs, bottom = Spacing.Xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
            ) {
                Box(
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = s.inputPlaceholder,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        textStyle =
                            MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions =
                            KeyboardOptions(
                                imeAction = ImeAction.Go,
                                autoCorrectEnabled = false,
                            ),
                        keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown &&
                                        (event.key == Key.Enter || event.key == Key.NumPadEnter)
                                    ) {
                                        onSubmit()
                                        true
                                    } else {
                                        false
                                    }
                                },
                    )
                }
                if (value.isNotEmpty()) {
                    IconButton(
                        onClick = { onValueChange("") },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(Sizes.IconSmall),
                        )
                    }
                }
                Surface(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(Corners.Md))
                            .clickable(onClick = onSubmit),
                    shape = RoundedCornerShape(Corners.Md),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        text = s.connectAction,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = Spacing.Md, vertical = 10.dp),
                    )
                }
            }
        }

        if (errorMessage != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
                modifier = Modifier.padding(horizontal = Spacing.Xs),
            ) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun QuickConnectHistoryGroup(
    history: List<QuickConnectHistoryEntry>,
    selectedKeys: Set<String>,
    onSelect: (QuickConnectHistoryEntry) -> Unit,
    onLongClick: (QuickConnectHistoryEntry) -> Unit,
) {
    val s = LocalAppStrings.current.quickConnect
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
        ) {
            Icon(
                Icons.Default.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = s.historySectionTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        history.forEach { entry ->
            QuickConnectHistoryRow(
                entry = entry,
                selected = historySelectionKey(entry) in selectedKeys,
                onClick = { onSelect(entry) },
                onLongClick = { onLongClick(entry) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickConnectHistoryRow(
    entry: QuickConnectHistoryEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val appStrings = LocalAppStrings.current
    val s = appStrings.quickConnect
    val sys = entry.system.ifBlank { entry.host }
    val meta = remember(entry, s) { formatQuickConnectHistoryMeta(entry, s) }
    val timestamp =
        remember(entry.lastConnectedAt, appStrings) {
            splitSessionTimestamp(entry.lastConnectedAt, appStrings.workspace)
        }

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Corners.Lg))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        shape = RoundedCornerShape(Corners.Lg),
        color =
            if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        border =
            BorderStroke(
                width = if (selected) 1.5.dp else Sizes.BorderThin,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Md, vertical = Spacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Sizes.AvatarCard),
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .size(Sizes.AvatarCard)
                            .clip(RoundedCornerShape(Corners.Md))
                            .background(systemColor(sys)),
                    contentAlignment = Alignment.Center,
                ) {
                    SystemAvatarIcon(sys, Sizes.IconMedium)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "${entry.username}@${entry.host}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (timestamp.dateLabel.isNotEmpty()) {
                    Text(
                        text = timestamp.dateLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(
                    text = timestamp.timeLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionConnectionCard(
    item: HostSessionItem,
    showPreview: Boolean,
    onOpen: () -> Unit,
    onReconnect: () -> Unit,
    onClose: () -> Unit,
    onRequestRemoveClosed: () -> Unit,
) {
    val s = LocalAppStrings.current
    val host =
        when (item) {
            is HostSessionItem.Terminal -> item.controller.host
            is HostSessionItem.Sftp -> item.host
            is HostSessionItem.Agent -> item.controller.host
        }
    val sys = host.system.ifBlank { host.hostname }
    val title =
        when (item) {
            is HostSessionItem.Terminal -> item.controller.title
            is HostSessionItem.Sftp -> host.name.ifBlank { host.hostname }
            is HostSessionItem.Agent ->
                item.controller.currentSession?.title ?: host.name.ifBlank { host.hostname }
        }
    val active = item.isActive
    val endpointLabel =
        buildString {
            append("${host.username}@${host.hostname}")
            if (host.port != 22) append(":${host.port}")
        }
    val subtitle =
        when (item) {
            is HostSessionItem.Sftp ->
                if (active) endpointLabel else "$endpointLabel · ${s.connStatusClosed}"
            is HostSessionItem.Agent -> {
                val stateLabel =
                    when {
                        item.controller.pendingApprovals.isNotEmpty() -> s.nativeAgents.sessionWaitingApproval
                        item.controller.busy -> s.nativeAgents.sessionRunning
                        item.isConnecting -> s.connStatusConnecting
                        item.isConnected -> s.hostsActive
                        else -> s.connStatusClosed
                    }
                "$endpointLabel · ${s.hostsActionAgents} · $stateLabel"
            }
            is HostSessionItem.Terminal -> {
                buildString {
                    append(endpointLabel)
                    if (item.controller.launchMode == TerminalLaunchMode.HERDR) {
                        append(" · ${s.hostsActionHerdr}")
                    }
                    if (!active) {
                        append(" · ${s.connStatusClosed}")
                    }
                }
            }
        }
    val isConnecting =
        item.isConnecting ||
            item is HostSessionItem.Terminal &&
            (
                item.controller.status == ConnStatus.CONNECTING ||
                    item.controller.status == ConnStatus.AUTH
            )
    val dotColor =
        when (item) {
            is HostSessionItem.Terminal ->
                statusColor(
                    item.controller.status,
                    item.controller.linkLostSeconds,
                )
            is HostSessionItem.Sftp ->
                if (item.session != null) StatusColors.Connected else StatusColors.Neutral
            is HostSessionItem.Agent ->
                when {
                    item.controller.pendingApprovals.isNotEmpty() -> StatusColors.Warning
                    item.isConnected -> StatusColors.Connected
                    item.isConnecting -> StatusColors.Warning
                    else -> StatusColors.Neutral
                }
        }
    val kindLabel =
        when (item) {
            is HostSessionItem.Sftp -> "SFTP"
            is HostSessionItem.Agent -> "AGENT"
            is HostSessionItem.Terminal ->
                if (host.connectionMode == ConnectionMode.MOSH) "MOSH" else "SSH"
        }
    val statusLabel =
        when (item) {
            is HostSessionItem.Agent ->
                when {
                    item.controller.pendingApprovals.isNotEmpty() -> s.nativeAgents.sessionWaitingApproval
                    item.controller.busy -> s.nativeAgents.sessionRunning
                    item.isConnecting -> s.connStatusConnecting
                    item.isConnected -> s.hostsActive
                    else -> s.connStatusClosed
                }
            else ->
                when {
                    isConnecting -> s.connStatusConnecting
                    active -> s.hostsActive
                    else -> s.connStatusClosed
                }
        }
    val cleanSubtitle = "$kindLabel · ${host.hostname}:${host.port}"

    val previewText =
        if (showPreview && item is HostSessionItem.Terminal) {
            extractTerminalPreviewText(item.controller.buffer)
        } else {
            null
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Corners.Lg))
                .combinedClickable(
                    onClick = {
                        if (!active && item is HostSessionItem.Terminal) {
                            onReconnect()
                        } else {
                            onOpen()
                        }
                    },
                    onLongClick = if (!active) onRequestRemoveClosed else null,
                ),
        shape = RoundedCornerShape(Corners.Lg),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.Md, vertical = Spacing.Md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                Box(
                    Modifier
                        .size(Sizes.AvatarCard)
                        .clip(RoundedCornerShape(Corners.Md))
                        .background(systemColor(sys)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(Sizes.IconMedium),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        SystemAvatarIcon(sys, Sizes.IconMedium)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = cleanSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (active || isConnecting) dotColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (previewText != null) {
                Surface(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = Spacing.Md, end = Spacing.Md, bottom = Spacing.Md),
                    shape = RoundedCornerShape(Corners.Md),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(
                        text = previewText,
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
                    )
                }
            }
        }
    }
}

internal fun extractTerminalPreviewText(
    buffer: TerminalBuffer,
    maxLines: Int = 5,
): String? {
    val lines =
        buffer
            .visibleLines()
            .map { line ->
                buildString {
                    for (cell in line.cells) {
                        if (!cell.isWideTail && cell.codePoint > 0) {
                            append(cell.codePoint.toChar())
                        }
                    }
                }.trimEnd()
            }.dropLastWhile { it.isEmpty() }
    val nonBlank = lines.filter { it.isNotEmpty() }.takeLast(maxLines)
    return if (nonBlank.isEmpty()) null else nonBlank.joinToString("\n")
}

internal fun statusColor(
    status: ConnStatus,
    linkLostSeconds: Int = 0,
): Color =
    when {
        // Link lost (session retained): amber, same source as the terminal-screen status dot/banner
        status == ConnStatus.CONNECTED && linkLostSeconds >= LINK_LOST_THRESHOLD_SECONDS -> StatusColors.Warning
        status == ConnStatus.CONNECTED -> StatusColors.Connected
        status == ConnStatus.CONNECTING || status == ConnStatus.AUTH -> StatusColors.Warning
        status == ConnStatus.ERROR -> StatusColors.Error
        else -> StatusColors.Neutral // CLOSED / IDLE
    }

@Composable
internal fun ProtocolBadge(label: String) {
    Surface(
        shape = RoundedCornerShape(Corners.Sm),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun QuickConnectSuggestionsCard(
    suggestions: List<QuickConnectSuggestion>,
    onSelectSavedHost: (Host) -> Unit,
    onSelectHistory: (QuickConnectHistoryEntry) -> Unit,
) {
    val appStrings = LocalAppStrings.current
    val s = appStrings.quickConnect
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Corners.Lg),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            suggestions.forEachIndexed { index, suggestion ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                when (suggestion) {
                    is QuickConnectSuggestion.SavedHost -> {
                        val host = suggestion.host
                        val protoLabel =
                            if (host.connectionMode == ConnectionMode.MOSH) appStrings.hostsModeMosh else appStrings.hostsModeSsh
                        QuickConnectSuggestionRow(
                            title = host.name.ifBlank { "${host.username}@${host.hostname}" },
                            badgeLabel = protoLabel,
                            subtitle = "${host.username}@${host.hostname}:${host.port} · ${s.savedHostBadge}",
                            onClick = { onSelectSavedHost(host) },
                        )
                    }
                    is QuickConnectSuggestion.History -> {
                        val entry = suggestion.entry
                        val protoLabel =
                            when {
                                entry.openSftp -> s.sftpPill
                                entry.protocol == ConnectionMode.MOSH -> appStrings.hostsModeMosh
                                else -> appStrings.hostsModeSsh
                            }
                        QuickConnectSuggestionRow(
                            title = "${entry.username}@${entry.host}",
                            badgeLabel = protoLabel,
                            subtitle = formatQuickConnectHistorySubtitle(entry, s, appStrings.workspace),
                            onClick = { onSelectHistory(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickConnectSuggestionRow(
    title: String,
    badgeLabel: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                ProtocolBadge(label = badgeLabel)
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Sizes.IconSmall),
        )
    }
}

internal sealed interface QuickConnectSuggestion {
    data class SavedHost(
        val host: Host,
    ) : QuickConnectSuggestion

    data class History(
        val entry: QuickConnectHistoryEntry,
    ) : QuickConnectSuggestion
}

internal fun buildQuickConnectSuggestions(
    query: String,
    savedHosts: List<Host>,
    history: List<QuickConnectHistoryEntry>,
    limit: Int = 3,
): List<QuickConnectSuggestion> {
    val normalized =
        query
            .trim()
            .lowercase()
            .removePrefix("ssh ")
            .removePrefix("mosh ")
            .removePrefix("sftp ")
            .trim()
    if (normalized.isEmpty()) return emptyList()

    val out = mutableListOf<QuickConnectSuggestion>()
    val seen = mutableSetOf<Triple<String, Int, String>>()

    for (host in savedHosts) {
        val haystack = "${host.name} ${host.username}@${host.hostname} ${host.hostname}".lowercase()
        if (normalized in haystack) {
            val key = Triple(host.hostname, host.port, host.username)
            if (seen.add(key)) {
                out.add(QuickConnectSuggestion.SavedHost(host))
                if (out.size >= limit) return out
            }
        }
    }

    for (entry in history) {
        val haystack = "${entry.username}@${entry.host} ${entry.host}".lowercase()
        if (normalized in haystack) {
            val key = Triple(entry.host, entry.port, entry.username)
            if (seen.add(key)) {
                out.add(QuickConnectSuggestion.History(entry))
                if (out.size >= limit) return out
            }
        }
    }

    return out
}

internal fun formatQuickConnectHistoryMeta(
    entry: QuickConnectHistoryEntry,
    s: QuickConnectStrings,
): String {
    val protocolLabel =
        when {
            entry.openSftp -> s.sftpPill
            entry.protocol == ConnectionMode.MOSH -> ConnectionMode.MOSH.name
            else -> ConnectionMode.SSH.name
        }
    return "$protocolLabel · ${s.historyPortTag(entry.port)}"
}

internal fun formatQuickConnectHistorySubtitle(
    entry: QuickConnectHistoryEntry,
    s: QuickConnectStrings,
    ws: WorkspaceStrings? = null,
): String {
    val parts = mutableListOf<String>()
    if (entry.protocol == ConnectionMode.MOSH && !entry.openSftp) {
        if (entry.port != 22) {
            parts.add(s.moshSshPortTag(entry.port))
        }
        if (entry.moshUdpPort > 0) {
            parts.add(s.moshUdpPortTag(entry.moshUdpPort))
        } else if (entry.port == 22) {
            parts.add(s.moshUdpAutoTag)
        }
    } else if (entry.port != 22) {
        parts.add(s.historyPortTag(entry.port))
    }
    parts.add(formatSessionCreatedAt(entry.lastConnectedAt, ws))
    return parts.joinToString(" · ")
}
