package dev.macodes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.macodes.data.SessionLog
import dev.macodes.data.SessionLogEvent
import dev.macodes.data.SessionLogEventType
import dev.macodes.data.SessionLogStatus
import dev.macodes.ui.theme.Corners
import dev.macodes.ui.theme.Sizes
import dev.macodes.ui.theme.Spacing
import dev.macodes.ui.theme.StatusColors

@Composable
internal fun SessionDetailsScreen(
    log: SessionLog,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    val s = LocalAppStrings.current
    val ws = s.workspace
    var confirmDelete by remember(log.id) { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        MacodesHeader(
            title = ws.logsDetailTitle,
            onBack = onBack,
            actions = {
                IconButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.size(Sizes.TouchTarget),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = ws.logsDetailDeleteAction,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )

        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            contentPadding = PaddingValues(Spacing.Lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            item(key = "overview") {
                SessionOverviewCard(log = log, ws = ws)
            }

            if (log.errorCode != null) {
                item(key = "error") {
                    SessionSafeErrorCard(
                        status = log.status,
                        message = ws.logsErrorDescription(log.errorCode),
                    )
                }
            }

            if (log.events.isNotEmpty()) {
                item(key = "timeline") {
                    SessionTimelineCard(
                        log = log,
                        ws = ws,
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(ws.logsDeleteConfirmTitle) },
            text = { Text(ws.logsDeleteSingleConfirm(log.hostLabel)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) {
                    Text(s.hostsDelete, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(s.terminalCancel)
                }
            },
        )
    }
}

@Composable
private fun SessionOverviewCard(
    log: SessionLog,
    ws: WorkspaceStrings,
) {
    val statusColor = statusAccentColor(log.status)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Corners.Md),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.Lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                ) {
                    Text(
                        text = log.hostLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${log.username}@${log.hostname}:${log.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ProtocolBadge(log.protocol.name)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                Icon(
                    imageVector = statusIcon(log.status),
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(Sizes.IconSmall),
                )
                Text(
                    text = statusLabel(log.status, ws),
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                SessionMetaRow(
                    label = ws.logsDetailStarted,
                    value = formatSessionDateTimeFull(log.startedAt, ws),
                )
                SessionMetaRow(
                    label = ws.logsDetailEnded,
                    value = formatSessionDateTimeFull(log.endedAt, ws),
                )
                SessionMetaRow(
                    label = ws.logsDetailDuration,
                    value =
                        log.durationMs?.let(::formatSessionDuration) ?: ws.logsDetailNotOpened,
                )
            }
        }
    }
}

@Composable
private fun SessionMetaRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(Spacing.Md))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SessionSafeErrorCard(
    status: SessionLogStatus,
    message: String,
) {
    val accent =
        when (status) {
            SessionLogStatus.FAILED -> StatusColors.Error
            SessionLogStatus.INTERRUPTED -> StatusColors.Warning
            SessionLogStatus.COMPLETED -> StatusColors.Neutral
        }
    val icon =
        when (status) {
            SessionLogStatus.FAILED -> Icons.Filled.ErrorOutline
            SessionLogStatus.INTERRUPTED, SessionLogStatus.COMPLETED -> Icons.Filled.WarningAmber
        }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Corners.Md),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(Sizes.BorderThin, accent.copy(alpha = 0.30f)),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(Sizes.IconSmall),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SessionTimelineCard(
    log: SessionLog,
    ws: WorkspaceStrings,
) {
    val orderedEvents =
        remember(log.id, log.events) {
            log.events.sortedBy { it.timestamp }
        }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Corners.Md),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.Lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            Text(
                text = ws.logsTimelineTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Column {
                orderedEvents.forEachIndexed { index, event ->
                    TimelineEventRow(
                        log = log,
                        event = event,
                        ws = ws,
                        isFirst = index == 0,
                        isLast = index == orderedEvents.lastIndex,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineEventRow(
    log: SessionLog,
    event: SessionLogEvent,
    ws: WorkspaceStrings,
    isFirst: Boolean,
    isLast: Boolean,
) {
    val dotColor = timelineEventColor(event.type, MaterialTheme.colorScheme.primary)
    val railLineColor = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = formatLocalTime24(event.timestamp, includeSeconds = true),
            style =
                MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .width(68.dp)
                    .padding(top = 2.dp),
        )
        Box(
            modifier =
                Modifier
                    .width(Sizes.AgentTimelineRailWidth)
                    .fillMaxHeight(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier =
                        Modifier
                            .width(Sizes.AgentTimelineLineWidth)
                            .height(6.dp)
                            .background(if (isFirst) Color.Transparent else railLineColor),
                )
                Box(
                    modifier =
                        Modifier
                            .size(Sizes.AgentTimelineDot)
                            .clip(CircleShape)
                            .background(dotColor),
                )
                Box(
                    modifier =
                        Modifier
                            .width(Sizes.AgentTimelineLineWidth)
                            .weight(1f, fill = true)
                            .background(if (isLast) Color.Transparent else railLineColor),
                )
            }
        }
        Spacer(Modifier.width(Spacing.Sm))
        Text(
            text = formatTimelineEventMessage(log, event, ws),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(bottom = if (isLast) Spacing.None else Spacing.Md),
        )
    }
}

private fun timelineEventColor(
    type: SessionLogEventType,
    primaryColor: Color,
): Color =
    when (type) {
        SessionLogEventType.HOST_VERIFICATION_SUCCEEDED,
        SessionLogEventType.AUTHENTICATION_SUCCEEDED,
        SessionLogEventType.SSH_BOOTSTRAP_SUCCEEDED,
        SessionLogEventType.UDP_CONNECTION_ESTABLISHED,
        SessionLogEventType.SESSION_OPENED,
        SessionLogEventType.SESSION_CLOSED,
        -> StatusColors.Connected
        SessionLogEventType.HOST_VERIFICATION_FAILED,
        SessionLogEventType.AUTHENTICATION_FAILED,
        SessionLogEventType.UDP_CONNECTION_TIMED_OUT,
        SessionLogEventType.CONNECTION_FAILED,
        -> StatusColors.Error
        SessionLogEventType.CONNECTION_INTERRUPTED -> StatusColors.Warning
        SessionLogEventType.CONNECTION_STARTED,
        SessionLogEventType.CONNECTION_ESTABLISHED,
        SessionLogEventType.SSH_BOOTSTRAP_STARTED,
        -> primaryColor
    }
