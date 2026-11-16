package dev.macodes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.macodes.agent.AgentBridgeController
import dev.macodes.agent.AgentBridgeState
import dev.macodes.data.ConnectionMode
import dev.macodes.data.Host
import dev.macodes.data.HostFolder
import dev.macodes.data.countFolderContents
import dev.macodes.data.filterHostOrganization
import dev.macodes.data.newId
import dev.macodes.data.wouldCreateFolderCycle
import dev.macodes.generated.resources.Res
import dev.macodes.generated.resources.host_alma
import dev.macodes.generated.resources.host_alpine
import dev.macodes.generated.resources.host_arch
import dev.macodes.generated.resources.host_centos
import dev.macodes.generated.resources.host_cloud
import dev.macodes.generated.resources.host_debian
import dev.macodes.generated.resources.host_fedora
import dev.macodes.generated.resources.host_fnos
import dev.macodes.generated.resources.host_istoreos
import dev.macodes.generated.resources.host_kali
import dev.macodes.generated.resources.host_linux
import dev.macodes.generated.resources.host_mac
import dev.macodes.generated.resources.host_opensuse
import dev.macodes.generated.resources.host_openwrt
import dev.macodes.generated.resources.host_raspbian
import dev.macodes.generated.resources.host_rocky
import dev.macodes.generated.resources.host_synology
import dev.macodes.generated.resources.host_ubuntu
import dev.macodes.generated.resources.host_windows
import dev.macodes.ssh.SftpSession
import dev.macodes.ui.theme.Corners
import dev.macodes.ui.theme.Sizes
import dev.macodes.ui.theme.Spacing
import dev.macodes.ui.theme.StatusColors
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** Session capabilities displayed on the home page cards and connection pages. */
sealed interface HostSessionItem {
    val hostId: String
    val createdAt: Long
    val isActive: Boolean
    val isConnecting: Boolean

    /** Truly connected (CONNECTED): Connections in progress do not count; the home page statistics and tags show these three states accordingly. */
    val isConnected: Boolean

    data class Terminal(
        val controller: TerminalController,
    ) : HostSessionItem {
        override val hostId: String get() = controller.host.id
        override val createdAt: Long get() = controller.createdAt
        override val isActive: Boolean get() = isActiveStatus(controller.status)
        override val isConnecting: Boolean
            get() = controller.status == ConnStatus.CONNECTING || controller.status == ConnStatus.AUTH
        override val isConnected: Boolean get() = controller.status == ConnStatus.CONNECTED
    }

    data class Sftp(
        val host: Host,
        val session: SftpSession?,
        override val createdAt: Long,
    ) : HostSessionItem {
        override val hostId: String get() = host.id

        /** `session=null` refers to unconnected entries that are resumed after a process restart: gray disconnected state. */
        override val isActive: Boolean get() = session != null
        override val isConnecting: Boolean get() = false
        override val isConnected: Boolean get() = session != null
    }

    data class Agent(
        val controller: AgentBridgeController,
    ) : HostSessionItem {
        override val hostId: String get() = controller.host.id
        override val createdAt: Long get() = controller.createdAt
        override val isActive: Boolean
            get() =
                controller.busy ||
                    controller.pendingApprovals.isNotEmpty() ||
                    controller.state == AgentBridgeState.CONNECTING ||
                    controller.state == AgentBridgeState.INSTALLING ||
                    controller.state == AgentBridgeState.READY
        override val isConnecting: Boolean
            get() = controller.state == AgentBridgeState.CONNECTING || controller.state == AgentBridgeState.INSTALLING
        override val isConnected: Boolean get() = controller.state == AgentBridgeState.READY
    }
}

/** Active session determination: "Connection," "Authentication," and "Connected" are all counted (same as the connection page status). */
internal fun isActiveStatus(status: ConnStatus): Boolean = status == ConnStatus.CONNECTED || status == ConnStatus.CONNECTING || status == ConnStatus.AUTH

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostListScreen(
    hosts: List<Host>,
    hostSessions: Map<String, List<HostSessionItem>>,
    onAdd: () -> Unit,
    onEdit: (Host) -> Unit,
    onConnect: (Host) -> Unit,
    onOpenHerdr: (Host) -> Unit,
    onConnectBatch: (List<Host>) -> Unit,
    onDisconnect: (Host) -> Unit,
    onDelete: (Host) -> Unit,
    onAgents: (Host) -> Unit,
    onOpenSession: (TerminalController) -> Unit,
    onOpenSftp: (Host, SftpSession?) -> Unit,
    onStartSftp: (Host) -> Unit,
    onCloseAllSessions: (Host) -> Unit,
    onBack: (() -> Unit)? = null,
    folders: List<HostFolder> = emptyList(),
    onAddInFolder: ((String?) -> Unit)? = null,
    onUpsertFolder: (HostFolder) -> Unit = {},
    onDeleteFolder: (String) -> Unit = {},
    onMoveHostsToFolder: (Set<String>, String?) -> Unit = { _, _ -> },
) {
    val s = LocalAppStrings.current
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var currentFolderId by remember { mutableStateOf<String?>(null) }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var addMenuOpen by remember { mutableStateOf(false) }
    var editingFolder by remember { mutableStateOf<HostFolder?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showMoveToFolderDialog by remember { mutableStateOf(false) }
    var pendingDeleteFolder by remember { mutableStateOf<HostFolder?>(null) }

    val searchFocusRequester = remember { FocusRequester() }
    // Wait until the search results expand and the entrance animation begins before refocusing; the keyboard should then pop up.
    LaunchedEffect(searchExpanded) {
        if (searchExpanded) {
            delay(150)
            searchFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(folders) {
        if (currentFolderId != null && folders.none { it.id == currentFolderId }) {
            currentFolderId = null
        }
    }

    val orgView =
        remember(hosts, folders, currentFolderId, selectedTag, query) {
            filterHostOrganization(
                hosts = hosts,
                folders = folders,
                currentFolderId = currentFolderId,
                selectedTag = selectedTag,
                query = query,
            )
        }
    val filtered = orgView.visibleHosts

    // Batch (multiple selections) mode: Tap the avatar / Long press a card to enter
    var selectionMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<String, Host>() }

    fun enterSelection(host: Host) {
        selected.clear()
        selected[host.id] = host
        selectionMode = true
    }

    fun exitSelection() {
        selectionMode = false
        selected.clear()
    }

    // In multi-select mode the system Back key exits batch mode first; otherwise navigates up one folder level.
    PlatformBackHandler(enabled = selectionMode || currentFolderId != null) {
        if (selectionMode) {
            exitSelection()
        } else {
            currentFolderId = folders.firstOrNull { it.id == currentFolderId }?.parentId
        }
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                SelectionHeader(
                    count = selected.size,
                    onClose = { exitSelection() },
                    onEdit = {
                        selected.values.firstOrNull()?.let(onEdit)
                        exitSelection()
                    },
                    onMoveToFolder = { showMoveToFolderDialog = true },
                    onSelectAll = { filtered.forEach { selected[it.id] = it } },
                    onConnect = {
                        onConnectBatch(selected.values.toList())
                        exitSelection()
                    },
                    onDisconnect = {
                        selected.values.forEach(onDisconnect)
                        exitSelection()
                    },
                    onRemove = {
                        selected.values.forEach(onDelete)
                        exitSelection()
                    },
                )
            } else {
                MacodesLargeHeader(
                    title = s.appTabHosts,
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
                                contentDescription = if (searchExpanded) s.navBack else s.hostsSearch,
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
                        onClick = { addMenuOpen = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(Corners.Lg),
                    ) { Icon(Icons.Default.Add, s.hostsAdd) }
                    DropdownMenu(
                        expanded = addMenuOpen,
                        onDismissRequest = { addMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(s.workspace.hostsNewHost) },
                            leadingIcon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                            onClick = {
                                addMenuOpen = false
                                if (onAddInFolder != null) {
                                    onAddInFolder(currentFolderId)
                                } else {
                                    onAdd()
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(s.workspace.hostsFolderNew) },
                            leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                            onClick = {
                                addMenuOpen = false
                                showNewFolderDialog = true
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AnimatedVisibility(
                visible = searchExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                MacodesSearchPill(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = s.hostsSearch,
                    modifier =
                        Modifier
                            .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm)
                            .focusRequester(searchFocusRequester),
                )
            }
            if (orgView.breadcrumbs.isNotEmpty()) {
                FolderBreadcrumbsBar(
                    breadcrumbs = orgView.breadcrumbs,
                    onSelectFolder = { currentFolderId = it },
                )
            }
            if (orgView.allTags.isNotEmpty()) {
                TagFilterBar(
                    tags = orgView.allTags,
                    selectedTag = selectedTag,
                    onSelectTag = { tag ->
                        selectedTag = if (selectedTag.equals(tag, ignoreCase = true)) null else tag
                    },
                )
            }
            val activeCount =
                hosts.sumOf { h ->
                    hostSessions[h.id].orEmpty().count { it.isActive }
                }
            val showEmptyState = orgView.visibleFolders.isEmpty() && filtered.isEmpty()
            if (showEmptyState) {
                Box(Modifier.fillMaxSize().padding(Spacing.Lg), contentAlignment = Alignment.Center) {
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
                                if (currentFolderId != null) Icons.Filled.FolderOpen else Icons.Filled.Terminal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp),
                            )
                            Text(
                                when {
                                    hosts.isEmpty() && folders.isEmpty() -> s.hostsEmpty
                                    currentFolderId != null && query.isBlank() && selectedTag == null -> s.workspace.hostsFolderEmpty
                                    else -> s.hostsNoMatch
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (hosts.isEmpty() && folders.isEmpty()) {
                                Button(
                                    onClick = {
                                        if (onAddInFolder != null) onAddInFolder(currentFolderId) else onAdd()
                                    },
                                    shape = RoundedCornerShape(Corners.Md),
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(Spacing.Xs))
                                    Text(s.hostsAdd)
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = Spacing.Lg, vertical = Spacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
                ) {
                    item(key = "hosts-overview-bar") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.Xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(Corners.Full),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Text(
                                    text = "${s.appTabHosts} · ${hosts.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = Spacing.Md, vertical = 6.dp),
                                )
                            }
                            if (folders.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(Corners.Full),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Text(
                                        text = "${s.workspace.hostsFoldersLabel} · ${folders.size}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = Spacing.Md, vertical = 6.dp),
                                    )
                                }
                            }
                            if (activeCount > 0) {
                                Surface(
                                    shape = RoundedCornerShape(Corners.Full),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = Spacing.Md, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Box(
                                            Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(StatusColors.Connected),
                                        )
                                        Text(
                                            text = "${s.hostsActive} · $activeCount",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    items(orgView.visibleFolders, key = { "folder:${it.id}" }) { folder ->
                        val counts = remember(hosts, folders, folder.id) { countFolderContents(hosts, folders, folder.id) }
                        FolderCard(
                            folder = folder,
                            hostCount = counts.hostCount,
                            subfolderCount = counts.subfolderCount,
                            onClick = { currentFolderId = folder.id },
                            onLongClick = { editingFolder = folder },
                        )
                    }
                    items(filtered, key = { it.id }) { host ->
                        val isSelected = host.id in selected
                        val sessions = hostSessions[host.id].orEmpty()
                        HostCard(
                            host = host,
                            sessions = sessions,
                            selectionMode = selectionMode,
                            selected = isSelected,
                            onCardClick = {
                                if (selectionMode) {
                                    if (isSelected) selected.remove(host.id) else selected[host.id] = host
                                } else {
                                    // If there is an active session, use the first active one; if all connections are disconnected, resume using the first disconnected one.
                                    // (Automatically reconnect after entering the terminal screen); create a new session only if none exists
                                    when (val target = sessions.firstOrNull { it.isActive } ?: sessions.firstOrNull()) {
                                        is HostSessionItem.Terminal -> onOpenSession(target.controller)
                                        is HostSessionItem.Sftp -> onOpenSftp(target.host, target.session)
                                        is HostSessionItem.Agent -> onAgents(host)
                                        null -> onConnect(host)
                                    }
                                }
                            },
                            onAvatarClick = {
                                if (selectionMode) {
                                    if (isSelected) selected.remove(host.id) else selected[host.id] = host
                                } else {
                                    enterSelection(host)
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) enterSelection(host)
                            },
                            onTerminal = { onConnect(host) },
                            onHerdr = { onOpenHerdr(host) },
                            onSftp = {
                                val existing = sessions.filterIsInstance<HostSessionItem.Sftp>().firstOrNull()
                                if (existing != null) {
                                    onOpenSftp(existing.host, existing.session)
                                } else {
                                    onStartSftp(host)
                                }
                            },
                            onAgents = { onAgents(host) },
                            onOpenSession = onOpenSession,
                            onOpenSftp = onOpenSftp,
                            onCloseAllSessions = { onCloseAllSessions(host) },
                        )
                    }
                }
            }
        }
    }

    if (showNewFolderDialog) {
        FolderEditDialog(
            existing = null,
            folders = folders,
            defaultParentId = currentFolderId,
            onSave = { folder ->
                onUpsertFolder(folder)
                showNewFolderDialog = false
            },
            onDelete = null,
            onDismiss = { showNewFolderDialog = false },
        )
    }

    editingFolder?.let { target ->
        FolderEditDialog(
            existing = target,
            folders = folders,
            defaultParentId = target.parentId,
            onSave = { updated ->
                onUpsertFolder(updated)
                editingFolder = null
            },
            onDelete = {
                editingFolder = null
                pendingDeleteFolder = target
            },
            onDismiss = { editingFolder = null },
        )
    }

    pendingDeleteFolder?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteFolder = null },
            title = { Text(s.workspace.hostsFolderDelete) },
            text = { Text(s.workspace.hostsFolderDeleteConfirm(target.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteFolder(target.id)
                        if (currentFolderId == target.id) {
                            currentFolderId = target.parentId
                        }
                        pendingDeleteFolder = null
                    },
                ) {
                    Text(s.workspace.hostsFolderDelete, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteFolder = null }) { Text(s.navBack) }
            },
        )
    }

    if (showMoveToFolderDialog && selected.isNotEmpty()) {
        FolderPickerDialog(
            title = s.workspace.hostsFolderMoveTo,
            folders = folders,
            selectedFolderId = selected.values.firstOrNull()?.folderId,
            onSelect = { targetFolderId ->
                onMoveHostsToFolder(selected.keys.toSet(), targetFolderId)
                showMoveToFolderDialog = false
                exitSelection()
            },
            onDismiss = { showMoveToFolderDialog = false },
        )
    }
}

/** Batch mode header: Close on the left, “x selected” in the middle, and Edit + three-dot menu on the right. */
@Composable
private fun SelectionHeader(
    count: Int,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onMoveToFolder: () -> Unit,
    onSelectAll: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRemove: () -> Unit,
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
                s.hostsSelectedCount(count),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            // Edit: Available only when a single host is selected (edit the currently selected host)
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
            Box {
                IconButton(onClick = { moreOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = s.navMore)
                }
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(s.workspace.hostsFolderMoveTo) },
                        leadingIcon = { Icon(Icons.Filled.DriveFileMove, null) },
                        onClick = {
                            moreOpen = false
                            onMoveToFolder()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(s.hostsSelectAll) },
                        leadingIcon = { Icon(Icons.Filled.SelectAll, null) },
                        onClick = {
                            moreOpen = false
                            onSelectAll()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(s.hostsConnect) },
                        leadingIcon = { Icon(Icons.Filled.Link, null) },
                        onClick = {
                            moreOpen = false
                            onConnect()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(s.hostsDisconnect) },
                        leadingIcon = { Icon(Icons.Filled.LinkOff, null) },
                        onClick = {
                            moreOpen = false
                            onDisconnect()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(s.hostsRemove, color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
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

/**
 * Host card (Termius style): System avatar + two rows of info.
 * - Row 1: Host IP/address; Row 2: Active or “protocol, username, system”
 * - Tap the card to establish the connection; long press the card or tap the avatar to enter batch mode
 * - In batch mode, tap to toggle selection; selected cards are highlighted with a border in the primary color.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostCard(
    host: Host,
    sessions: List<HostSessionItem>,
    selectionMode: Boolean,
    selected: Boolean,
    onCardClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onLongClick: () -> Unit,
    onTerminal: () -> Unit,
    onHerdr: () -> Unit,
    onSftp: () -> Unit,
    onAgents: () -> Unit,
    onOpenSession: (TerminalController) -> Unit,
    onOpenSftp: (Host, SftpSession?) -> Unit,
    onCloseAllSessions: () -> Unit,
) {
    val s = LocalAppStrings.current
    val sys = host.system.ifBlank { host.hostname }
    val connecting = sessions.any { it.isConnecting }
    val isMosh = host.connectionMode == ConnectionMode.MOSH
    val protoLabel = if (isMosh) s.hostsModeMosh else s.hostsModeSsh
    // First line: Alias (name) takes precedence; if empty, fall back to the host address.
    val title = host.name.ifBlank { host.hostname }
    val detail =
        buildString {
            append("${host.username}@${host.hostname}:${host.port}")
            if (host.system.isNotBlank()) {
                append(" · ")
                append(host.system)
            }
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Corners.Lg))
                .combinedClickable(
                    onClick = onCardClick,
                    onLongClick = onLongClick,
                ),
        shape = RoundedCornerShape(Corners.Lg),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
            ),
        border =
            if (selected) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant)
            },
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                // System Avatar (Rounded Square + White Icon): Tap to enter/toggle batch selection
                Box(
                    Modifier
                        .size(Sizes.AvatarCard)
                        .clip(RoundedCornerShape(Corners.Md))
                        .background(systemColor(sys))
                        .clickable(onClick = onAvatarClick),
                    contentAlignment = Alignment.Center,
                ) {
                    if (connecting) {
                        // Connection: The avatar shows a spinning icon; once the connection is complete, you will be automatically redirected to the terminal screen.
                        CircularProgressIndicator(
                            modifier = Modifier.size(Sizes.IconMedium),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        SystemAvatarIcon(sys, Sizes.IconMedium)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Surface(
                            shape = RoundedCornerShape(Corners.Sm),
                            color =
                                if (isMosh) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                },
                        ) {
                            Text(
                                text = protoLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color =
                                    if (isMosh) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    },
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (host.tags.isNotEmpty()) {
                        val visibleTags = host.tags.take(3)
                        val overflowCount = host.tags.size - visibleTags.size
                        Row(
                            modifier = Modifier.padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            visibleTags.forEach { tag ->
                                Surface(
                                    shape = RoundedCornerShape(Corners.Sm),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Text(
                                        text = "#$tag",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            if (overflowCount > 0) {
                                Surface(
                                    shape = RoundedCornerShape(Corners.Sm),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Text(
                                        text = "+$overflowCount",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (selectionMode) {
                    // Selection indicator: checkmark circle
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(Corners.Full))
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                } else {
                    HostMoreMenu(
                        sessions = sessions,
                        onTerminal = onTerminal,
                        onHerdr = onHerdr,
                        onSftp = onSftp,
                        onAgents = onAgents,
                        onOpenTerminal = onOpenSession,
                        onOpenSftp = onOpenSftp,
                        onCloseAll = onCloseAllSessions,
                    )
                }
            }
        }
    }
}

/** Single point of entry for cards: “More” icon + single session badge; integrate connection capabilities and existing sessions into a unified menu. */
@Composable
private fun HostMoreMenu(
    sessions: List<HostSessionItem>,
    onTerminal: () -> Unit,
    onHerdr: () -> Unit,
    onSftp: () -> Unit,
    onAgents: () -> Unit,
    onOpenTerminal: (TerminalController) -> Unit,
    onOpenSftp: (Host, SftpSession?) -> Unit,
    onCloseAll: () -> Unit,
) {
    val s = LocalAppStrings.current
    var open by remember { mutableStateOf(false) }
    val activeCount = sessions.count { it.isActive }
    val connectingOnly = activeCount > 0 && sessions.filter { it.isActive }.all { it.isConnecting }
    Box {
        IconButton(onClick = { open = true }) {
            BadgedBox(
                badge = {
                    if (activeCount > 0) {
                        Badge(
                            containerColor = if (connectingOnly) StatusColors.Warning else StatusColors.Connected,
                        ) {
                            Text(if (activeCount > 99) "99+" else activeCount.toString())
                        }
                    }
                },
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = s.navMore,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.width(Sizes.HostActionsMenuWidth),
        ) {
            Text(
                s.hostsConnect,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            DropdownMenuItem(
                text = { Text(s.hostsActionTerminal) },
                leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                onClick = {
                    open = false
                    onTerminal()
                },
            )
            DropdownMenuItem(
                text = { Text(s.hostsActionHerdr) },
                leadingIcon = {
                    Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                onClick = {
                    open = false
                    onHerdr()
                },
            )
            DropdownMenuItem(
                text = { Text(s.hostsActionFiles) },
                leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                onClick = {
                    open = false
                    onSftp()
                },
            )
            DropdownMenuItem(
                text = { Text(s.hostsActionAgents) },
                leadingIcon = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                onClick = {
                    open = false
                    onAgents()
                },
            )
            if (sessions.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    s.connSectionTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            sessions.forEach { item ->
                // Three-state labels: connected green / connecting orange / disconnected gray (SFTP sessions are always active)
                val statusLabel =
                    when (item) {
                        is HostSessionItem.Terminal ->
                            when (item.controller.status) {
                                ConnStatus.CONNECTED -> s.hostsActive
                                ConnStatus.CONNECTING, ConnStatus.AUTH -> s.connStatusConnecting
                                else -> s.connStatusClosed
                            }
                        // SFTP: has connection = active; disconnected retained (session=null) = disconnected; enter the tab to reconnect
                        is HostSessionItem.Sftp -> if (item.session != null) s.hostsActive else s.connStatusClosed
                        is HostSessionItem.Agent ->
                            when {
                                item.controller.pendingApprovals.isNotEmpty() -> s.nativeAgents.sessionWaitingApproval
                                item.controller.busy -> s.nativeAgents.sessionRunning
                                item.isConnecting -> s.connStatusConnecting
                                item.isConnected -> s.hostsActive
                                else -> s.connStatusClosed
                            }
                    }
                val statusColor =
                    when (item) {
                        is HostSessionItem.Terminal ->
                            when (item.controller.status) {
                                ConnStatus.CONNECTED -> StatusColors.Connected
                                ConnStatus.CONNECTING, ConnStatus.AUTH -> StatusColors.Warning
                                else -> StatusColors.Neutral
                            }
                        is HostSessionItem.Sftp ->
                            if (item.session !=
                                null
                            ) {
                                StatusColors.Connected
                            } else {
                                StatusColors.Neutral
                            }
                        is HostSessionItem.Agent ->
                            when {
                                item.controller.pendingApprovals.isNotEmpty() -> StatusColors.Warning
                                item.isConnected -> StatusColors.Connected
                                item.isConnecting -> StatusColors.Warning
                                else -> StatusColors.Neutral
                            }
                    }
                when (item) {
                    is HostSessionItem.Terminal ->
                        DropdownMenuItem(
                            text = {
                                val launchLabel =
                                    if (item.controller.launchMode == TerminalLaunchMode.HERDR) {
                                        s.hostsActionHerdr
                                    } else {
                                        s.hostsActionTerminal
                                    }
                                Text(
                                    "$launchLabel · ${formatSessionCreatedAt(item.createdAt, s.workspace)}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingIcon = {
                                Text(
                                    statusLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColor,
                                )
                            },
                            onClick = {
                                open = false
                                onOpenTerminal(item.controller)
                            },
                        )
                    is HostSessionItem.Sftp ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "${s.hostsActionFiles} · ${formatSessionCreatedAt(item.createdAt, s.workspace)}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingIcon = {
                                Text(
                                    statusLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColor,
                                )
                            },
                            onClick = {
                                open = false
                                onOpenSftp(item.host, item.session)
                            },
                        )
                    is HostSessionItem.Agent ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "${item.controller.currentSession?.title ?: s.hostsActionAgents} · " +
                                        formatSessionCreatedAt(item.createdAt, s.workspace),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                            trailingIcon = {
                                Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = statusColor)
                            },
                            onClick = {
                                open = false
                                onAgents()
                            },
                        )
                }
            }
            if (sessions.isNotEmpty()) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(s.hostsCloseAll, color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(Icons.Default.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    onClick = {
                        open = false
                        onCloseAll()
                    },
                )
            }
        }
    }
}

internal data class AdaptiveTimestamp(
    val dateLabel: String,
    val timeLabel: String,
) {
    val fullLabel: String
        get() = if (dateLabel.isEmpty()) timeLabel else "$dateLabel · $timeLabel"
}

/** Splits [millis] into adaptive date ("Today", "Yesterday", "12 Nov", "14 Nov 2025") and "HH:mm" time labels. */
internal fun splitSessionTimestamp(
    millis: Long,
    s: WorkspaceStrings? = null,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    now: Instant = Clock.System.now(),
): AdaptiveTimestamp {
    if (millis <= 0L) return AdaptiveTimestamp(dateLabel = "", timeLabel = "--:--")
    val created = Instant.fromEpochMilliseconds(millis).toLocalDateTime(timeZone)
    val today = now.toLocalDateTime(timeZone).date
    val yesterday = today.minus(DatePeriod(days = 1))

    fun pad(value: Int): String = value.toString().padStart(2, '0')
    val time = "${pad(created.hour)}:${pad(created.minute)}"
    val date =
        when {
            created.date == today -> s?.dateToday ?: "Today"
            created.date == yesterday -> s?.dateYesterday ?: "Yesterday"
            created.year == today.year ->
                s?.formatMonthDay?.invoke(created.dayOfMonth, created.monthNumber, null)
                    ?: formatEnglishMonthDay(created.dayOfMonth, created.monthNumber, null)
            else ->
                s?.formatMonthDay?.invoke(created.dayOfMonth, created.monthNumber, created.year)
                    ?: formatEnglishMonthDay(created.dayOfMonth, created.monthNumber, created.year)
        }
    return AdaptiveTimestamp(dateLabel = date, timeLabel = time)
}

/** Adaptive single-line timestamp notation: "Today · HH:mm", "Yesterday · HH:mm", "12 Nov · HH:mm", or "14 Nov 2025 · HH:mm". */
internal fun formatSessionCreatedAt(
    millis: Long,
    s: WorkspaceStrings? = null,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    now: Instant = Clock.System.now(),
): String = splitSessionTimestamp(millis, s, timeZone, now).fullLabel

/** System keywords → White icon resources (for systems with branded icons; otherwise, fall back to [systemIcon]). */
internal fun systemSvg(system: String): DrawableResource? {
    val s = system.lowercase()
    return when {
        s.contains("macos") || s.contains("darwin") || s.contains("osx") || s.contains("mac") -> Res.drawable.host_mac
        s.contains("windows") || s.contains("win") -> Res.drawable.host_windows
        s.contains("centos") -> Res.drawable.host_centos
        s.contains("ubuntu") -> Res.drawable.host_ubuntu
        s.contains("alpine") -> Res.drawable.host_alpine
        s.contains("fedora") -> Res.drawable.host_fedora
        s.contains("openwrt") -> Res.drawable.host_openwrt
        s.contains("istoreos") -> Res.drawable.host_istoreos
        s.contains("debian") -> Res.drawable.host_debian
        s.contains("fnos") || s.contains("fnnas") || s.contains("飞牛") -> Res.drawable.host_fnos
        s.contains("arch") -> Res.drawable.host_arch
        s.contains("kali") -> Res.drawable.host_kali
        s.contains("rocky") -> Res.drawable.host_rocky
        s.contains("alma") -> Res.drawable.host_alma
        s.contains("raspbian") || s.contains("raspberry") -> Res.drawable.host_raspbian
        s.contains("opensuse") || s.contains("suse") -> Res.drawable.host_opensuse
        s.contains("synology") || s.contains("dsm") -> Res.drawable.host_synology
        s.contains("linux") ||
            s.contains("redhat") ||
            s.contains("manjaro") ||
            s.contains("gentoo") ||
            s.contains("mint") ||
            s.contains("nixos") ||
            s.contains("pop") ||
            s.contains("elementary") -> Res.drawable.host_linux
        else -> null
    }
}

/** System keywords → Avatar icons (Material vector: android/ios/bsd; return null for unknown systems and use the cloud host’s default icon). */
internal fun systemIcon(system: String): ImageVector? {
    val s = system.lowercase()
    return when {
        s.contains("android") -> Icons.Filled.Android
        s.contains("ios") || s.contains("iphone") -> Icons.Filled.PhoneIphone
        s.contains("macos") || s.contains("darwin") || s.contains("osx") || s.contains("mac") -> Icons.Filled.LaptopMac
        s.contains("windows") || s.contains("win") -> Icons.Filled.DesktopWindows
        s.contains("freebsd") || s.contains("bsd") -> Icons.Filled.Memory
        s.contains("linux") ||
            s.contains("ubuntu") ||
            s.contains("debian") ||
            s.contains("centos") ||
            s.contains("fedora") ||
            s.contains("arch") ||
            s.contains("alpine") ||
            s.contains("kali") ||
            s.contains("redhat") ||
            s.contains("raspbian") -> Icons.Filled.Terminal
        else -> null
    }
}

/**
 * System Avatar Icons (Constant White): Brand SVG preferred → Material vector (Android/iOS/BSD) →
 * Use the default icon for cloud hosts as a fallback. This applies uniformly to the home page cards, the terminal tab, and the SFTP host selection—ensuring consistent rendering.
 */
@Composable
internal fun SystemAvatarIcon(
    system: String,
    size: Dp,
) {
    val svg = systemSvg(system)
    if (svg != null) {
        Icon(painterResource(svg), contentDescription = null, tint = Color.White, modifier = Modifier.size(size))
        return
    }
    val vec = systemIcon(system)
    if (vec != null) {
        Icon(vec, contentDescription = null, tint = Color.White, modifier = Modifier.size(size))
        return
    }
    // Unknown system: Default cloud host icon (white)
    Icon(
        painterResource(Res.drawable.host_cloud),
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier.size(size),
    )
}

/**
 * System keywords → Avatar background color (solid color, as close as possible to each system’s official brand color). Recognition order:
 * First, check the host’s “system” field; if it is empty, fallback to the hostname (e.g., mac.example.com → macOS).
 * Ubuntu: orange; Debian: red; Mac: black… Use brand-specific emerald green for unknown systems.
 */
internal fun systemColor(system: String): Color {
    val s = system.lowercase()
    return when {
        s.contains("android") -> Color(0xFF3DDC84)
        s.contains("ios") || s.contains("iphone") -> Color(0xFF0A84FF)
        s.contains("macos") || s.contains("darwin") || s.contains("osx") || s.contains("mac") -> Color.Black
        s.contains("windows") || s.contains("win") -> Color(0xFF0078D4)
        s.contains("freebsd") || s.contains("bsd") -> Color(0xFFD6242C)
        s.contains("ubuntu") -> Color(0xFFE9635E)
        s.contains("fedora") -> Color(0xFF294172)
        s.contains("debian") -> Color(0xFFA80030)
        s.contains("alpine") -> Color(0xFF0D597F)
        s.contains("openwrt") -> Color(0xFF295FB3)
        s.contains("istoreos") -> Color(0xFF2E6BE6)
        s.contains("centos") -> Color(0xFF262577)
        s.contains("fnos") || s.contains("fnnas") || s.contains("飞牛") -> Color(0xFF3070D0)
        s.contains("arch") -> Color(0xFF1793D1)
        s.contains("kali") -> Color(0xFF557C94)
        s.contains("rocky") -> Color(0xFF10B981)
        s.contains("alma") -> Color(0xFF0E6682)
        s.contains("raspbian") || s.contains("raspberry") -> Color(0xFFC51A4A)
        s.contains("opensuse") || s.contains("suse") -> Color(0xFF73BA25)
        s.contains("synology") || s.contains("dsm") -> Color(0xFF1692D4)
        s.contains("linux") ||
            s.contains("redhat") ||
            s.contains("manjaro") ||
            s.contains("gentoo") ||
            s.contains("mint") ||
            s.contains("nixos") ||
            s.contains("pop") ||
            s.contains("elementary") -> Color(0xFF475569)
        // Unknown system: brand electric blue; white cloud-host icon pops on it
        else -> Color(0xFF2B84F6)
    }
}

@Composable
private fun FolderBreadcrumbsBar(
    breadcrumbs: List<HostFolder>,
    onSelectFolder: (String?) -> Unit,
) {
    val s = LocalAppStrings.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Lg, vertical = Spacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
    ) {
        Surface(
            shape = RoundedCornerShape(Corners.Full),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
            modifier =
                Modifier
                    .clip(RoundedCornerShape(Corners.Full))
                    .clickable { onSelectFolder(null) },
        ) {
            Text(
                text = s.workspace.hostsFolderAll,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.Md, vertical = 6.dp),
            )
        }
        breadcrumbs.forEachIndexed { index, folder ->
            val isCurrent = index == breadcrumbs.lastIndex
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Surface(
                shape = RoundedCornerShape(Corners.Full),
                color =
                    if (isCurrent) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                border =
                    if (isCurrent) {
                        null
                    } else {
                        BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant)
                    },
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(Corners.Full))
                        .clickable(enabled = !isCurrent) { onSelectFolder(folder.id) },
            ) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color =
                        if (isCurrent) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = Spacing.Md, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun TagFilterBar(
    tags: List<String>,
    selectedTag: String?,
    onSelectTag: (String) -> Unit,
) {
    val s = LocalAppStrings.current
    val chipColors =
        FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Lg, vertical = Spacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        FilterChip(
            selected = selectedTag == null,
            onClick = { if (selectedTag != null) onSelectTag(selectedTag) },
            label = { Text(s.workspace.hostsTagsAll) },
            colors = chipColors,
        )
        tags.forEach { tag ->
            val active = selectedTag.equals(tag, ignoreCase = true)
            FilterChip(
                selected = active,
                onClick = { onSelectTag(tag) },
                label = { Text("#$tag") },
                colors = chipColors,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    folder: HostFolder,
    hostCount: Int,
    subfolderCount: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val s = LocalAppStrings.current
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
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
                    Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(Sizes.IconMedium),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = s.workspace.hostsFolderCount(hostCount, subfolderCount),
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
            )
        }
    }
}

@Composable
private fun FolderEditDialog(
    existing: HostFolder?,
    folders: List<HostFolder>,
    defaultParentId: String?,
    onSave: (HostFolder) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var parentId by remember(existing, defaultParentId) { mutableStateOf(existing?.parentId ?: defaultParentId) }
    var showParentPicker by remember { mutableStateOf(false) }

    val parentOptions =
        remember(folders, existing) {
            if (existing == null) {
                folders
            } else {
                folders.filter { f -> !wouldCreateFolderCycle(folders, existing.id, f.id) }
            }
        }
    val parentName = parentOptions.firstOrNull { it.id == parentId }?.name ?: s.workspace.hostsFolderRoot

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) s.workspace.hostsFolderNew else s.workspace.hostsFolderEdit) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(s.workspace.hostsFolderName) },
                    singleLine = true,
                    shape = RoundedCornerShape(Corners.Md),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (parentOptions.isNotEmpty()) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = Sizes.TouchTarget)
                                .clip(RoundedCornerShape(Corners.Md))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { showParentPicker = true }
                                .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(Sizes.IconSmall),
                        )
                        Spacer(Modifier.width(Spacing.Sm))
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.workspace.hostsFolderParent,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                parentName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(Sizes.IconSmall),
                        )
                        Spacer(Modifier.width(Spacing.Xs))
                        Text(s.workspace.hostsFolderDelete, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.trim().isNotEmpty(),
                onClick = {
                    val folder =
                        HostFolder(
                            id = existing?.id ?: newId(),
                            name = name.trim(),
                            parentId = parentId,
                            createdAt = existing?.createdAt ?: Clock.System.now().toEpochMilliseconds(),
                        )
                    onSave(folder)
                },
            ) {
                Text(s.editSave)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s.navBack) }
        },
    )

    if (showParentPicker) {
        FolderPickerDialog(
            title = s.workspace.hostsFolderParent,
            folders = parentOptions,
            selectedFolderId = parentId,
            onSelect = {
                parentId = it
                showParentPicker = false
            },
            onDismiss = { showParentPicker = false },
        )
    }
}

@Composable
internal fun FolderPickerDialog(
    title: String,
    folders: List<HostFolder>,
    selectedFolderId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    val sortedFolders = remember(folders) { folders.sortedBy { it.name.lowercase() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                FolderPickerOptionRow(
                    label = s.workspace.hostsFolderRoot,
                    selected = selectedFolderId == null,
                    onClick = { onSelect(null) },
                )
                sortedFolders.forEach { folder ->
                    FolderPickerOptionRow(
                        label = folder.name,
                        selected = folder.id == selectedFolderId,
                        onClick = { onSelect(folder.id) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(s.navBack) }
        },
    )
}

@Composable
private fun FolderPickerOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.TouchTarget)
                .clip(RoundedCornerShape(Corners.Md))
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    },
                ).clickable(onClick = onClick)
                .padding(horizontal = Spacing.Md, vertical = Spacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            tint =
                if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.size(Sizes.IconSmall),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(Sizes.IconSmall),
            )
        }
    }
}
