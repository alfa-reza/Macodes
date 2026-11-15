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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
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
import dev.macodes.data.Host
import dev.macodes.data.HostRepository
import dev.macodes.ui.theme.Corners
import dev.macodes.ui.theme.Sizes
import dev.macodes.ui.theme.Spacing
import dev.macodes.util.monospaceFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun KnownHostsScreen(
    repository: HostRepository,
    hosts: List<Host> = emptyList(),
    onKnownHostsChanged: () -> Unit,
    onBack: () -> Unit,
) {
    val s = LocalAppStrings.current
    val ws = s.workspace
    var records by remember { mutableStateOf(repository.listKnownHosts()) }
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var inspecting by remember { mutableStateOf<HostRepository.KnownHostRecord?>(null) }
    var pendingForget by remember { mutableStateOf<List<HostRepository.KnownHostRecord>>(emptyList()) }
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
        records.filter { rec ->
            q.isEmpty() ||
                rec.label.contains(q, ignoreCase = true) ||
                rec.endpoint.contains(q, ignoreCase = true) ||
                rec.fingerprint.contains(q, ignoreCase = true)
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
                    extraMenuItems =
                        if (selectedIds.size == 1) {
                            { dismiss ->
                                DropdownMenuItem(
                                    text = { Text(ws.actionInspect) },
                                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                                    onClick = {
                                        dismiss()
                                        val target = records.firstOrNull { it.id in selectedIds }
                                        selectedIds = emptySet()
                                        if (target != null) inspecting = target
                                    },
                                )
                            }
                        } else {
                            null
                        },
                    onSelectAll = { selectedIds = filtered.map { it.id }.toSet() },
                    onRemove = {
                        val targets = records.filter { it.id in selectedIds }
                        if (targets.isNotEmpty()) pendingForget = targets
                    },
                )
            } else {
                MacodesLargeHeader(
                    title = ws.knownHostsTitle,
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
                                contentDescription = if (searchExpanded) s.navBack else ws.knownHostsSearch,
                            )
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
                    placeholder = ws.knownHostsSearch,
                    modifier =
                        Modifier
                            .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm)
                            .focusRequester(searchFocusRequester),
                )
            }

            if (filtered.isEmpty()) {
                WorkspaceEmptyState(
                    icon = Icons.Default.Fingerprint,
                    message = if (records.isEmpty()) ws.knownHostsEmpty else ws.knownHostsNoMatch,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = Spacing.Lg, vertical = Spacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
                ) {
                    items(filtered, key = { it.id }) { rec ->
                        val selected = rec.id in selectedIds
                        KnownHostCard(
                            record = rec,
                            selected = selected,
                            onClick = {
                                if (selectionMode) {
                                    toggleSelection(rec.id)
                                } else {
                                    inspecting = rec
                                }
                            },
                            onLongClick = { toggleSelection(rec.id) },
                        )
                    }
                }
            }
        }
    }

    inspecting?.let { rec ->
        val linkedHosts =
            remember(rec, hosts) {
                hosts.filter { host ->
                    "${host.hostname}:${host.port}" == rec.endpoint &&
                        (!rec.isQuickConnect || host.knownHostFingerprint == rec.fingerprint)
                }
            }
        KnownHostDetailDialog(
            record = rec,
            linkedHosts = linkedHosts,
            onCopyAddress = {
                clipboard.setText(AnnotatedString(rec.endpoint))
                scope.launch { snackbarHostState.showSnackbar(ws.knownHostDetailAddressCopied) }
            },
            onCopyPublicKey = {
                clipboard.setText(AnnotatedString(rec.fingerprint))
                scope.launch { snackbarHostState.showSnackbar(ws.knownHostsCopiedMessage) }
            },
            onDismiss = { inspecting = null },
        )
    }

    if (pendingForget.isNotEmpty()) {
        val message =
            if (pendingForget.size == 1) {
                ws.knownHostsForgetConfirm(pendingForget.first().endpoint)
            } else {
                ws.knownHostsForgetSelectedConfirm(pendingForget.size)
            }
        AlertDialog(
            onDismissRequest = { pendingForget = emptyList() },
            title = { Text(ws.knownHostsForgetAction) },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingForget.forEach { repository.forgetKnownHost(it) }
                        records = repository.listKnownHosts()
                        selectedIds = emptySet()
                        pendingForget = emptyList()
                        onKnownHostsChanged()
                    },
                ) {
                    Text(ws.knownHostsForgetAction, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingForget = emptyList() }) {
                    Text(s.quickConnect.cancelAction)
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KnownHostCard(
    record: HostRepository.KnownHostRecord,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
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
                WorkspaceIconBadge(icon = Icons.Default.Fingerprint)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = formatDisplayEndpoint(record.endpoint),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = record.fingerprint,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = monospaceFontFamily(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun KnownHostDetailDialog(
    record: HostRepository.KnownHostRecord,
    linkedHosts: List<Host>,
    onCopyAddress: () -> Unit,
    onCopyPublicKey: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    val ws = s.workspace
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ws.knownHostsTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                KnownHostCopyField(
                    label = ws.knownHostDetailAddress,
                    value = record.endpoint,
                    monospace = false,
                    copyDescription = ws.knownHostDetailAddressCopied,
                    onCopy = onCopyAddress,
                )
                KnownHostCopyField(
                    label = ws.knownHostDetailPublicKey,
                    value = record.fingerprint,
                    monospace = true,
                    copyDescription = ws.knownHostsCopyAction,
                    onCopy = onCopyPublicKey,
                )
                if (linkedHosts.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                        Text(
                            text = ws.knownHostDetailHosts,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                linkedHosts.forEach { host ->
                                    Text(
                                        text =
                                            buildString {
                                                append(host.name.ifBlank { host.hostname })
                                                if (host.username.isNotBlank()) {
                                                    append(" (${host.username})")
                                                }
                                            },
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(s.settingsClose)
            }
        },
    )
}

@Composable
private fun KnownHostCopyField(
    label: String,
    value: String,
    monospace: Boolean,
    copyDescription: String,
    onCopy: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = RoundedCornerShape(Corners.Md),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = Spacing.Md, end = Spacing.Xs, top = Spacing.Xs, bottom = Spacing.Xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                Text(
                    text = value,
                    style =
                        if (monospace) {
                            MaterialTheme.typography.bodySmall.copy(fontFamily = monospaceFontFamily())
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = copyDescription,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(Sizes.IconSmall),
                    )
                }
            }
        }
    }
}
