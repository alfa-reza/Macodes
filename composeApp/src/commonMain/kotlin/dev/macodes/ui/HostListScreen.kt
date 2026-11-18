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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.PushPin
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.macodes.agent.AgentBridgeController
import dev.macodes.agent.AgentBridgeState
import dev.macodes.data.ConnectionMode
import dev.macodes.data.Host
import dev.macodes.data.HostFilterState
import dev.macodes.data.HostFolder
import dev.macodes.data.HostTag
import dev.macodes.data.countFolderContents
import dev.macodes.data.filterHostOrganization
import dev.macodes.data.formatSafeSshCommand
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
import dev.macodes.ssh.formatHostCardSpecs
import dev.macodes.ui.theme.Alphas
import dev.macodes.ui.theme.Corners
import dev.macodes.ui.theme.Sizes
import dev.macodes.ui.theme.Spacing
import dev.macodes.ui.theme.StatusColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    aiAgentEnabled: Boolean = true,
    onOpenSession: (TerminalController) -> Unit,
    onOpenSftp: (Host, SftpSession?) -> Unit,
    onStartSftp: (Host) -> Unit,
    onCloseAllSessions: (Host) -> Unit,
    onBack: (() -> Unit)? = null,
    folders: List<HostFolder> = emptyList(),
    tagDefinitions: List<HostTag> = emptyList(),
    onAddInFolder: ((String?) -> Unit)? = null,
    onUpsertFolder: (HostFolder) -> Unit = {},
    onDeleteFolder: (String, Boolean) -> Unit = { _, _ -> },
    onMoveHostsToFolder: (Set<String>, String?) -> Unit = { _, _ -> },
    onOpenDetails: (Host) -> Unit = onEdit,
    onDuplicate: (Host) -> Unit = {},
    onTogglePin: (Host) -> Unit = {},
    onUpsertTag: (HostTag) -> Unit = {},
    onDeleteTag: (String) -> Unit = {},
    onUpdateHostsTags: (Set<String>, Set<String>, Set<String>) -> Unit = { _, _, _ -> },
    listState: LazyListState = rememberLazyListState(),
    currentFolderId: String? = null,
    onCurrentFolderChange: ((String?) -> Unit)? = null,
    query: String = "",
    onQueryChange: ((String) -> Unit)? = null,
    searchExpanded: Boolean = false,
    onSearchExpandedChange: ((Boolean) -> Unit)? = null,
    filterState: HostFilterState = HostFilterState(),
    onFilterStateChange: ((HostFilterState) -> Unit)? = null,
) {
    val s = LocalAppStrings.current
    val ws = s.workspace
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var internalQuery by remember { mutableStateOf(query) }
    var internalSearchExpanded by remember { mutableStateOf(searchExpanded) }
    var internalFolderId by remember { mutableStateOf(currentFolderId) }
    var internalFilterState by remember { mutableStateOf(filterState) }

    val activeQuery = if (onQueryChange != null) query else internalQuery
    val activeSearchExpanded = if (onSearchExpandedChange != null) searchExpanded else internalSearchExpanded
    val activeFolderId = if (onCurrentFolderChange != null) currentFolderId else internalFolderId
    val activeFilterState = if (onFilterStateChange != null) filterState else internalFilterState

    fun setQuery(v: String) {
        if (onQueryChange != null) onQueryChange(v) else internalQuery = v
    }

    fun setSearchExpanded(v: Boolean) {
        if (onSearchExpandedChange != null) onSearchExpandedChange(v) else internalSearchExpanded = v
    }

    fun setFolderId(v: String?) {
        if (onCurrentFolderChange != null) onCurrentFolderChange(v) else internalFolderId = v
    }

    fun setFilterState(v: HostFilterState) {
        if (onFilterStateChange != null) onFilterStateChange(v) else internalFilterState = v
    }

    var addSheetOpen by remember { mutableStateOf(false) }
    var filterSheetOpen by remember { mutableStateOf(false) }
    var tagManagerOpen by remember { mutableStateOf(false) }
    var editingFolder by remember { mutableStateOf<HostFolder?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showMoveToFolderDialog by remember { mutableStateOf(false) }
    var singleMoveHost by remember { mutableStateOf<Host?>(null) }
    var pendingDeleteFolder by remember { mutableStateOf<HostFolder?>(null) }
    var pendingDeleteHosts by remember { mutableStateOf<List<Host>>(emptyList()) }
    var tagTargetHosts by remember { mutableStateOf<List<Host>>(emptyList()) }

    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(activeSearchExpanded) {
        if (activeSearchExpanded) {
            delay(150)
            searchFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(folders, activeFolderId) {
        if (activeFolderId != null && folders.none { it.id == activeFolderId }) {
            setFolderId(null)
        }
    }

    val tagColorsByName =
        remember(tagDefinitions) {
            tagDefinitions.associate { it.name.lowercase() to it.colorIndex }
        }

    val effectiveTagDefinitions =
        remember(hosts, tagDefinitions) {
            val seen = tagDefinitions.associateBy { it.name.lowercase() }.toMutableMap()
            for (h in hosts) {
                for (raw in h.tags) {
                    val clean = raw.trim().removePrefix("#")
                    if (clean.isNotEmpty() && clean.lowercase() !in seen) {
                        seen[clean.lowercase()] = HostTag(id = clean, name = clean)
                    }
                }
            }
            seen.values.sortedBy { it.name.lowercase() }
        }

    val orgView =
        remember(hosts, folders, activeFolderId, activeFilterState, activeQuery, tagDefinitions) {
            filterHostOrganization(
                hosts = hosts,
                folders = folders,
                currentFolderId = activeFolderId,
                filterState = activeFilterState,
                query = activeQuery,
                tagDefinitions = tagDefinitions,
            )
        }
    val filtered = orgView.visibleHosts
    val hasGlobalFilter = activeQuery.trim().isNotEmpty() || activeFilterState.hasActiveFilters

    // Multi-selection mode
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

    PlatformBackHandler(enabled = selectionMode || activeFolderId != null || activeSearchExpanded) {
        when {
            selectionMode -> exitSelection()
            activeSearchExpanded -> {
                setSearchExpanded(false)
                setQuery("")
            }
            else -> setFolderId(folders.firstOrNull { it.id == activeFolderId }?.parentId)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    onManageTags = { tagTargetHosts = selected.values.toList() },
                    onSelectAll = { filtered.forEach { selected[it.id] = it } },
                    onConnect = {
                        onConnectBatch(selected.values.toList())
                        exitSelection()
                    },
                    onDisconnect = {
                        selected.values.forEach(onDisconnect)
                        exitSelection()
                    },
                    onRemove = { pendingDeleteHosts = selected.values.toList() },
                )
            } else {
                MacodesHeader(
                    title = s.appTabHosts,
                    onBack = onBack,
                    actions = {
                        IconButton(
                            onClick = {
                                if (activeSearchExpanded) {
                                    setSearchExpanded(false)
                                    setQuery("")
                                } else {
                                    setSearchExpanded(true)
                                }
                            },
                            modifier = Modifier.size(Sizes.TouchTarget),
                        ) {
                            Icon(
                                if (activeSearchExpanded) Icons.Filled.Close else Icons.Filled.Search,
                                contentDescription = if (activeSearchExpanded) s.navBack else s.hostsSearch,
                            )
                        }
                        IconButton(
                            onClick = { filterSheetOpen = true },
                            modifier = Modifier.size(Sizes.TouchTarget),
                        ) {
                            Box {
                                Icon(
                                    Icons.Filled.FilterList,
                                    contentDescription = ws.hostOrg.filterAction,
                                )
                                if (activeFilterState.hasActiveFilters) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .align(Alignment.TopEnd)
                                                .size(Sizes.AgentTimelineDot)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary),
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                FloatingActionButton(
                    onClick = { addSheetOpen = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(Corners.Lg),
                ) { Icon(Icons.Default.Add, s.hostsAdd) }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AnimatedVisibility(
                visible = activeSearchExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                MacodesSearchPill(
                    value = activeQuery,
                    onValueChange = { setQuery(it) },
                    placeholder = s.hostsSearch,
                    modifier =
                        Modifier
                            .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm)
                            .focusRequester(searchFocusRequester),
                )
            }
            AnimatedVisibility(
                visible = activeFilterState.hasActiveFilters,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                ActiveHostFilterChipsBar(
                    filterState = activeFilterState,
                    ws = ws,
                    onRemovePinned = { setFilterState(activeFilterState.copy(pinnedOnly = false)) },
                    onRemoveTag = { tag ->
                        setFilterState(
                            activeFilterState.copy(
                                selectedTags = activeFilterState.selectedTags.filterNot { it.equals(tag, ignoreCase = true) }.toSet(),
                            ),
                        )
                    },
                    onRemoveProtocol = { mode ->
                        setFilterState(activeFilterState.copy(selectedProtocols = activeFilterState.selectedProtocols - mode))
                    },
                    onRemoveProvider = { provider ->
                        setFilterState(activeFilterState.copy(selectedProviders = activeFilterState.selectedProviders - provider))
                    },
                    onClearAll = { setFilterState(activeFilterState.copy(selectedTags = emptySet(), selectedProtocols = emptySet(), selectedProviders = emptySet(), pinnedOnly = false)) },
                )
            }
            if (orgView.breadcrumbs.isNotEmpty() && !hasGlobalFilter) {
                FolderBreadcrumbsBar(
                    breadcrumbs = orgView.breadcrumbs,
                    onSelectFolder = { setFolderId(it) },
                    onEditCurrentFolder = {
                        orgView.breadcrumbs.lastOrNull()?.let { editingFolder = it }
                    },
                )
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
                                if (activeFolderId != null) Icons.Filled.FolderOpen else Icons.Filled.Terminal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp),
                            )
                            Text(
                                when {
                                    hosts.isEmpty() && folders.isEmpty() -> s.hostsEmpty
                                    activeFolderId != null && !hasGlobalFilter -> ws.hostsFolderEmpty
                                    hasGlobalFilter -> ws.hostOrg.noFilterResults
                                    else -> s.hostsNoMatch
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (hosts.isEmpty() && folders.isEmpty() || (activeFolderId != null && !hasGlobalFilter)) {
                                Button(
                                    onClick = {
                                        if (onAddInFolder != null) onAddInFolder(activeFolderId) else onAdd()
                                    },
                                    shape = RoundedCornerShape(Corners.Md),
                                    modifier = Modifier.heightIn(min = Sizes.TouchTarget),
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(Spacing.Xs))
                                    Text(s.hostsAdd)
                                }
                            } else if (hasGlobalFilter) {
                                OutlinedButton(
                                    onClick = {
                                        setQuery("")
                                        setFilterState(HostFilterState(sortOrder = activeFilterState.sortOrder))
                                    },
                                    shape = RoundedCornerShape(Corners.Md),
                                    modifier = Modifier.heightIn(min = Sizes.TouchTarget),
                                ) {
                                    Text(ws.hostOrg.filterClearAll)
                                }
                            }
                        }
                    }
                }
            } else {
                val activeFolder = orgView.breadcrumbs.lastOrNull()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Spacing.Lg, end = Spacing.Lg, top = Spacing.Sm, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
                ) {
                    if (orgView.visibleFolders.isNotEmpty()) {
                        item(key = "section-folders") {
                            HostSectionHeader(title = ws.hostsFoldersLabel)
                        }
                        item(key = "folders-group") {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(Corners.Lg),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Column {
                                    orgView.visibleFolders.forEachIndexed { idx, folder ->
                                        val counts =
                                            remember(hosts, folders, folder.id) {
                                                countFolderContents(hosts, folders, folder.id)
                                            }
                                        FolderRow(
                                            folder = folder,
                                            hostCount = counts.hostCount,
                                            subfolderCount = counts.subfolderCount,
                                            onClick = { setFolderId(folder.id) },
                                            onLongClick = { editingFolder = folder },
                                        )
                                        if (idx < orgView.visibleFolders.lastIndex) {
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (filtered.isNotEmpty()) {
                        item(key = "section-hosts") {
                            val headerLabel =
                                when {
                                    hasGlobalFilter -> ws.hostOrg.resultsCount(filtered.size)
                                    activeFolder != null -> activeFolder.name
                                    else -> ws.hostOrg.allHostsSection
                                }
                            HostSectionHeader(
                                title = headerLabel,
                                modifier =
                                    Modifier.padding(
                                        top = if (orgView.visibleFolders.isNotEmpty()) Spacing.Sm else Spacing.None,
                                    ),
                            )
                        }
                    }

                    items(filtered, key = { it.id }) { host ->
                        val isSelected = host.id in selected
                        val sessions = hostSessions[host.id].orEmpty()
                        HostCard(
                            host = host,
                            sessions = sessions,
                            tagColorsByName = tagColorsByName,
                            selectionMode = selectionMode,
                            selected = isSelected,
                            onCardClick = {
                                if (selectionMode) {
                                    if (isSelected) selected.remove(host.id) else selected[host.id] = host
                                } else {
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
                            onDetails = { onOpenDetails(host) },
                            onEdit = { onEdit(host) },
                            onTogglePin = { onTogglePin(host) },
                            onManageTags = { tagTargetHosts = listOf(host) },
                            onMoveToFolder = { singleMoveHost = host },
                            onDuplicate = { onDuplicate(host) },
                            onCopyAddress = {
                                clipboard.setText(AnnotatedString("${host.username}@${host.hostname}:${host.port}"))
                                scope.launch { snackbarHostState.showSnackbar(ws.hostOrg.copiedAddressMessage) }
                            },
                            onCopySshCommand = {
                                clipboard.setText(AnnotatedString(formatSafeSshCommand(host)))
                                scope.launch { snackbarHostState.showSnackbar(ws.hostOrg.copiedSshCommandMessage) }
                            },
                            onDelete = { pendingDeleteHosts = listOf(host) },
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
                            aiAgentEnabled = aiAgentEnabled,
                            onOpenSession = onOpenSession,
                            onOpenSftp = onOpenSftp,
                            onCloseAllSessions = { onCloseAllSessions(host) },
                        )
                    }
                }
            }
        }
    }

    if (addSheetOpen) {
        val addSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { addSheetOpen = false },
            sheetState = addSheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.Lg)
                        .padding(bottom = Spacing.Xl)
                        .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
            ) {
                AddOptionRow(
                    icon = Icons.Filled.Terminal,
                    label = ws.hostsNewHost,
                    onClick = {
                        addSheetOpen = false
                        if (onAddInFolder != null) onAddInFolder(activeFolderId) else onAdd()
                    },
                )
                AddOptionRow(
                    icon = Icons.Filled.CreateNewFolder,
                    label = ws.hostsFolderNew,
                    onClick = {
                        addSheetOpen = false
                        showNewFolderDialog = true
                    },
                )
            }
        }
    }

    if (filterSheetOpen) {
        HostFilterBottomSheet(
            initialFilter = activeFilterState,
            availableTags = effectiveTagDefinitions,
            ws = ws,
            s = s,
            onDismiss = { filterSheetOpen = false },
            onManageTags = { tagManagerOpen = true },
            onApply = { updated ->
                setFilterState(updated)
                filterSheetOpen = false
            },
        )
    }

    if (tagManagerOpen) {
        HostTagManagerDialog(
            tags = effectiveTagDefinitions,
            ws = ws,
            s = s,
            onUpsertTag = onUpsertTag,
            onDeleteTag = onDeleteTag,
            onDismiss = { tagManagerOpen = false },
        )
    }

    if (tagTargetHosts.isNotEmpty()) {
        val isSingle = tagTargetHosts.size == 1
        val initialSelected =
            if (isSingle) {
                tagTargetHosts.first().tags.toSet()
            } else {
                emptySet()
            }
        HostAssignTagsDialog(
            targetCount = tagTargetHosts.size,
            initialSelectedTags = initialSelected,
            availableTags = effectiveTagDefinitions,
            ws = ws,
            s = s,
            onCreateTag = { name ->
                onUpsertTag(HostTag(id = newId(), name = name))
            },
            onApplySingleHost = { nextTags ->
                val target = tagTargetHosts.firstOrNull() ?: return@HostAssignTagsDialog
                val toRemove = target.tags.filterNot { existing -> nextTags.any { it.equals(existing, ignoreCase = true) } }.toSet()
                val toAdd = nextTags.filterNot { next -> target.tags.any { it.equals(next, ignoreCase = true) } }.toSet()
                onUpdateHostsTags(setOf(target.id), toAdd, toRemove)
            },
            onApplyBulk = { addTags, removeTags ->
                onUpdateHostsTags(tagTargetHosts.mapTo(HashSet()) { it.id }, addTags, removeTags)
                exitSelection()
            },
            onDismiss = { tagTargetHosts = emptyList() },
        )
    }

    if (showNewFolderDialog) {
        FolderEditDialog(
            existing = null,
            folders = folders,
            defaultParentId = activeFolderId,
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
        val counts = countFolderContents(hosts, folders, target.id)
        val hasContents = counts.hostCount > 0 || counts.subfolderCount > 0
        AlertDialog(
            onDismissRequest = { pendingDeleteFolder = null },
            title = { Text(ws.hostsFolderDelete) },
            text = {
                Text(
                    if (hasContents) {
                        ws.hostOrg.folderDeleteNonEmptyConfirm(target.name, counts.hostCount, counts.subfolderCount)
                    } else {
                        ws.hostsFolderDeleteConfirm(target.name)
                    },
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (hasContents) {
                        TextButton(
                            onClick = {
                                onDeleteFolder(target.id, false)
                                if (activeFolderId == target.id) setFolderId(target.parentId)
                                pendingDeleteFolder = null
                            },
                        ) {
                            Text(ws.hostOrg.folderDeleteMoveContents)
                        }
                        TextButton(
                            onClick = {
                                onDeleteFolder(target.id, true)
                                if (activeFolderId == target.id) setFolderId(target.parentId)
                                pendingDeleteFolder = null
                            },
                        ) {
                            Text(ws.hostOrg.folderDeleteEverything, color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        TextButton(
                            onClick = {
                                onDeleteFolder(target.id, false)
                                if (activeFolderId == target.id) setFolderId(target.parentId)
                                pendingDeleteFolder = null
                            },
                        ) {
                            Text(ws.hostsFolderDelete, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteFolder = null }) { Text(s.terminalCancel) }
            },
        )
    }

    if (pendingDeleteHosts.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingDeleteHosts = emptyList() },
            title = { Text(ws.hostOrg.deleteConfirmTitle) },
            text = {
                Text(
                    if (pendingDeleteHosts.size == 1) {
                        val h = pendingDeleteHosts.first()
                        ws.hostOrg.deleteSingleConfirm(h.name.ifBlank { h.hostname })
                    } else {
                        ws.hostOrg.deleteSelectedConfirm(pendingDeleteHosts.size)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteHosts.forEach(onDelete)
                        pendingDeleteHosts = emptyList()
                        if (selectionMode) exitSelection()
                    },
                ) {
                    Text(s.hostsDelete, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteHosts = emptyList() }) {
                    Text(s.terminalCancel)
                }
            },
        )
    }

    singleMoveHost?.let { hostTarget ->
        FolderPickerDialog(
            title = ws.hostsFolderMoveTo,
            folders = folders,
            selectedFolderId = hostTarget.folderId,
            onSelect = { targetFolderId ->
                onMoveHostsToFolder(setOf(hostTarget.id), targetFolderId)
                singleMoveHost = null
            },
            onDismiss = { singleMoveHost = null },
        )
    }

    if (showMoveToFolderDialog && selected.isNotEmpty()) {
        FolderPickerDialog(
            title = ws.hostsFolderMoveTo,
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

@Composable
private fun HostSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = Spacing.Xs, vertical = Spacing.Xs),
    )
}

@Composable
private fun AddOptionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.TouchTarget)
                .clip(RoundedCornerShape(Corners.Md))
                .clickable(onClick = onClick)
                .padding(horizontal = Spacing.Md, vertical = Spacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(Sizes.IconMedium),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ActiveHostFilterChipsBar(
    filterState: HostFilterState,
    ws: WorkspaceStrings,
    onRemovePinned: () -> Unit,
    onRemoveTag: (String) -> Unit,
    onRemoveProtocol: (ConnectionMode) -> Unit,
    onRemoveProvider: (dev.macodes.data.CloudProvider) -> Unit,
    onClearAll: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Lg, vertical = Spacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
    ) {
        if (filterState.pinnedOnly) {
            RemovableFilterChip(label = ws.hostOrg.filterPinnedOnly, onRemove = onRemovePinned)
        }
        filterState.selectedProtocols.forEach { mode ->
            RemovableFilterChip(
                label = if (mode == ConnectionMode.MOSH) "Mosh" else "SSH",
                onRemove = { onRemoveProtocol(mode) },
            )
        }
        filterState.selectedProviders.forEach { provider ->
            RemovableFilterChip(
                label = provider.shortBadge,
                onRemove = { onRemoveProvider(provider) },
            )
        }
        filterState.selectedTags.forEach { tag ->
            RemovableFilterChip(
                label = "#$tag",
                onRemove = { onRemoveTag(tag) },
            )
        }
        TextButton(
            onClick = onClearAll,
            modifier = Modifier.heightIn(min = Sizes.TouchTarget),
        ) {
            Text(ws.hostOrg.filterClearAll)
        }
    }
}

@Composable
private fun RemovableFilterChip(
    label: String,
    onRemove: () -> Unit,
) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = {
            Icon(
                Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(InputChipDefaults.IconSize),
            )
        },
        colors =
            InputChipDefaults.inputChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
    )
}

/** Batch mode header: Close on the left, “x selected” in the middle, and Edit + three-dot menu on the right. */
@Composable
private fun SelectionHeader(
    count: Int,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onMoveToFolder: () -> Unit,
    onManageTags: () -> Unit,
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
                        text = { Text(s.workspace.hostOrg.manageTagsAction) },
                        leadingIcon = { Icon(Icons.Filled.Label, null) },
                        onClick = {
                            moreOpen = false
                            onManageTags()
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
                        text = { Text(s.hostsDelete, color = MaterialTheme.colorScheme.error) },
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
 * Compact Host card:
 * - Primary: Host name + pin indicator + SSH/Mosh badge
 * - Secondary: username@hostname:port
 * - Optional metadata (only when non-empty): Cloud Provider + vCPU/RAM specs + max 2 Tags (+N)
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun HostCard(
    host: Host,
    sessions: List<HostSessionItem>,
    tagColorsByName: Map<String, Int?>,
    selectionMode: Boolean,
    selected: Boolean,
    onCardClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onLongClick: () -> Unit,
    onTerminal: () -> Unit,
    onDetails: () -> Unit,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onManageTags: () -> Unit,
    onMoveToFolder: () -> Unit,
    onDuplicate: () -> Unit,
    onCopyAddress: () -> Unit,
    onCopySshCommand: () -> Unit,
    onDelete: () -> Unit,
    onHerdr: () -> Unit,
    onSftp: () -> Unit,
    onAgents: () -> Unit,
    aiAgentEnabled: Boolean = true,
    onOpenSession: (TerminalController) -> Unit,
    onOpenSftp: (Host, SftpSession?) -> Unit,
    onCloseAllSessions: () -> Unit,
) {
    val s = LocalAppStrings.current
    val sys = host.system.ifBlank { host.hostname }
    val connecting = sessions.any { it.isConnecting }
    val isConnected = sessions.any { it.isConnected }
    val isMosh = host.connectionMode == ConnectionMode.MOSH
    val protoLabel = if (isMosh) s.hostsModeMosh else s.hostsModeSsh
    val title = host.name.ifBlank { host.hostname }
    val endpoint = "${host.username}@${host.hostname}:${host.port}"
    val specsSummary = formatHostCardSpecs(host.inventory)
    val hasOptionalMeta = host.cloudProvider != null || specsSummary != null || host.tags.isNotEmpty()

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
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = Spacing.Md, end = Spacing.Xs, top = Spacing.Md, bottom = Spacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(Corners.Md))
                    .background(systemColor(sys))
                    .clickable(onClick = onAvatarClick),
                contentAlignment = Alignment.Center,
            ) {
                if (connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Sizes.IconSmall),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    SystemAvatarIcon(sys, Sizes.IconSmall)
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
                ) {
                    if (host.isPinned) {
                        Icon(
                            imageVector = Icons.Filled.PushPin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
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
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    if (isConnected) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(StatusColors.Connected),
                        )
                    }
                }

                Text(
                    endpoint,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (hasOptionalMeta) {
                    FlowRow(
                        modifier = Modifier.padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                    ) {
                        host.cloudProvider?.let { provider ->
                            Surface(
                                shape = RoundedCornerShape(Corners.Sm),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = Alphas.SELECTION_BORDER),
                            ) {
                                Text(
                                    text = provider.shortBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        if (specsSummary != null) {
                            Surface(
                                shape = RoundedCornerShape(Corners.Sm),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ) {
                                Text(
                                    text = specsSummary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        val visibleTags = host.tags.take(2)
                        val overflowCount = host.tags.size - visibleTags.size
                        visibleTags.forEach { tag ->
                            HostTagBadge(
                                tagName = tag,
                                colorIndex = tagColorsByName[tag.trim().removePrefix("#").lowercase()],
                            )
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
                Box(
                    Modifier
                        .padding(end = Spacing.Sm)
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
                    isPinned = host.isPinned,
                    sessions = sessions,
                    onTerminal = onTerminal,
                    onDetails = onDetails,
                    onEdit = onEdit,
                    onTogglePin = onTogglePin,
                    onManageTags = onManageTags,
                    onMoveToFolder = onMoveToFolder,
                    onDuplicate = onDuplicate,
                    onCopyAddress = onCopyAddress,
                    onCopySshCommand = onCopySshCommand,
                    onDelete = onDelete,
                    onHerdr = onHerdr,
                    onSftp = onSftp,
                    onAgents = onAgents,
                    aiAgentEnabled = aiAgentEnabled,
                    onOpenTerminal = onOpenSession,
                    onOpenSftp = onOpenSftp,
                    onCloseAll = onCloseAllSessions,
                )
            }
        }
    }
}

/** Unified card action menu: Connect, Details, Edit, Pin, Tags, Folder, Duplicate, Copy, Delete, and live sessions. */
@Composable
private fun HostMoreMenu(
    isPinned: Boolean,
    sessions: List<HostSessionItem>,
    onTerminal: () -> Unit,
    onDetails: () -> Unit,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onManageTags: () -> Unit,
    onMoveToFolder: () -> Unit,
    onDuplicate: () -> Unit,
    onCopyAddress: () -> Unit,
    onCopySshCommand: () -> Unit,
    onDelete: () -> Unit,
    onHerdr: () -> Unit,
    onSftp: () -> Unit,
    onAgents: () -> Unit,
    aiAgentEnabled: Boolean = true,
    onOpenTerminal: (TerminalController) -> Unit,
    onOpenSftp: (Host, SftpSession?) -> Unit,
    onCloseAll: () -> Unit,
) {
    val s = LocalAppStrings.current
    val ws = s.workspace
    var open by remember { mutableStateOf(false) }
    val activeCount = sessions.count { it.isActive }
    val connectingOnly = activeCount > 0 && sessions.filter { it.isActive }.all { it.isConnecting }
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.size(Sizes.TouchTarget),
        ) {
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
            DropdownMenuItem(
                text = { Text(s.hostsConnect) },
                leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                onClick = {
                    open = false
                    onTerminal()
                },
            )
            DropdownMenuItem(
                text = { Text(ws.hostOrg.detailsAction) },
                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                onClick = {
                    open = false
                    onDetails()
                },
            )
            DropdownMenuItem(
                text = { Text(s.hostsEdit) },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = {
                    open = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(if (isPinned) ws.hostOrg.unpinAction else ws.hostOrg.pinAction) },
                leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                onClick = {
                    open = false
                    onTogglePin()
                },
            )
            DropdownMenuItem(
                text = { Text(ws.hostOrg.manageTagsAction) },
                leadingIcon = { Icon(Icons.Default.Label, contentDescription = null) },
                onClick = {
                    open = false
                    onManageTags()
                },
            )
            DropdownMenuItem(
                text = { Text(ws.hostsFolderMoveTo) },
                leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                onClick = {
                    open = false
                    onMoveToFolder()
                },
            )
            DropdownMenuItem(
                text = { Text(ws.hostOrg.duplicateAction) },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                onClick = {
                    open = false
                    onDuplicate()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(ws.hostOrg.copyAddressAction) },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                onClick = {
                    open = false
                    onCopyAddress()
                },
            )
            DropdownMenuItem(
                text = { Text(ws.hostOrg.copySshCommandAction) },
                leadingIcon = { Icon(Icons.Default.ContentPasteGo, contentDescription = null) },
                onClick = {
                    open = false
                    onCopySshCommand()
                },
            )
            HorizontalDivider()
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
            if (aiAgentEnabled) {
                DropdownMenuItem(
                    text = { Text(s.hostsActionAgents) },
                    leadingIcon = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                    onClick = {
                        open = false
                        onAgents()
                    },
                )
            }
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
                val statusLabel =
                    when (item) {
                        is HostSessionItem.Terminal ->
                            when (item.controller.status) {
                                ConnStatus.CONNECTED -> s.hostsActive
                                ConnStatus.CONNECTING, ConnStatus.AUTH -> s.connStatusConnecting
                                else -> s.connStatusClosed
                            }
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
                            if (item.session != null) StatusColors.Connected else StatusColors.Neutral
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
            HorizontalDivider()
            if (sessions.isNotEmpty()) {
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
            DropdownMenuItem(
                text = { Text(s.hostsDelete, color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = {
                    open = false
                    onDelete()
                },
            )
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
    onEditCurrentFolder: () -> Unit,
) {
    val s = LocalAppStrings.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Lg, vertical = Spacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
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
        IconButton(
            onClick = onEditCurrentFolder,
            modifier = Modifier.size(Sizes.TouchTarget),
        ) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = s.workspace.hostsFolderEdit,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Sizes.IconSmall),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    folder: HostFolder,
    hostCount: Int,
    subfolderCount: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val s = LocalAppStrings.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.TouchTarget)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ).padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = folder.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = s.workspace.hostsFolderCount(hostCount, subfolderCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Sizes.IconSmall),
        )
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
