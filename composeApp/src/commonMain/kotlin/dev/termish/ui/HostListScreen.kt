package dev.termish.ui

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.termish.agent.AgentBridgeController
import dev.termish.agent.AgentBridgeState
import dev.termish.data.ConnectionMode
import dev.termish.data.Host
import dev.termish.generated.resources.Res
import dev.termish.generated.resources.host_alma
import dev.termish.generated.resources.host_alpine
import dev.termish.generated.resources.host_arch
import dev.termish.generated.resources.host_centos
import dev.termish.generated.resources.host_cloud
import dev.termish.generated.resources.host_debian
import dev.termish.generated.resources.host_fedora
import dev.termish.generated.resources.host_fnos
import dev.termish.generated.resources.host_istoreos
import dev.termish.generated.resources.host_kali
import dev.termish.generated.resources.host_linux
import dev.termish.generated.resources.host_mac
import dev.termish.generated.resources.host_opensuse
import dev.termish.generated.resources.host_openwrt
import dev.termish.generated.resources.host_raspbian
import dev.termish.generated.resources.host_rocky
import dev.termish.generated.resources.host_synology
import dev.termish.generated.resources.host_ubuntu
import dev.termish.generated.resources.host_windows
import dev.termish.ssh.SftpSession
import dev.termish.ui.theme.Sizes
import dev.termish.ui.theme.StatusColors
import dev.termish.util.monospaceFontFamily
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
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

    data class Screen(
        val entry: ScreenSessionEntry,
    ) : HostSessionItem {
        override val hostId: String get() = entry.host.id
        override val createdAt: Long get() = entry.createdAt
        override val isActive: Boolean get() = true
        override val isConnecting: Boolean get() = entry.session == null
        override val isConnected: Boolean get() = entry.session != null
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
    onOpenScreenSession: (ScreenSessionEntry) -> Unit,
    onStartSftp: (Host) -> Unit,
    onOpenScreen: (Host) -> Unit,
    onCloseAllSessions: (Host) -> Unit,
) {
    val s = LocalAppStrings.current
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    // Wait until the search results expand and the entrance animation begins before refocusing; the keyboard should then pop up.
    LaunchedEffect(searchExpanded) {
        if (searchExpanded) {
            delay(150)
            searchFocusRequester.requestFocus()
        }
    }
    val filtered =
        hosts
            .filter { h ->
                query.isBlank() ||
                    h.name.contains(query, ignoreCase = true) ||
                    h.hostname.contains(query, ignoreCase = true) ||
                    h.username.contains(query, ignoreCase = true) ||
                    h.system.contains(query, ignoreCase = true) ||
                    h.tags.any { it.contains(query, ignoreCase = true) }
            }
            // Sorting order: Sort by creation time (new hosts are appended to the end); do not reorder based on the most recent connection—
            // After linking, the position of the list cards remains unchanged; you can rely on muscle memory (old data with `createdAt=0` is used as a fallback based on its name).
            .sortedWith(compareBy<Host> { it.createdAt }.thenBy { it.name.lowercase() })

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

    // In multi-select mode the system Back key exits batch mode first, not to the home screen
    PlatformBackHandler(enabled = selectionMode, onBack = { exitSelection() })

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
                TermishLargeHeader(
                    title = s.appTabHosts,
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
                FloatingActionButton(
                    onClick = onAdd,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(16.dp),
                ) { Icon(Icons.Default.Add, s.hostsAdd) }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AnimatedVisibility(
                visible = searchExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .focusRequester(searchFocusRequester),
                    placeholder = { Text(s.hostsSearch) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = s.navBack)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
            }
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (hosts.isEmpty()) s.hostsEmpty else s.hostsNoMatch,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
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
                                        is HostSessionItem.Screen -> onOpenScreenSession(target.entry)
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
                            onScreen = {
                                val existing = sessions.filterIsInstance<HostSessionItem.Screen>().firstOrNull()
                                if (existing != null) {
                                    onOpenScreenSession(existing.entry)
                                } else {
                                    onOpenScreen(host)
                                }
                            },
                            onAgents = { onAgents(host) },
                            onOpenSession = onOpenSession,
                            onOpenSftp = onOpenSftp,
                            onOpenScreenSession = onOpenScreenSession,
                            onCloseAllSessions = { onCloseAllSessions(host) },
                        )
                    }
                }
            }
        }
    }
}

/** Batch mode header: Close on the left, “x selected” in the middle, and Edit + three-dot menu on the right. */
@Composable
private fun SelectionHeader(
    count: Int,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onSelectAll: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRemove: () -> Unit,
) {
    val s = LocalAppStrings.current
    var moreOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = s.navBack)
            }
            Text(
                s.hostsSelectedCount(count),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = monospaceFontFamily(),
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
    onScreen: () -> Unit,
    onAgents: () -> Unit,
    onOpenSession: (TerminalController) -> Unit,
    onOpenSftp: (Host, SftpSession?) -> Unit,
    onOpenScreenSession: (ScreenSessionEntry) -> Unit,
    onCloseAllSessions: () -> Unit,
) {
    val s = LocalAppStrings.current
    val sys = host.system.ifBlank { host.hostname }
    val connecting = sessions.any { it.isConnecting }
    // First line: Alias (name) takes precedence; if empty, fall back to the host address.
    val title = host.name.ifBlank { host.hostname }
    // Card subtitles should always refer to the host itself; session status is consistently handled by the badge on the right and the “More” menu,
    // Avoid having “2 Disconnected” and the “2” badge occupy the same card.
    val detail =
        buildString {
            append(if (host.connectionMode == ConnectionMode.MOSH) s.hostsModeMosh else s.hostsModeSsh)
            append(" · ")
            append(host.username)
            if (host.system.isNotBlank()) {
                append(" · ")
                append(host.system)
            }
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .combinedClickable(
                    onClick = onCardClick,
                    onLongClick = onLongClick,
                ),
        shape = RoundedCornerShape(14.dp),
        // Follow the theme: light mode = white, dark mode = dark; highlight batch-selected items with a border in the primary color.
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ),
        border =
            BorderStroke(
                width = if (selected) 2.dp else 1.dp,
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // System Avatar (Rounded Square + White Icon): Tap to enter/toggle batch selection
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(systemColor(sys))
                        .clickable(onClick = onAvatarClick),
                    contentAlignment = Alignment.Center,
                ) {
                    if (connecting) {
                        // Connection: The avatar shows a spinning icon; once the connection is complete, you will be automatically redirected to the terminal screen.
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        SystemAvatarIcon(sys, 22.dp)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (selectionMode) {
                    // Selection indicator: checkmark circle
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Text(
                                "✓",
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                } else {
                    HostMoreMenu(
                        sessions = sessions,
                        onTerminal = onTerminal,
                        onHerdr = onHerdr,
                        onSftp = onSftp,
                        onScreen = onScreen,
                        onAgents = onAgents,
                        onOpenTerminal = onOpenSession,
                        onOpenSftp = onOpenSftp,
                        onOpenScreenSession = onOpenScreenSession,
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
    onScreen: () -> Unit,
    onAgents: () -> Unit,
    onOpenTerminal: (TerminalController) -> Unit,
    onOpenSftp: (Host, SftpSession?) -> Unit,
    onOpenScreenSession: (ScreenSessionEntry) -> Unit,
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
                text = { Text(s.hostsActionScreen) },
                leadingIcon = { Icon(Icons.Default.DesktopWindows, contentDescription = null) },
                onClick = {
                    open = false
                    onScreen()
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
                        is HostSessionItem.Screen ->
                            if (item.isConnecting) s.connStatusConnecting else s.hostsActive
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
                        is HostSessionItem.Screen ->
                            if (item.isConnecting) StatusColors.Warning else StatusColors.Connected
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
                                    "$launchLabel · ${formatSessionCreatedAt(item.createdAt)}",
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
                                    "${s.hostsActionFiles} · ${formatSessionCreatedAt(item.createdAt)}",
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
                    is HostSessionItem.Screen ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "${s.hostsActionScreen} · ${formatSessionCreatedAt(item.createdAt)}",
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
                                onOpenScreenSession(item.entry)
                            },
                        )
                    is HostSessionItem.Agent ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "${item.controller.currentSession?.title ?: s.hostsActionAgents} · " +
                                        formatSessionCreatedAt(item.createdAt),
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

/** For entries on the same day, show only the hour and minute; for entries spanning multiple days, show the month and day to ensure the notation is compact yet sufficient to distinguish between sessions. */
private fun formatSessionCreatedAt(millis: Long): String {
    if (millis <= 0L) return "--:--"
    val timeZone = TimeZone.currentSystemDefault()
    val created = Instant.fromEpochMilliseconds(millis).toLocalDateTime(timeZone)
    val today =
        Clock.System
            .now()
            .toLocalDateTime(timeZone)
            .date

    fun pad(value: Int): String = value.toString().padStart(2, '0')
    val time = "${pad(created.hour)}:${pad(created.minute)}"
    return if (created.date == today) time else "${pad(created.monthNumber)}-${pad(created.dayOfMonth)} $time"
}

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
        s.contains("ubuntu") -> Color(0xFFE95420)
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
        // Unknown system: brand emerald green (same hue as light-theme primary); white cloud-host icon pops on it
        else -> Color(0xFF059669)
    }
}
