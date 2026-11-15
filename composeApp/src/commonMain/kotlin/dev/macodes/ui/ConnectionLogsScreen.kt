package dev.macodes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.macodes.data.ConnectionLogEntry
import dev.macodes.data.ConnectionLogEvent
import dev.macodes.data.ConnectionLogProtocol
import dev.macodes.data.HostRepository
import dev.macodes.ui.theme.Corners
import dev.macodes.ui.theme.Sizes
import dev.macodes.ui.theme.Spacing
import dev.macodes.ui.theme.StatusColors
import dev.macodes.util.monospaceFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class LogFilter {
    ALL,
    FAILED,
    SSH,
    MOSH,
    SFTP,
}

@Composable
fun ConnectionLogsScreen(
    repository: HostRepository,
    onLogsChanged: () -> Unit,
    onBack: () -> Unit,
) {
    val s = LocalAppStrings.current
    val ws = s.workspace
    var logs by remember { mutableStateOf(repository.listConnectionLogs()) }
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var activeFilter by remember { mutableStateOf(LogFilter.ALL) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var inspecting by remember { mutableStateOf<ConnectionLogEntry?>(null) }
    var pendingDeleteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val selectionMode = selectedIds.isNotEmpty()

    LaunchedEffect(searchExpanded) {
        if (searchExpanded) {
            delay(150)
            searchFocusRequester.requestFocus()
        }
    }

    val q = query.trim()
    val filtered =
        logs.filter { entry ->
            val filterMatches =
                when (activeFilter) {
                    LogFilter.ALL -> true
                    LogFilter.FAILED -> entry.event == ConnectionLogEvent.FAILED
                    LogFilter.SSH -> entry.protocol == ConnectionLogProtocol.SSH
                    LogFilter.MOSH -> entry.protocol == ConnectionLogProtocol.MOSH
                    LogFilter.SFTP -> entry.protocol == ConnectionLogProtocol.SFTP
                }
            val queryMatches =
                q.isEmpty() ||
                    entry.hostLabel.contains(q, ignoreCase = true) ||
                    entry.endpoint.contains(q, ignoreCase = true) ||
                    entry.detail.contains(q, ignoreCase = true)
            filterMatches && queryMatches
        }

    fun toggleSelection(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selectionMode) {
                WorkspaceSelectionHeader(
                    count = selectedIds.size,
                    onClose = { selectedIds = emptySet() },
                    onSelectAll = { selectedIds = filtered.map { it.id }.toSet() },
                    onRemove = {
                        if (selectedIds.isNotEmpty()) pendingDeleteIds = selectedIds
                    },
                )
            } else {
                MacodesLargeHeader(
                    title = ws.logsTitle,
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
                                contentDescription = if (searchExpanded) s.navBack else ws.logsSearch,
                            )
                        }
                        if (logs.isNotEmpty()) {
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = s.navMore,
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(ws.logsClearAction, color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Filled.DeleteSweep,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            showClearConfirm = true
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
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
                    placeholder = ws.logsSearch,
                    modifier =
                        Modifier
                            .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm)
                            .focusRequester(searchFocusRequester),
                )
            }

            if (logs.isNotEmpty()) {
                val filterChips =
                    listOf(
                        LogFilter.ALL to ws.logsFilterAll,
                        LogFilter.FAILED to ws.logsFilterFailed,
                        LogFilter.SSH to ConnectionLogProtocol.SSH.name,
                        LogFilter.MOSH to ConnectionLogProtocol.MOSH.name,
                        LogFilter.SFTP to ConnectionLogProtocol.SFTP.name,
                    )
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.Lg, vertical = Spacing.Xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                ) {
                    filterChips.forEach { (filter, label) ->
                        FilterChip(
                            selected = activeFilter == filter,
                            onClick = {
                                activeFilter =
                                    if (activeFilter == filter && filter != LogFilter.ALL) {
                                        LogFilter.ALL
                                    } else {
                                        filter
                                    }
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }

            if (filtered.isEmpty()) {
                WorkspaceEmptyState(
                    icon = Icons.Default.History,
                    message = if (logs.isEmpty()) ws.logsEmpty else ws.logsNoMatch,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = Spacing.Lg, vertical = Spacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
                ) {
                    items(filtered, key = { it.id }) { entry ->
                        ConnectionLogCard(
                            entry = entry,
                            selected = entry.id in selectedIds,
                            onClick = {
                                if (selectionMode) {
                                    toggleSelection(entry.id)
                                } else {
                                    inspecting = entry
                                }
                            },
                            onLongClick = { toggleSelection(entry.id) },
                        )
                    }
                }
            }
        }
    }

    inspecting?.let { entry ->
        val statusLabel =
            when (entry.event) {
                ConnectionLogEvent.CONNECTED -> ws.logsEventConnected
                ConnectionLogEvent.DISCONNECTED -> ws.logsEventDisconnected
                ConnectionLogEvent.FAILED -> ws.logsEventFailed
            }
        val copySummary =
            buildString {
                append("${entry.hostLabel} (${entry.protocol.name} · $statusLabel)\n")
                append("${formatDisplayEndpoint(entry.endpoint)} · ${formatSessionCreatedAt(entry.timestamp, ws)}")
                if (entry.detail.isNotBlank()) {
                    append("\n${entry.detail}")
                }
            }
        AlertDialog(
            onDismissRequest = { inspecting = null },
            title = { Text(ws.logsDetailTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                    LogDetailRow(label = ws.logsDetailTarget, value = formatDisplayEndpoint(entry.endpoint), monospace = true)
                    LogDetailRow(label = ws.logsDetailProtocol, value = entry.protocol.name)
                    LogDetailRow(label = ws.logsDetailStatus, value = statusLabel)
                    LogDetailRow(label = ws.logsDetailTime, value = formatSessionCreatedAt(entry.timestamp, ws))
                    if (entry.detail.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(Corners.Md),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = entry.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color =
                                    if (entry.event == ConnectionLogEvent.FAILED) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                modifier = Modifier.padding(Spacing.Md),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(copySummary))
                        inspecting = null
                        scope.launch { snackbarHostState.showSnackbar(ws.logsCopiedMessage) }
                    },
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(ws.logsCopyAction)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { inspecting = null }) {
                    Text(s.settingsClose)
                }
            },
        )
    }

    if (pendingDeleteIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingDeleteIds = emptySet() },
            title = { Text(ws.actionRemove) },
            text = { Text(ws.logsDeleteSelectedConfirm(pendingDeleteIds.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        repository.deleteConnectionLogs(pendingDeleteIds)
                        logs = repository.listConnectionLogs()
                        pendingDeleteIds = emptySet()
                        selectedIds = emptySet()
                        onLogsChanged()
                    },
                ) {
                    Text(ws.actionRemove, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteIds = emptySet() }) {
                    Text(s.quickConnect.cancelAction)
                }
            },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(ws.logsClearAction) },
            text = { Text(ws.logsClearConfirm) },
            confirmButton = {
                TextButton(
                    onClick = {
                        repository.clearConnectionLogs()
                        logs = emptyList()
                        selectedIds = emptySet()
                        onLogsChanged()
                        showClearConfirm = false
                    },
                ) {
                    Text(ws.logsClearAction, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(s.quickConnect.cancelAction)
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConnectionLogCard(
    entry: ConnectionLogEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val ws = LocalAppStrings.current.workspace
    val statusColor =
        when (entry.event) {
            ConnectionLogEvent.CONNECTED -> StatusColors.Connected
            ConnectionLogEvent.DISCONNECTED -> StatusColors.Neutral
            ConnectionLogEvent.FAILED -> StatusColors.Error
        }
    val eventLabel =
        when (entry.event) {
            ConnectionLogEvent.CONNECTED -> ws.logsEventConnected
            ConnectionLogEvent.DISCONNECTED -> ws.logsEventDisconnected
            ConnectionLogEvent.FAILED -> ws.logsEventFailed
        }
    val targetMeta = remember(entry.hostLabel, entry.endpoint) { formatConnectionLogTarget(entry.hostLabel, entry.endpoint) }

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
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .size(Sizes.StatusDot)
                            .clip(CircleShape)
                            .background(statusColor),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                ) {
                    Text(
                        text = entry.hostLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatSessionCreatedAt(entry.timestamp, ws),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
                ) {
                    ProtocolBadge(label = entry.protocol.name)
                    Text(
                        text = eventLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    if (targetMeta != null) {
                        Text(
                            text = "· $targetMeta",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (entry.detail.isNotBlank()) {
                    Text(
                        text = entry.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (entry.event == ConnectionLogEvent.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogDetailRow(
    label: String,
    value: String,
    monospace: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style =
                if (monospace) {
                    MaterialTheme.typography.bodySmall.copy(fontFamily = monospaceFontFamily())
                } else {
                    MaterialTheme.typography.bodySmall
                },
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Omit redundant target info when [hostLabel] already names the exact user@host and omit default port `:22`.
 * When [hostLabel] matches `user@host` on a custom port (`user@host:2222`), keep only `:2222`.
 */
internal fun formatConnectionLogTarget(
    hostLabel: String,
    endpoint: String,
): String? {
    val cleanEndpoint = formatDisplayEndpoint(endpoint.trim())
    val cleanLabel = hostLabel.trim()
    if (cleanEndpoint.isBlank()) return null
    if (cleanEndpoint.equals(cleanLabel, ignoreCase = true)) return null
    if (cleanLabel.isNotBlank() && cleanEndpoint.startsWith("$cleanLabel:", ignoreCase = true)) {
        return cleanEndpoint.substring(cleanLabel.length)
    }
    return cleanEndpoint
}
