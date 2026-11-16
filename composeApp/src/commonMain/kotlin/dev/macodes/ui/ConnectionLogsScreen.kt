package dev.macodes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.macodes.data.ConnectionLogProtocol
import dev.macodes.data.HostRepository
import dev.macodes.data.SessionLog
import dev.macodes.data.SessionLogEvent
import dev.macodes.data.SessionLogEventType
import dev.macodes.data.SessionLogQuery
import dev.macodes.data.SessionLogSortOrder
import dev.macodes.data.SessionLogStatus
import dev.macodes.data.filterAndSortSessionLogs
import dev.macodes.ui.theme.Alphas
import dev.macodes.ui.theme.Corners
import dev.macodes.ui.theme.Sizes
import dev.macodes.ui.theme.Spacing
import dev.macodes.ui.theme.StatusColors
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/** Workspace Logs screen: ended SSH, Mosh, and SFTP sessions grouped by date. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConnectionLogsScreen(
    repository: HostRepository,
    onBack: () -> Unit,
    onLogsChanged: () -> Unit = {},
    initialSelectedLogId: String? = null,
    initialFilterSheetOpen: Boolean = false,
) {
    val s = LocalAppStrings.current
    val ws = s.workspace
    val logsVersion by repository.sessionLogsVersion.collectAsState()
    var allLogs by remember { mutableStateOf(repository.listSessionLogs()) }
    LaunchedEffect(logsVersion) {
        allLogs = repository.listSessionLogs()
    }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<SessionLogStatus?>(null) }
    var protocolFilter by remember { mutableStateOf<ConnectionLogProtocol?>(null) }
    var sortOrder by remember { mutableStateOf(SessionLogSortOrder.NEWEST_FIRST) }
    var filterSheetOpen by remember { mutableStateOf(initialFilterSheetOpen) }
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var selectedLogId by remember { mutableStateOf(initialSelectedLogId) }

    val selectedLog =
        remember(allLogs, selectedLogId) {
            selectedLogId?.let { id -> allLogs.firstOrNull { it.id == id } }
        }

    PlatformBackHandler(enabled = selectedLog != null || selectionMode || filterSheetOpen) {
        when {
            filterSheetOpen -> filterSheetOpen = false
            selectedLog != null -> selectedLogId = null
            selectionMode -> {
                selectionMode = false
                selectedIds = emptySet()
            }
        }
    }

    if (selectedLog != null) {
        SessionDetailsScreen(
            log = selectedLog,
            onBack = { selectedLogId = null },
            onDelete = {
                repository.deleteSessionLogs(setOf(selectedLog.id))
                allLogs = repository.listSessionLogs()
                onLogsChanged()
                selectedLogId = null
            },
        )
        return
    }

    val query =
        remember(searchQuery, statusFilter, protocolFilter, sortOrder) {
            SessionLogQuery(
                search = searchQuery,
                status = statusFilter,
                protocol = protocolFilter,
                sortOrder = sortOrder,
            )
        }
    val visibleLogs =
        remember(allLogs, query, ws) {
            filterAndSortSessionLogs(
                logs = allLogs,
                query = query,
                resolveErrorText = ws.logsErrorDescription,
            )
        }
    val hasActiveFilter = statusFilter != null || protocolFilter != null
    val hasQueryOrFilter = searchQuery.isNotBlank() || hasActiveFilter

    val timeZone = remember { TimeZone.currentSystemDefault() }
    val now = remember(allLogs.size) { Clock.System.now() }
    val groupedLogs =
        remember(visibleLogs, ws, timeZone, now) {
            groupSessionLogsByDate(
                logs = visibleLogs,
                ws = ws,
                now = now,
                timeZone = timeZone,
            )
        }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (selectionMode) {
            WorkspaceSelectionHeader(
                count = selectedIds.size,
                onClose = {
                    selectionMode = false
                    selectedIds = emptySet()
                },
                onSelectAll = {
                    selectedIds =
                        if (selectedIds.size == visibleLogs.size) {
                            emptySet()
                        } else {
                            visibleLogs.map { it.id }.toSet()
                        }
                },
                onRemove = { confirmDeleteSelected = true },
            )
        } else {
            MacodesHeader(
                title = ws.logsTitle,
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = {
                            if (searchOpen) {
                                searchQuery = ""
                                searchOpen = false
                            } else {
                                searchOpen = true
                            }
                        },
                        modifier = Modifier.size(Sizes.TouchTarget),
                    ) {
                        Icon(
                            imageVector = if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = ws.logsSearch,
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(Sizes.TouchTarget),
                        ) {
                            Box {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = s.navMore,
                                )
                                if (hasActiveFilter) {
                                    ActiveFilterDot(Modifier.align(Alignment.TopEnd))
                                }
                            }
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(ws.logsFilterAction) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.FilterList,
                                        contentDescription = null,
                                    )
                                },
                                trailingIcon =
                                    if (hasActiveFilter) {
                                        { ActiveFilterDot() }
                                    } else {
                                        null
                                    },
                                onClick = {
                                    menuExpanded = false
                                    filterSheetOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(ws.logsSortAction)
                                        Text(
                                            text =
                                                if (sortOrder == SessionLogSortOrder.NEWEST_FIRST) {
                                                    ws.logsSortNewestFirst
                                                } else {
                                                    ws.logsSortOldestFirst
                                                },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.SwapVert,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    sortOrder =
                                        if (sortOrder == SessionLogSortOrder.NEWEST_FIRST) {
                                            SessionLogSortOrder.OLDEST_FIRST
                                        } else {
                                            SessionLogSortOrder.NEWEST_FIRST
                                        }
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        ws.logsClearAction,
                                        color =
                                            if (allLogs.isNotEmpty()) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteSweep,
                                        contentDescription = null,
                                        tint =
                                            if (allLogs.isNotEmpty()) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                    )
                                },
                                enabled = allLogs.isNotEmpty(),
                                onClick = {
                                    menuExpanded = false
                                    confirmClear = true
                                },
                            )
                        }
                    }
                },
            )
        }

        AnimatedVisibility(visible = !selectionMode && (searchOpen || searchQuery.isNotEmpty())) {
            MacodesSearchPill(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = ws.logsSearch,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.Lg)
                        .padding(bottom = Spacing.Sm),
            )
        }

        if (hasActiveFilter && !selectionMode) {
            ActiveFilterBanner(
                status = statusFilter,
                protocol = protocolFilter,
                ws = ws,
                onReset = {
                    statusFilter = null
                    protocolFilter = null
                },
            )
        }

        if (visibleLogs.isEmpty()) {
            if (hasQueryOrFilter) {
                WorkspaceEmptyState(
                    icon = Icons.Filled.Search,
                    message = "${ws.logsNoMatch}\n${ws.logsNoMatchHint}",
                    actionLabel = ws.logsFilterReset,
                    onAction = {
                        searchQuery = ""
                        searchOpen = false
                        statusFilter = null
                        protocolFilter = null
                    },
                )
            } else {
                WorkspaceEmptyState(
                    icon = Icons.Filled.History,
                    message = "${ws.logsEmpty}\n${ws.logsEmptyHint}",
                )
            }
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = Spacing.Lg, vertical = Spacing.Sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                groupedLogs.forEach { group ->
                    item(key = "header:${group.dateLabel}") {
                        Text(
                            text = group.dateLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.Sm, bottom = Spacing.Xs),
                        )
                    }
                    items(group.entries, key = { it.id }) { entry ->
                        val isSelected = entry.id in selectedIds
                        SessionLogCard(
                            entry = entry,
                            ws = ws,
                            timeZone = timeZone,
                            selectionMode = selectionMode,
                            selected = isSelected,
                            onClick = {
                                if (selectionMode) {
                                    selectedIds =
                                        if (isSelected) {
                                            selectedIds - entry.id
                                        } else {
                                            selectedIds + entry.id
                                        }
                                } else {
                                    selectedLogId = entry.id
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedIds = setOf(entry.id)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (filterSheetOpen) {
        SessionLogFilterSheet(
            initialStatus = statusFilter,
            initialProtocol = protocolFilter,
            ws = ws,
            onDismiss = { filterSheetOpen = false },
            onApply = { newStatus, newProtocol ->
                statusFilter = newStatus
                protocolFilter = newProtocol
                filterSheetOpen = false
            },
        )
    }

    if (confirmDeleteSelected) {
        val pendingDeleteIds = selectedIds
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            title = { Text(s.hostsDelete) },
            text = { Text(ws.logsDeleteSelectedConfirm(pendingDeleteIds.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteSelected = false
                        repository.deleteSessionLogs(pendingDeleteIds)
                        allLogs = repository.listSessionLogs()
                        onLogsChanged()
                        selectionMode = false
                        selectedIds = emptySet()
                    },
                ) {
                    Text(s.hostsDelete, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSelected = false }) {
                    Text(s.terminalCancel)
                }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(ws.logsClearAction) },
            text = { Text(ws.logsClearConfirm) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        repository.clearSessionLogs()
                        allLogs = emptyList()
                        onLogsChanged()
                    },
                ) {
                    Text(ws.logsClearAction, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(s.terminalCancel)
                }
            },
        )
    }
}

@Composable
private fun ActiveFilterBanner(
    status: SessionLogStatus?,
    protocol: ConnectionLogProtocol?,
    ws: WorkspaceStrings,
    onReset: () -> Unit,
) {
    val parts =
        buildList {
            status?.let { add(statusLabel(it, ws)) }
            protocol?.let { add(it.name) }
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Lg)
                .padding(bottom = Spacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "${ws.logsActiveFilterPrefix}: ${parts.joinToString(" · ")}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onReset,
            contentPadding = PaddingValues(horizontal = Spacing.Sm, vertical = Spacing.None),
        ) {
            Text(ws.logsFilterReset, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionLogCard(
    entry: SessionLog,
    ws: WorkspaceStrings,
    timeZone: TimeZone,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val accentColor = statusAccentColor(entry.status)
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Corners.Md))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        shape = RoundedCornerShape(Corners.Md),
        color =
            if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = Alphas.SELECTION_CONTAINER)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        border =
            BorderStroke(
                Sizes.BorderThin,
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = Alphas.SELECTION_BORDER)
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
            ) {
                // 1. Host name left + 24h local time right
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                ) {
                    Text(
                        text = entry.hostLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatLocalTime24(entry.startedAt, includeSeconds = false, timeZone = timeZone),
                        style =
                            MaterialTheme.typography.labelMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 2. username@hostname:port
                Text(
                    text = "${entry.username}@${entry.hostname}:${entry.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // 3. Protocol, status indicator, and duration (when opened)
                Row(
                    modifier = Modifier.padding(top = Sizes.AgentTimelineLineWidth),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                ) {
                    ProtocolBadge(entry.protocol.name)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
                    ) {
                        Icon(
                            imageVector = statusIcon(entry.status),
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(Sizes.IconSmall),
                        )
                        Text(
                            text = statusLabel(entry.status, ws),
                            style = MaterialTheme.typography.labelMedium,
                            color = accentColor,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    if (entry.durationMs != null) {
                        Text(
                            text = "· ${formatSessionDuration(entry.durationMs, ws)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 4. Safe failure reason only when failed
                if (entry.status == SessionLogStatus.FAILED && entry.errorCode != null) {
                    Text(
                        text = ws.logsErrorDescription(entry.errorCode),
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.Error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SessionLogFilterSheet(
    initialStatus: SessionLogStatus?,
    initialProtocol: ConnectionLogProtocol?,
    ws: WorkspaceStrings,
    onDismiss: () -> Unit,
    onApply: (SessionLogStatus?, ConnectionLogProtocol?) -> Unit,
) {
    var draftStatus by remember(initialStatus) { mutableStateOf(initialStatus) }
    var draftProtocol by remember(initialProtocol) { mutableStateOf(initialProtocol) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Lg)
                    .padding(bottom = Spacing.Xl)
                    .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.Lg),
        ) {
            Text(
                text = ws.logsFilterTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                Text(
                    text = ws.logsFilterStatusLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                ) {
                    val statusOptions =
                        listOf(
                            null to ws.logsFilterAll,
                            SessionLogStatus.COMPLETED to ws.logsStatusCompleted,
                            SessionLogStatus.FAILED to ws.logsStatusFailed,
                            SessionLogStatus.INTERRUPTED to ws.logsStatusInterrupted,
                        )
                    statusOptions.forEach { (option, label) ->
                        val selected = draftStatus == option
                        FilterChip(
                            selected = selected,
                            onClick = { draftStatus = option },
                            label = { Text(label) },
                            leadingIcon =
                                if (selected) {
                                    { SelectedChipCheckIcon() }
                                } else {
                                    null
                                },
                            modifier = Modifier.heightIn(min = Sizes.TouchTarget),
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                Text(
                    text = ws.logsFilterProtocolLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                ) {
                    val protocolOptions =
                        listOf(
                            null to ws.logsFilterAll,
                            ConnectionLogProtocol.SSH to ConnectionLogProtocol.SSH.name,
                            ConnectionLogProtocol.MOSH to ConnectionLogProtocol.MOSH.name,
                            ConnectionLogProtocol.SFTP to ConnectionLogProtocol.SFTP.name,
                        )
                    protocolOptions.forEach { (option, label) ->
                        val selected = draftProtocol == option
                        FilterChip(
                            selected = selected,
                            onClick = { draftProtocol = option },
                            label = { Text(label) },
                            leadingIcon =
                                if (selected) {
                                    { SelectedChipCheckIcon() }
                                } else {
                                    null
                                },
                            modifier = Modifier.heightIn(min = Sizes.TouchTarget),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        draftStatus = null
                        draftProtocol = null
                    },
                    modifier = Modifier.heightIn(min = Sizes.TouchTarget),
                ) {
                    Text(ws.logsFilterReset)
                }
                Spacer(Modifier.size(Spacing.Sm))
                Button(
                    onClick = { onApply(draftStatus, draftProtocol) },
                    modifier = Modifier.heightIn(min = Sizes.TouchTarget),
                ) {
                    Text(ws.logsFilterApply)
                }
            }
        }
    }
}

private data class SessionLogDateGroup(
    val dateLabel: String,
    val entries: List<SessionLog>,
)

private fun groupSessionLogsByDate(
    logs: List<SessionLog>,
    ws: WorkspaceStrings,
    now: Instant,
    timeZone: TimeZone,
): List<SessionLogDateGroup> {
    if (logs.isEmpty()) return emptyList()
    val grouped = LinkedHashMap<String, MutableList<SessionLog>>()
    for (entry in logs) {
        val label = formatSessionDateGroupHeader(entry.startedAt, ws, now, timeZone)
        grouped.getOrPut(label) { mutableListOf() }.add(entry)
    }
    return grouped.map { (label, entries) -> SessionLogDateGroup(label, entries) }
}

internal fun formatSessionDateGroupHeader(
    millis: Long,
    ws: WorkspaceStrings,
    now: Instant = Clock.System.now(),
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val today = now.toLocalDateTime(timeZone).date
    val yesterday = today.minus(DatePeriod(days = 1))
    val date = Instant.fromEpochMilliseconds(millis).toLocalDateTime(timeZone).date
    return when (date) {
        today -> ws.dateToday
        yesterday -> ws.dateYesterday
        else -> ws.formatMonthDay(date.dayOfMonth, date.monthNumber, date.year)
    }
}

internal fun formatLocalTime24(
    millis: Long,
    includeSeconds: Boolean = false,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val dt = Instant.fromEpochMilliseconds(millis).toLocalDateTime(timeZone)
    val hh = dt.hour.toString().padStart(2, '0')
    val mm = dt.minute.toString().padStart(2, '0')
    return if (includeSeconds) {
        val ss = dt.second.toString().padStart(2, '0')
        "$hh:$mm:$ss"
    } else {
        "$hh:$mm"
    }
}

internal fun formatSessionDateTimeFull(
    millis: Long,
    ws: WorkspaceStrings,
    now: Instant = Clock.System.now(),
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val dayLabel = formatSessionDateGroupHeader(millis, ws, now, timeZone)
    val timeLabel = formatLocalTime24(millis, includeSeconds = true, timeZone = timeZone)
    return "$dayLabel · $timeLabel"
}

internal fun formatSessionDuration(
    durationMs: Long,
    ws: WorkspaceStrings,
): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L)) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> ws.logsDurationHoursMinutes(hours, minutes.toString().padStart(2, '0'))
        minutes > 0L -> ws.logsDurationMinutesSeconds(minutes, seconds.toString().padStart(2, '0'))
        else -> ws.logsDurationSeconds(seconds)
    }
}

@Composable
private fun ActiveFilterDot(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .size(Sizes.AgentTimelineDot)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
    )
}

@Composable
private fun SelectedChipCheckIcon() {
    Icon(
        imageVector = Icons.Filled.Check,
        contentDescription = null,
        modifier = Modifier.size(Spacing.Lg),
    )
}

internal fun statusLabel(
    status: SessionLogStatus,
    ws: WorkspaceStrings,
): String =
    when (status) {
        SessionLogStatus.COMPLETED -> ws.logsStatusCompleted
        SessionLogStatus.FAILED -> ws.logsStatusFailed
        SessionLogStatus.INTERRUPTED -> ws.logsStatusInterrupted
    }

internal fun statusAccentColor(status: SessionLogStatus): Color =
    when (status) {
        SessionLogStatus.COMPLETED -> StatusColors.Connected
        SessionLogStatus.FAILED -> StatusColors.Error
        SessionLogStatus.INTERRUPTED -> StatusColors.Warning
    }

internal fun statusIcon(status: SessionLogStatus): ImageVector =
    when (status) {
        SessionLogStatus.COMPLETED -> Icons.Filled.CheckCircle
        SessionLogStatus.FAILED -> Icons.Filled.Error
        SessionLogStatus.INTERRUPTED -> Icons.Filled.Warning
    }

internal fun formatTimelineEventMessage(
    entry: SessionLog,
    event: SessionLogEvent,
    ws: WorkspaceStrings,
): String =
    when (event.type) {
        SessionLogEventType.CONNECTION_STARTED ->
            ws.logsEventConnectingTo("${entry.hostname}:${entry.port}")
        SessionLogEventType.CONNECTION_ESTABLISHED ->
            ws.logsEventConnectionEstablished
        SessionLogEventType.HOST_VERIFICATION_SUCCEEDED ->
            ws.logsEventHostVerified
        SessionLogEventType.HOST_VERIFICATION_FAILED ->
            ws.logsEventHostVerifyFailed
        SessionLogEventType.AUTHENTICATION_SUCCEEDED ->
            ws.logsEventAuthSucceeded(event.detailCode)
        SessionLogEventType.AUTHENTICATION_FAILED ->
            ws.logsEventAuthFailed
        SessionLogEventType.SSH_BOOTSTRAP_STARTED ->
            ws.logsEventMoshBootstrapStarted
        SessionLogEventType.SSH_BOOTSTRAP_SUCCEEDED ->
            ws.logsEventMoshServerStarted
        SessionLogEventType.UDP_CONNECTION_ESTABLISHED ->
            ws.logsEventMoshUdpEstablished
        SessionLogEventType.UDP_CONNECTION_TIMED_OUT ->
            ws.logsEventMoshUdpTimedOut
        SessionLogEventType.SESSION_OPENED ->
            ws.logsEventSessionOpened(entry.protocol)
        SessionLogEventType.SESSION_CLOSED ->
            ws.logsEventSessionClosed
        SessionLogEventType.CONNECTION_FAILED ->
            ws.logsEventConnectionFailed
        SessionLogEventType.CONNECTION_INTERRUPTED ->
            ws.logsEventConnectionInterrupted
    }
