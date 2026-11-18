package dev.macodes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.macodes.data.ConnectionMode
import dev.macodes.data.Host
import dev.macodes.data.HostFolder
import dev.macodes.data.HostRepository
import dev.macodes.data.HostTag
import dev.macodes.data.formatSafeSshCommand
import dev.macodes.ssh.SshSession
import dev.macodes.ssh.formatDiskMb
import dev.macodes.ssh.formatRamMb
import dev.macodes.ssh.refreshHostInventory
import dev.macodes.ui.theme.Corners
import dev.macodes.ui.theme.Sizes
import dev.macodes.ui.theme.Spacing
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HostDetailsScreen(
    host: Host,
    folders: List<HostFolder>,
    tagDefinitions: List<HostTag>,
    activeSshSession: SshSession?,
    repository: HostRepository,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onSftp: () -> Unit,
    onAgents: () -> Unit,
    aiAgentEnabled: Boolean = true,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleAutoDetectInventory: (Boolean) -> Unit,
    onManageTags: () -> Unit,
    onInventoryRefreshed: () -> Unit,
    onDelete: () -> Unit,
) {
    val s = LocalAppStrings.current
    val ws = s.workspace
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember(host.id) { mutableStateOf(false) }
    var refreshingSpecs by remember(host.id) { mutableStateOf(false) }
    var refreshError by remember(host.id) { mutableStateOf<String?>(null) }

    val tagColorsByName =
        remember(tagDefinitions) {
            tagDefinitions.associate { it.name.lowercase() to it.colorIndex }
        }
    val folderName =
        remember(folders, host.folderId, ws) {
            folders.firstOrNull { it.id == host.folderId }?.name ?: ws.hostsFolderRoot
        }
    val sys = host.system.ifBlank { host.hostname }
    val inv = host.inventory

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        MacodesHeader(
            title = ws.hostOrg.detailsTitle,
            onBack = onBack,
            actions = {
                IconButton(
                    onClick = onTogglePin,
                    modifier = Modifier.size(Sizes.TouchTarget),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = if (host.isPinned) ws.hostOrg.unpinAction else ws.hostOrg.pinAction,
                        tint =
                            if (host.isPinned) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(Sizes.TouchTarget),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = s.hostsEdit,
                    )
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(Spacing.Lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            // Identity & Metadata card
            item(key = "identity") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Corners.Lg),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(Spacing.Lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Md),
                    ) {
                        Row(
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
                                SystemAvatarIcon(sys, Sizes.IconMedium)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
                                ) {
                                    Text(
                                        text = host.name.ifBlank { host.hostname },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    ProtocolBadge(
                                        if (host.connectionMode == ConnectionMode.MOSH) {
                                            s.hostsModeMosh
                                        } else {
                                            s.hostsModeSsh
                                        },
                                    )
                                }
                                Text(
                                    text = "${host.username}@${host.hostname}:${host.port}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        DetailMetaRow(
                            label = ws.hostOrg.osLabel,
                            value = host.system.ifBlank { ws.hostOrg.inventoryNotDetected },
                        )
                        DetailMetaRow(
                            label = ws.hostOrg.folderLabel,
                            value = folderName,
                        )
                        DetailMetaRow(
                            label = ws.hostOrg.cloudProviderLabel,
                            value = cloudProviderDisplayLabel(host.cloudProvider, ws),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = s.editTags.substringBefore(" ("),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = onManageTags,
                                modifier = Modifier.heightIn(min = Sizes.TouchTarget),
                            ) {
                                Text(ws.hostOrg.manageTagsAction)
                            }
                        }
                        if (host.tags.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
                                verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                            ) {
                                host.tags.forEach { tag ->
                                    HostTagBadge(
                                        tagName = tag,
                                        colorIndex = tagColorsByName[tag.lowercase()],
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Quick Actions
            item(key = "actions") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Corners.Lg),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(Spacing.Lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    ) {
                        Button(
                            onClick = onConnect,
                            shape = RoundedCornerShape(Corners.Md),
                            modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.TouchTarget),
                        ) {
                            Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(Sizes.IconSmall))
                            Spacer(Modifier.width(Spacing.Xs))
                            Text(s.hostsConnect)
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                        ) {
                            OutlinedButton(
                                onClick = onSftp,
                                shape = RoundedCornerShape(Corners.Md),
                                modifier = Modifier.heightIn(min = Sizes.TouchTarget),
                            ) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(Sizes.IconSmall))
                                Spacer(Modifier.width(Spacing.Xs))
                                Text(s.hostsActionFiles)
                            }
                            if (aiAgentEnabled) {
                                OutlinedButton(
                                    onClick = onAgents,
                                    shape = RoundedCornerShape(Corners.Md),
                                    modifier = Modifier.heightIn(min = Sizes.TouchTarget),
                                ) {
                                    Icon(Icons.Filled.SmartToy, contentDescription = null, modifier = Modifier.size(Sizes.IconSmall))
                                    Spacer(Modifier.width(Spacing.Xs))
                                    Text(s.hostsActionAgents)
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString("${host.username}@${host.hostname}:${host.port}"))
                                    scope.launch { snackbarHostState.showSnackbar(ws.hostOrg.copiedAddressMessage) }
                                },
                                shape = RoundedCornerShape(Corners.Md),
                                modifier = Modifier.heightIn(min = Sizes.TouchTarget),
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(Sizes.IconSmall))
                                Spacer(Modifier.width(Spacing.Xs))
                                Text(ws.hostOrg.copyAddressAction)
                            }
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(formatSafeSshCommand(host)))
                                    scope.launch { snackbarHostState.showSnackbar(ws.hostOrg.copiedSshCommandMessage) }
                                },
                                shape = RoundedCornerShape(Corners.Md),
                                modifier = Modifier.heightIn(min = Sizes.TouchTarget),
                            ) {
                                Icon(Icons.Filled.ContentPasteGo, contentDescription = null, modifier = Modifier.size(Sizes.IconSmall))
                                Spacer(Modifier.width(Spacing.Xs))
                                Text(ws.hostOrg.copySshCommandAction)
                            }
                        }
                    }
                }
            }

            // VPS Inventory Card
            item(key = "inventory") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Corners.Lg),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(Spacing.Lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Md),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = ws.hostOrg.inventorySectionTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            TextButton(
                                enabled = !refreshingSpecs,
                                onClick = {
                                    refreshingSpecs = true
                                    refreshError = null
                                    scope.launch {
                                        val result = refreshHostInventory(host, repository, activeSshSession)
                                        refreshingSpecs = false
                                        result
                                            .onSuccess { onInventoryRefreshed() }
                                            .onFailure { err ->
                                                refreshError = err.message ?: "unknown error"
                                            }
                                    }
                                },
                                modifier = Modifier.heightIn(min = Sizes.TouchTarget),
                            ) {
                                if (refreshingSpecs) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(Spacing.Xs))
                                    Text(ws.hostOrg.inventoryRefreshing)
                                } else {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(Sizes.IconSmall))
                                    Spacer(Modifier.width(Spacing.Xs))
                                    Text(ws.hostOrg.inventoryRefreshAction)
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = ws.hostOrg.inventoryAutoDetectLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = ws.hostOrg.inventoryAutoDetectHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = host.autoDetectInventory,
                                onCheckedChange = onToggleAutoDetectInventory,
                                colors = macodesSwitchColors(),
                            )
                        }

                        refreshError?.let { msg ->
                            Text(
                                text = ws.hostOrg.inventoryRefreshFailed(msg),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        DetailMetaRow(
                            label = ws.hostOrg.inventoryCpuLabel,
                            value = inv?.cpuCores?.let { "$it vCPU" } ?: ws.hostOrg.inventoryNotDetected,
                        )
                        DetailMetaRow(
                            label = ws.hostOrg.inventoryRamLabel,
                            value = inv?.memoryTotalMb?.let { formatRamMb(it) } ?: ws.hostOrg.inventoryNotDetected,
                        )
                        DetailMetaRow(
                            label = ws.hostOrg.inventoryDiskLabel,
                            value = inv?.diskTotalMb?.let { formatDiskMb(it) } ?: ws.hostOrg.inventoryNotDetected,
                        )
                        DetailMetaRow(
                            label = ws.hostOrg.inventoryArchLabel,
                            value = inv?.architecture?.ifBlank { null } ?: ws.hostOrg.inventoryNotDetected,
                        )
                        DetailMetaRow(
                            label = ws.hostOrg.inventoryLastDetectedLabel,
                            value =
                                inv?.detectedAt?.takeIf { it > 0L }?.let { formatSessionDateTimeFull(it, ws) }
                                    ?: ws.hostOrg.inventoryNotDetected,
                        )
                    }
                }
            }

            // Duplicate / Delete
            item(key = "footer-actions") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                ) {
                    OutlinedButton(
                        onClick = onDuplicate,
                        shape = RoundedCornerShape(Corners.Md),
                        modifier = Modifier.weight(1f).heightIn(min = Sizes.TouchTarget),
                    ) {
                        Text(ws.hostOrg.duplicateAction)
                    }
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        shape = RoundedCornerShape(Corners.Md),
                        modifier = Modifier.weight(1f).heightIn(min = Sizes.TouchTarget),
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(Sizes.IconSmall),
                        )
                        Spacer(Modifier.width(Spacing.Xs))
                        Text(s.hostsDelete, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(ws.hostOrg.deleteConfirmTitle) },
            text = { Text(ws.hostOrg.deleteSingleConfirm(host.name.ifBlank { host.hostname })) },
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
                TextButton(onClick = { confirmDelete = false }) { Text(s.terminalCancel) }
            },
        )
    }
}

@Composable
private fun DetailMetaRow(
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
