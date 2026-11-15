package dev.macodes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.macodes.ui.theme.Corners
import dev.macodes.ui.theme.Sizes
import dev.macodes.ui.theme.Spacing

/** Sub-screens inside the top-level Workspace tab. Port forwarding is omitted because no tunnel engine exists yet. */
enum class WorkspaceSubPage {
    HOSTS,
    KEYCHAIN,
    SNIPPETS,
    KNOWN_HOSTS,
    LOGS,
}

@Composable
fun WorkspaceScreen(
    hostsCount: Int,
    credentialsCount: Int,
    snippetsCount: Int,
    knownHostsCount: Int,
    logsCount: Int,
    onOpenSub: (WorkspaceSubPage) -> Unit,
) {
    val ws = LocalAppStrings.current.workspace

    Scaffold(
        topBar = { MacodesLargeHeader(title = ws.title) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.Lg),
        ) {
            Text(
                text = ws.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.Md),
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Corners.Lg),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column {
                    WorkspaceHubRow(
                        icon = Icons.Default.Dns,
                        title = ws.hostsTitle,
                        subtitle = ws.hostsSubtitle,
                        count = hostsCount,
                        onClick = { onOpenSub(WorkspaceSubPage.HOSTS) },
                    )
                    WorkspaceRowDivider()
                    WorkspaceHubRow(
                        icon = Icons.Default.VpnKey,
                        title = ws.keychainTitle,
                        subtitle = ws.keychainSubtitle,
                        count = credentialsCount,
                        onClick = { onOpenSub(WorkspaceSubPage.KEYCHAIN) },
                    )
                    WorkspaceRowDivider()
                    WorkspaceHubRow(
                        icon = Icons.Default.Code,
                        title = ws.snippetsTitle,
                        subtitle = ws.snippetsSubtitle,
                        count = snippetsCount,
                        onClick = { onOpenSub(WorkspaceSubPage.SNIPPETS) },
                    )
                    WorkspaceRowDivider()
                    WorkspaceHubRow(
                        icon = Icons.Default.Fingerprint,
                        title = ws.knownHostsTitle,
                        subtitle = ws.knownHostsSubtitle,
                        count = knownHostsCount,
                        onClick = { onOpenSub(WorkspaceSubPage.KNOWN_HOSTS) },
                    )
                    WorkspaceRowDivider()
                    WorkspaceHubRow(
                        icon = Icons.Default.History,
                        title = ws.logsTitle,
                        subtitle = ws.logsSubtitle,
                        count = logsCount,
                        onClick = { onOpenSub(WorkspaceSubPage.LOGS) },
                    )
                }
            }
            Spacer(Modifier.height(Spacing.Xl))
        }
    }
}

@Composable
private fun WorkspaceRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = Spacing.Lg),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
}

@Composable
private fun WorkspaceHubRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    count: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.TouchTarget)
                .clickable(onClick = onClick)
                .padding(horizontal = Spacing.Lg, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        WorkspaceIconBadge(icon = icon)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (count > 0) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun WorkspaceIconBadge(icon: ImageVector) {
    Box(
        modifier =
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(Corners.Sm))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(Sizes.IconSmall),
        )
    }
}

@Composable
internal fun WorkspaceEmptyState(
    icon: ImageVector,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(Spacing.Lg),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(Corners.Xl),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(Spacing.Xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (actionLabel != null && onAction != null) {
                    Button(
                        onClick = onAction,
                        shape = RoundedCornerShape(Corners.Md),
                    ) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}

/** Strip redundant SSH default `:22` suffix while keeping non-standard ports visible. */
internal fun formatDisplayEndpoint(endpoint: String): String = endpoint.removeSuffix(":22")

/** Shared contextual top bar for long-press multi-selection across Workspace and Connections lists. */
@Composable
internal fun WorkspaceSelectionHeader(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onRemove: () -> Unit,
    onEdit: (() -> Unit)? = null,
    extraMenuItems: (@Composable (dismissMenu: () -> Unit) -> Unit)? = null,
) {
    val s = LocalAppStrings.current
    var moreOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = Spacing.Xs, vertical = Spacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = s.navBack)
            }
            Text(
                text = s.hostsSelectedCount(count),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (onEdit != null) {
                IconButton(onClick = onEdit, enabled = count == 1) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = s.hostsEdit,
                        tint =
                            if (count == 1) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            },
                    )
                }
            }
            Box {
                IconButton(onClick = { moreOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = s.navMore)
                }
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    extraMenuItems?.invoke { moreOpen = false }
                    DropdownMenuItem(
                        text = { Text(s.hostsSelectAll) },
                        leadingIcon = { Icon(Icons.Filled.SelectAll, contentDescription = null) },
                        onClick = {
                            moreOpen = false
                            onSelectAll()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(s.workspace.actionRemove, color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            moreOpen = false
                            onRemove()
                        },
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
