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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
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
    val suggestions =
        remember(commandInput, savedHosts, history) {
            buildQuickConnectSuggestions(commandInput, savedHosts, history)
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
            MacodesLargeHeader(title = s.appTabConnections)
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
                    Text(
                        text = s.connSectionTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = Spacing.Xs),
                    )
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
                    )
                }
            }

            if (showHistory && history.isNotEmpty()) {
                item(key = "quick-connect-history") {
                    QuickConnectHistoryGroup(
                        history = history,
                        onSelect = onSelectHistory,
                        onEdit = onEditHistory,
                        onSaveToHosts = onSaveHistoryToHosts,
                        onDelete = onDeleteHistory,
                        onClear = onClearHistory,
                    )
                }
            }

            if (sessions.isEmpty() && (!showHistory || history.isEmpty())) {
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
}

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
    onSelect: (QuickConnectHistoryEntry) -> Unit,
    onEdit: (QuickConnectHistoryEntry) -> Unit,
    onSaveToHosts: (QuickConnectHistoryEntry) -> Unit,
    onDelete: (QuickConnectHistoryEntry) -> Unit,
    onClear: () -> Unit,
) {
    val s = LocalAppStrings.current.quickConnect
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
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
            TextButton(onClick = onClear) {
                Text(s.clearHistoryAction, style = MaterialTheme.typography.labelMedium)
            }
        }

        history.forEach { entry ->
            QuickConnectHistoryRow(
                entry = entry,
                onClick = { onSelect(entry) },
                onEdit = { onEdit(entry) },
                onSaveToHosts = { onSaveToHosts(entry) },
                onDelete = { onDelete(entry) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickConnectHistoryRow(
    entry: QuickConnectHistoryEntry,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onSaveToHosts: () -> Unit,
    onDelete: () -> Unit,
) {
    val appStrings = LocalAppStrings.current
    val s = appStrings.quickConnect
    val isMosh = entry.protocol == ConnectionMode.MOSH
    val sys = entry.system.ifBlank { entry.host }
    val badgeLabel =
        when {
            entry.openSftp -> s.sftpPill
            isMosh -> appStrings.hostsModeMosh
            else -> appStrings.hostsModeSsh
        }
    val subtitle = formatQuickConnectHistorySubtitle(entry, s)
    var menuExpanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Corners.Lg))
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = { menuExpanded = true },
                    ),
            shape = RoundedCornerShape(Corners.Lg),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.Md, top = Spacing.Sm, bottom = Spacing.Sm, end = Spacing.Xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
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
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
                    ) {
                        Text(
                            text = "${entry.username}@${entry.host}",
                            style = MaterialTheme.typography.titleSmall,
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
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = LocalAppStrings.current.connRemove,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(Sizes.IconSmall),
                    )
                }
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(s.editConnectionAction) },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(s.saveToHostsAction) },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onSaveToHosts()
                },
            )
        }
    }
}

@Composable
private fun SessionConnectionCard(
    item: HostSessionItem,
    showPreview: Boolean,
    onOpen: () -> Unit,
    onReconnect: () -> Unit,
    onClose: () -> Unit,
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
    val subtitle =
        when (item) {
            is HostSessionItem.Sftp -> "sftp ${host.username}@${host.hostname} -p ${host.port}"
            is HostSessionItem.Agent -> {
                val stateLabel =
                    when {
                        item.controller.pendingApprovals.isNotEmpty() -> s.nativeAgents.sessionWaitingApproval
                        item.controller.busy -> s.nativeAgents.sessionRunning
                        item.isConnecting -> s.connStatusConnecting
                        item.isConnected -> s.hostsActive
                        else -> s.connStatusClosed
                    }
                "${host.username}@${host.hostname} · ${s.hostsActionAgents} · $stateLabel"
            }
            is HostSessionItem.Terminal -> {
                val proto =
                    if (host.connectionMode == ConnectionMode.MOSH) s.hostsModeMosh else s.hostsModeSsh
                val modeSuffix =
                    if (item.controller.launchMode == TerminalLaunchMode.HERDR) " · ${s.hostsActionHerdr}" else ""
                "$proto ${host.username}@${host.hostname} -p ${host.port}$modeSuffix"
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
    val active = item.isActive
    val kindLabel =
        when (item) {
            is HostSessionItem.Sftp -> "SFTP"
            is HostSessionItem.Agent -> "AGENT"
            is HostSessionItem.Terminal ->
                if (host.connectionMode == ConnectionMode.MOSH) "MOSH" else "SSH"
        }

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
                .clickable(onClick = onOpen),
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
                        .padding(start = Spacing.Md, top = Spacing.Sm, bottom = Spacing.Sm, end = Spacing.Xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                Box(Modifier.size(Sizes.AvatarCard)) {
                    Box(
                        Modifier
                            .fillMaxSize()
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
                    if (!isConnecting) {
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                                .border(2.dp, MaterialTheme.colorScheme.surfaceContainer, CircleShape),
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
                    ) {
                        Text(
                            text = title,
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
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!active && item is HostSessionItem.Terminal) {
                    IconButton(onClick = onReconnect) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = s.quickConnect.reconnectContentDescription,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(onClick = onClose) {
                    if (active) {
                        Icon(
                            Icons.Default.LinkOff,
                            contentDescription = s.connDisconnect,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = s.connRemove,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
private fun ProtocolBadge(label: String) {
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
                            subtitle = formatQuickConnectHistorySubtitle(entry, s),
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

internal fun formatQuickConnectHistorySubtitle(
    entry: QuickConnectHistoryEntry,
    s: QuickConnectStrings,
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
    parts.add(formatSessionCreatedAt(entry.lastConnectedAt))
    return parts.joinToString(" · ")
}
