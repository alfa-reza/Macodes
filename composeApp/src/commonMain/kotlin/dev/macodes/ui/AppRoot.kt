package dev.macodes.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.macodes.agent.AgentBridgeController
import dev.macodes.data.ASR_API_KEY_ACCOUNT
import dev.macodes.data.AgentProviderType
import dev.macodes.data.AppSettings
import dev.macodes.data.AsrProvider
import dev.macodes.data.AsrProviderType
import dev.macodes.data.Host
import dev.macodes.data.HostRepository
import dev.macodes.data.SECRET_SERVICE
import dev.macodes.data.SecretStore
import dev.macodes.data.ThemeMode
import dev.macodes.data.asrKeyAccount
import dev.macodes.data.newId
import dev.macodes.data.resolveCredentials
import dev.macodes.data.secretAccountFor
import dev.macodes.notify.NotificationCenter
import dev.macodes.notify.NotificationEvent
import dev.macodes.notify.NotificationPermissionState
import dev.macodes.notify.rememberNotificationPermissionController
import dev.macodes.screen.MAX_SCREEN_RECONNECT_ATTEMPTS
import dev.macodes.screen.ScreenSession
import dev.macodes.screen.ScreenSessionMessages
import dev.macodes.screen.ScreenUiState
import dev.macodes.screen.fallbackScreenQuality
import dev.macodes.screen.isUnstableScreenStream
import dev.macodes.ssh.AuthPrompt
import dev.macodes.ssh.HostKeyInfo
import dev.macodes.ssh.SftpSession
import dev.macodes.ssh.SshCallbacks
import dev.macodes.ssh.SshConnection
import dev.macodes.ssh.createSftpSession
import dev.macodes.ui.theme.TerminalThemes
import dev.macodes.ui.theme.MacodesTheme
import dev.macodes.util.BackgroundProtectionVendor
import dev.macodes.util.LocalTerminalFont
import dev.macodes.util.SessionKeepAlive
import dev.macodes.util.TermLog
import dev.macodes.util.TerminalFont
import dev.macodes.util.backgroundProtectionState
import dev.macodes.util.backgroundProtectionVendor
import dev.macodes.util.ioDispatcher
import dev.macodes.util.monospaceFontFamily
import dev.macodes.util.observeAppLifecycle
import dev.macodes.util.observeNetworkChange
import dev.macodes.util.openApplicationSettings
import dev.macodes.util.shouldShowBackgroundProtectionGuide
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

private enum class HomeTab { HOSTS, CONNECTIONS, SETTINGS }

private const val AGENT_IDLE_TIMEOUT_MS = 60_000L

private fun ScreenStrings.toSessionMessages(): ScreenSessionMessages =
    ScreenSessionMessages(
        connectionFailed = connectionFailed,
        readChannelFailed = readChannelFailed,
        tcpPortMissing = tcpPortMissing,
        tcpChannelFailed = tcpChannelFailed,
        tcpDisconnected = tcpDisconnected,
        screenInUse = screenInUse,
        screenRecordingPermissionMissing = screenRecordingPermissionMissing,
        ffmpegMissing = ffmpegMissing,
        unsupportedOs = unsupportedOs,
        relayUpgradeRequired = relayUpgradeRequired,
        displayMissing = displayMissing,
        serviceNotRunning = serviceNotRunning,
        waylandHint = waylandHint,
        waylandDependenciesMissing = waylandDependenciesMissing,
        screenAsleepHint = screenAsleepHint,
        screenLockedHint = screenLockedHint,
        firstFrameTimeout = firstFrameTimeout,
        decoderInitializationFailed = decoderInitializationFailed,
        decoderNoOutput = decoderNoOutput,
        decodingFailed = decodingFailed,
        playerUnsupported = playerUnsupported,
    )

/** Minimalist bottom bar items: icon + small monospace font tag; when selected, the item turns the theme’s green color; no capsule indicator. */
@Composable
private fun HomeTabItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    mono: FontFamily,
    modifier: Modifier = Modifier,
    badge: Int = 0,
    badgeAlert: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BadgedBox(
            badge = {
                if (badge > 0) {
                    Badge(
                        containerColor =
                            if (badgeAlert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        contentColor =
                            if (badgeAlert) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(if (badge > 99) "99+" else "$badge", fontFamily = mono)
                    }
                }
            },
        ) {
            Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = mono,
            color = color,
        )
    }
}

private sealed interface Screen {
    data object Home : Screen

    data class Edit(
        val hostId: String?,
    ) : Screen

    data class Agents(
        val hostId: String,
        val context: AgentLaunchContext? = null,
    ) : Screen

    /** Directly holds a reference to the controller: Sessions are managed by SessionManager and persist across screens. */
    data object Terminal : Screen
}

/** Subpages of the Settings page (all managed by AppRoot: back button + fullscreen + bottom tabs are hidden). */
enum class SettingsSubPage {
    TERMINAL,
    NOTIFICATION,
    DIAGNOSTICS,
    SNIPPETS,
    VOICE,
    PRIVACY,
}

/** Screen session entries (remote screen streaming). */
data class ScreenSessionEntry(
    val host: Host,
    /** Terminal tab session ID that creates this screen session: The floating window is shown only in that tab. */
    val ownerSessionId: String,
    val session: ScreenSession?,
    val uiState: ScreenUiState,
    /** Time when the entry was first created; remains unchanged during streaming reconnection and parameter toggling. */
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
)

@Composable
fun AppRoot(repository: HostRepository) {
    // Legacy Agent Provider Migration: type=DEEPSEEK (single DeepSeek) → OPENAI type +
    // Anthropic-compatible endpoints (Claude uses /anthropic; OpenCode/Pi uses OpenAI-compatible endpoints),
    // Refer to the Provider model from tuiniverse. Keep the old IDs, names, and baseURLs unchanged.
    fun migrateLegacyProviders(s: AppSettings): AppSettings {
        val providers = s.agentProviders
        val needsMigration =
            providers.any { provider ->
                provider.type == AgentProviderType.DEEPSEEK ||
                    provider.piProvider.isBlank() &&
                    provider.baseUrl.contains("api.deepseek.com")
            }
        if (!needsMigration) return s
        val migrated =
            providers.map { p ->
                val isDeepSeek = p.type == AgentProviderType.DEEPSEEK || p.baseUrl.contains("api.deepseek.com")
                if (!isDeepSeek) {
                    p
                } else {
                    p.copy(
                        type = AgentProviderType.OPENAI,
                        anthropicBaseUrl = p.anthropicBaseUrl.ifBlank { "${p.baseUrl.trimEnd('/')}/anthropic" },
                        piProvider = p.piProvider.ifBlank { "deepseek" },
                    )
                }
            }
        return s.copy(agentProviders = migrated)
    }

    // Perform the migration of the old speech recognition service configuration (single-instance `asrResourceId`) to the provider list:
    // On first launch, import the old resource IDs and old keys into the list to avoid requiring users to reconfigure them.
    fun migrateLegacyAsr(s: AppSettings): AppSettings {
        if (s.asrProviders.isNotEmpty() || s.asrResourceId.isBlank()) return s
        val legacyKey = SecretStore.get(SECRET_SERVICE, ASR_API_KEY_ACCOUNT)
        if (legacyKey.isNullOrBlank()) return s
        val p =
            AsrProvider(
                id = newId(),
                type = AsrProviderType.VOLC_STREAMING,
                name = "",
                resourceId = s.asrResourceId,
                enabled = true,
            )
        SecretStore.set(SECRET_SERVICE, asrKeyAccount(p.id), legacyKey)
        return s.copy(asrProviders = listOf(p))
    }

    remember(repository) {
        val stored = repository.loadSettings()
        val migrated = migrateLegacyProviders(migrateLegacyAsr(stored))
        if (migrated != stored) repository.saveSettings(migrated)
    }
    val settings by repository.appSettings.collectAsState()
    val notificationPermissionController = rememberNotificationPermissionController()
    var notificationPermissionState by remember { mutableStateOf(NotificationPermissionState.UNKNOWN) }
    var backgroundProtection by remember { mutableStateOf(backgroundProtectionState()) }
    val backgroundVendor = remember { backgroundProtectionVendor() }
    var showBackgroundProtectionGuide by remember { mutableStateOf(false) }
    var hosts by remember { mutableStateOf(repository.listHosts()) }
    val navigation = remember { NavigationStack<Screen>(Screen.Home) }
    val screen = navigation.current
    val scope = rememberCoroutineScope()

    fun navigate(target: Screen) {
        navigation.push(target)
    }

    fun navigateBack() {
        navigation.pop()
    }

    // Language Strings (Pre-declared for use by Lambda functions such as connectSftp)
    val appStrings = remember(settings.language) { appStringsFor(settings.language) }
    // Connection error messages toggle instantly based on language: SessionManager is reused during reassembly; the latest value must be retrieved via State
    val currentStrings = rememberUpdatedState(appStrings)
    val sessionManager = remember { SessionManager(repository, strings = { currentStrings.value }) }

    LaunchedEffect(notificationPermissionController) {
        notificationPermissionController.refresh { notificationPermissionState = it }
    }

    /** The tab currently shown on the terminal screen (SSH session or SFTP; used to toggle between multiple sessions on the same host). */
    var currentTab by remember { mutableStateOf<SessionTab?>(null) }

    /** Toggle between tabs (tab ID stack): When returning, pop the stack to go back to the previous tab; only when the stack is empty will it return to the home page. */
    val tabHistory = remember { mutableStateListOf<String>() }

    /** Sessions that wait for the connection to complete before redirecting (the card avatar spins while the connection is being established). */
    var pendingNavigate by remember { mutableStateOf<TerminalController?>(null) }

    /** Terminal + Menu “Favorites” dialog: host + list of favorite paths (null = disabled). */
    var favoritesDialog by remember { mutableStateOf<Pair<Host, List<String>>?>(null) }

    /** Screen session entries (remote screen streaming). */
    val screenSessions = remember { mutableStateListOf<ScreenSessionEntry>() }

    /** Limit each host to one task to reconnect the screen to prevent newly established channels from being closed after an old task is completed. */
    val screenReconnectJobs = remember { mutableMapOf<String, Job>() }

    // When the user taps “Screen” on the host card, automatically expand to fullscreen once the first frame arrives; normal terminal-menu launches are unaffected.
    var screenFullscreenRequestHostId by remember { mutableStateOf<String?>(null) }

    /** SFTP: Select host overlay / Current session / Authentication and host key dialog. */
    var sftpPickerVisible by remember { mutableStateOf(false) }
    var sftpAuth by remember { mutableStateOf<AuthPromptRequest?>(null) }
    var sftpHostKey by remember { mutableStateOf<HostKeyRequest?>(null) }

    // The Agent control connection persists across pages; dialogs must also be owned by AppRoot and cannot capture the state of a destroyed screen.
    var agentAuth by remember { mutableStateOf<Pair<String, AuthPromptRequest>?>(null) }
    var agentHostKey by remember { mutableStateOf<Pair<String, HostKeyRequest>?>(null) }
    val agentControllers = remember { mutableStateMapOf<String, AgentBridgeController>() }
    val agentControllerPool =
        remember {
            IdleResourcePool<String, String, AgentBridgeController>(
                scope = scope,
                idleTimeoutMillis = AGENT_IDLE_TIMEOUT_MS,
                closeResource = { controller ->
                    if (agentAuth?.first == controller.host.id) {
                        agentAuth?.second?.deferred?.complete(null)
                        agentAuth = null
                    }
                    if (agentHostKey?.first == controller.host.id) {
                        agentHostKey?.second?.deferred?.complete(false)
                        agentHostKey = null
                    }
                    if (agentControllers[controller.host.id] === controller) {
                        agentControllers.remove(controller.host.id)
                    }
                    controller.close()
                    TermLog.i("agent") { "idle connection closed ${controller.host.name}" }
                },
                // Idle control connections can be recycled after leaving the screen, but while remote tasks are running or waiting for approval,
                // You must continue to use the event listener; otherwise, users will not receive completion or approval notifications.
                canCloseResource = { controller ->
                    !controller.hasActiveWork
                },
            )
        }
    val snackbarHostState = remember { SnackbarHostState() }

    fun acquireAgentController(host: Host): AgentBridgeController =
        agentControllerPool.acquire(
            key = host.id,
            signature = sessionManager.signatureFor(host),
        ) {
            val callbacks =
                object : SshCallbacks {
                    override suspend fun onOutput(data: ByteArray) {}

                    override suspend fun onStderr(data: ByteArray) {}

                    override fun onExitStatus(status: Int) {}

                    override fun onClosed(reason: String?) {}

                    override suspend fun onPrompt(prompt: AuthPrompt): List<String>? {
                        agentAuth?.second?.deferred?.complete(null)
                        val request = AuthPromptRequest(prompt)
                        agentAuth = host.id to request
                        return awaitAuthPromptAnswer(request.deferred).also {
                            if (agentAuth?.second === request) agentAuth = null
                        }
                    }

                    override fun verifyHostKey(hostKey: HostKeyInfo): Boolean {
                        val known =
                            repository.getHost(host.id)?.knownHostFingerprint
                                ?: host.knownHostFingerprint
                        if (known == hostKey.fingerprintSha256) return true
                        if (known == null && !repository.loadSettings().verifyHostKeyOnFirstUse) return true
                        agentHostKey?.second?.deferred?.complete(false)
                        val request = HostKeyRequest(hostKey, known != null, known)
                        agentHostKey = host.id to request
                        val accepted = awaitHostKeyPromptAnswer(request.deferred)
                        if (agentHostKey?.second === request) agentHostKey = null
                        if (accepted) repository.touchConnected(host.id, hostKey.fingerprintSha256)
                        return accepted
                    }
                }
            AgentBridgeController(host, repository, callbacks, scope).also { controller ->
                controller.onApprovalRequested = { approval ->
                    val strings = currentStrings.value.nativeAgents
                    val title = approval.title.ifBlank { strings.approvalRequired }
                    NotificationCenter.post(
                        NotificationEvent.AGENT_TASK,
                        "Macodes",
                        strings.approvalNotification(host.name, title),
                        id = "agent-approval:${host.id}".hashCode(),
                    )
                }
                controller.onTaskSettled = { session ->
                    val strings = currentStrings.value.nativeAgents
                    val title = session?.title?.ifBlank { strings.title } ?: strings.title
                    NotificationCenter.post(
                        NotificationEvent.AGENT_TASK,
                        "Macodes",
                        strings.taskCompletedNotification(host.name, title),
                        id = "agent-complete:${host.id}".hashCode(),
                    )
                }
                agentControllers[host.id] = controller
            }
        }

    lateinit var applyScreenStreamConfig: (Host, Int, String, Boolean) -> Unit

    /**
     * Establish an SFTP session (authentication/host key confirmation via a global dialog); call the [onEstablished] callback upon success.
     * Reusable for initial connection and reconnection after a disconnection; failure is handled by the caller (initial connection = Snackbar, reconnection = keep banner).
     * Defined before `connectSftp`: Local functions cannot be forward-referenced.
     * suspend: `createSftpSession` is a blocking operation; the I/O thread must be switched (the caller is in the `Main` scope,
     * Otherwise, the UI freezes for a few seconds after tapping the "Reconnect" button—"No response after tapping").
     */
    suspend fun establishSftp(
        host: Host,
        onEstablished: (SftpSession, Any) -> Unit,
    ) {
        // Identifier for this connection: onClosed. Use this to distinguish between “unexpected disconnection of this connection” and
        // “reconnecting / switching: old connection actively closed” (close synchronously triggers the old callback,
        // without comparing generations, the disconnect banner would be wrongly applied to the just-reconnected session)
        val connectionToken = Any()
        val (pw, key) = resolveCredentials(host)
        val callbacks =
            object : SshCallbacks {
                override suspend fun onOutput(data: ByteArray) {}

                override suspend fun onStderr(data: ByteArray) {}

                override fun onExitStatus(status: Int) {}

                override fun onClosed(reason: String?) {
                    // SFTP disconnect pushed proactively: immediately set disconnected (red banner + reconnect entry).
                    // Generation comparison: callbacks of old connections closed during reconnect/replace/removal still fire,
                    // However, if an entry has been updated to a new version (or removed) → ignore it; do not mislabel it.
                    val entry =
                        sessionManager.sftpSessions
                            .firstOrNull { it.host.id == host.id && it.session != null }
                    if (entry == null || entry.connectionToken !== connectionToken) return
                    scope.launch {
                        TermLog.w("sftp") { "sftp closed ${host.name}: ${reason ?: "unknown"}" }
                        entry.uiState.disconnected = true
                    }
                }

                override suspend fun onPrompt(prompt: AuthPrompt): List<String>? {
                    val req = AuthPromptRequest(prompt)
                    sftpAuth = req
                    return req.deferred.await()
                }

                override fun verifyHostKey(hostKey: HostKeyInfo): Boolean {
                    // Same origin as the terminal connection: Automatically passes if the fingerprint matches; no duplicate dialogs.
                    val known =
                        repository.getHost(host.id)?.knownHostFingerprint
                            ?: host.knownHostFingerprint
                    if (known != null) {
                        if (known == hostKey.fingerprintSha256) return true
                        // Fingerprint changes: A dialog prompts the user to verify the new and old fingerprints
                        val req = HostKeyRequest(hostKey, changed = true, previousFingerprint = known)
                        sftpHostKey = req
                        val accepted = awaitHostKeyPromptAnswer(req.deferred)
                        if (accepted) repository.touchConnected(host.id, hostKey.fingerprintSha256)
                        return accepted
                    }
                    // First connection: Set the settings to skip the initial confirmation and trust immediately
                    if (!repository.loadSettings().verifyHostKeyOnFirstUse) return true
                    val req = HostKeyRequest(hostKey)
                    sftpHostKey = req
                    val accepted = awaitHostKeyPromptAnswer(req.deferred)
                    if (accepted) repository.touchConnected(host.id, hostKey.fingerprintSha256)
                    return accepted
                }
            }
        val conn =
            SshConnection(
                host = host.hostname,
                port = host.port,
                username = host.username,
                password = pw,
                privateKeyPem = key,
                keepAliveSeconds = repository.loadSettings().keepaliveSeconds,
            )
        val session = withContext(ioDispatcher()) { createSftpSession(conn, callbacks) }
        TermLog.i("sftp") { "connected ${host.name} ${host.hostname}:${host.port}" }
        onEstablished(session, connectionToken)
    }

    /**
     * Establish a screen streaming session (authentication and host key confirmation are handled via a global dialog, similar to the SFTP mode).
     * Upon success, register the entry and switch to the screen tab; in case of failure, the caller should display a tooltip.
     */
    suspend fun establishScreen(
        host: Host,
        uiState: ScreenUiState = ScreenUiState(),
        refreshPermissionsBeforeStart: Boolean = false,
        onEstablished: (ScreenSession, ScreenUiState) -> Unit,
    ) {
        val (pw, key) = resolveCredentials(host)
        val callbacks =
            object : SshCallbacks {
                override suspend fun onOutput(data: ByteArray) {}

                override suspend fun onStderr(data: ByteArray) {}

                override fun onExitStatus(status: Int) {}

                override fun onClosed(reason: String?) {}

                override suspend fun onPrompt(prompt: AuthPrompt): List<String>? {
                    val req = AuthPromptRequest(prompt)
                    sftpAuth = req
                    return req.deferred.await()
                }

                override fun verifyHostKey(hostKey: HostKeyInfo): Boolean {
                    val known =
                        repository.getHost(host.id)?.knownHostFingerprint
                            ?: host.knownHostFingerprint
                    if (known != null) {
                        if (known == hostKey.fingerprintSha256) return true
                        val req = HostKeyRequest(hostKey, changed = true, previousFingerprint = known)
                        sftpHostKey = req
                        val accepted = awaitHostKeyPromptAnswer(req.deferred)
                        if (accepted) repository.touchConnected(host.id, hostKey.fingerprintSha256)
                        return accepted
                    }
                    if (!repository.loadSettings().verifyHostKeyOnFirstUse) return true
                    val req = HostKeyRequest(hostKey)
                    sftpHostKey = req
                    val accepted = awaitHostKeyPromptAnswer(req.deferred)
                    if (accepted) repository.touchConnected(host.id, hostKey.fingerprintSha256)
                    return accepted
                }
            }
        val conn =
            SshConnection(
                host = host.hostname,
                port = host.port,
                username = host.username,
                password = pw,
                privateKeyPem = key,
                connectTimeoutMillis = 10_000,
                keepAliveSeconds = 0,
            )
        val session =
            ScreenSession(
                conn,
                callbacks,
                scope,
                uiState,
                messages = currentStrings.value.screen.toSessionMessages(),
                refreshPermissionsBeforeStart = refreshPermissionsBeforeStart,
                // Automatic reconnect on stream loss: only one task per host, and re-verify the session generation before replacing.
                // Otherwise, concurrency will cause old tasks to take turns closing the newly created channel, creating a cycle of disconnections at fixed intervals.
                onStreamLost = {
                    scope.launch schedule@{
                        // Installing or upgrading Relay will automatically restart remote services; it is expected that old video channels will be disconnected.
                        // Lifecycle; the install-complete callback owns the single rebuild — do not also schedule a concurrent
                        // 3-second auto-reconnect that would close the brand-new channel.
                        if (uiState.installing) {
                            TermLog.i("screen") { "skip reconnect while installing ${host.name}" }
                            return@schedule
                        }
                        if (screenReconnectJobs[host.id]?.isActive == true) {
                            TermLog.i("screen") { "skip duplicate reconnect ${host.name}" }
                            return@schedule
                        }
                        val sourceUiState = uiState
                        val reconnectJob =
                            scope.launch reconnect@{
                                delay(3_000)
                                val current = screenSessions.firstOrNull { it.host.id == host.id }
                                if (current?.uiState !== sourceUiState) return@reconnect

                                val now = Clock.System.now().toEpochMilliseconds()
                                val unstable = isUnstableScreenStream(now, sourceUiState.videoReadyAtMillis)
                                val attempt = if (unstable) sourceUiState.streamReconnectAttempts + 1 else 1
                                if (attempt > MAX_SCREEN_RECONNECT_ATTEMPTS) {
                                    TermLog.w("screen") {
                                        "auto-reconnect stopped ${host.name} after $MAX_SCREEN_RECONNECT_ATTEMPTS attempts"
                                    }
                                    return@reconnect
                                }

                                // If the original image or high-resolution version cuts off immediately after the frame appears, roll back step by step; the actual width of the original image must not exceed
                                // when actual width ≤ 1920, do not upscale in reverse. Keep the user's quality setting on occasional drops after a stable run.
                                val readyWidth =
                                    sourceUiState.player
                                        ?.videoDims
                                        ?.value
                                        ?.first ?: 0
                                val currentQuality = sourceUiState.streamQuality
                                val fallbackQuality = fallbackScreenQuality(currentQuality, readyWidth, unstable)
                                if (fallbackQuality != currentQuality) {
                                    val fallbackScale = ScreenSession.scaleForQuality(fallbackQuality)
                                    current.session?.setStreamConfig(sourceUiState.streamFps, fallbackScale)
                                    sourceUiState.streamQuality = fallbackQuality
                                    TermLog.w("screen") {
                                        "unstable stream fallback ${host.name}: quality=$currentQuality->$fallbackQuality"
                                    }
                                }

                                val owner = current.ownerSessionId
                                TermLog.i("screen") { "auto-reconnect ${host.name} attempt=$attempt" }
                                try {
                                    establishScreen(host) { newSession, newUi ->
                                        val latest = screenSessions.firstOrNull { it.host.id == host.id }
                                        if (latest?.uiState !== sourceUiState) {
                                            newSession.close()
                                            return@establishScreen
                                        }
                                        latest.session?.close()
                                        screenSessions.removeAll { it.host.id == host.id }
                                        newUi.streamReconnectAttempts = attempt
                                        newUi.streamFps = sourceUiState.streamFps
                                        newUi.streamQuality = sourceUiState.streamQuality
                                        newUi.preferredStreamFps = sourceUiState.preferredStreamFps
                                        newUi.adaptiveFpsChangedAtMillis = sourceUiState.adaptiveFpsChangedAtMillis
                                        screenSessions.add(
                                            ScreenSessionEntry(host, owner, newSession, newUi, current.createdAt),
                                        )
                                    }
                                } catch (e: Exception) {
                                    TermLog.w("screen") { "auto-reconnect failed ${host.name}: $e" }
                                }
                            }
                        screenReconnectJobs[host.id] = reconnectJob
                    }
                },
                onAdaptiveFpsRequested = { fps ->
                    applyScreenStreamConfig(
                        host,
                        fps,
                        ScreenSession.scaleForQuality(uiState.streamQuality),
                        false,
                    )
                },
            )
        withContext(ioDispatcher()) { session.start() }
        onEstablished(session, uiState)
    }

    // Terminal + Menu “Screen”: Create a streaming session, **stay in the current tab**—a floating window appears on the terminal screen,
    // Click the fullscreen button to expand the floating window on the current page (without switching tabs).
    val openScreen: (Host) -> Unit = { host ->
        screenReconnectJobs.remove(host.id)?.cancel()
        val ownerId = (currentTab as? SessionTab.Terminal)?.controller?.sessionId ?: ""
        // Register placeholder entries upon connection: so that when a screen is opened for the first time on a different host, the host’s
        // Fullscreen connection feedback—there’s no need to wait until SSH and streaming are set up before the terminal button and system status bar are hidden.
        val pendingUiState = ScreenUiState()
        screenSessions.firstOrNull { it.host.id == host.id }?.session?.close()
        screenSessions.removeAll { it.host.id == host.id }
        screenSessions.add(ScreenSessionEntry(host, ownerId, null, pendingUiState))
        scope.launch {
            try {
                establishScreen(host, pendingUiState) { session, uiState ->
                    // If an update request was already initiated on the same host, the old request must not overwrite the new entry once it completes.
                    val pending = screenSessions.firstOrNull { it.host.id == host.id }
                    if (pending?.uiState !== pendingUiState) {
                        session.close()
                        return@establishScreen
                    }
                    screenSessions.removeAll { it.host.id == host.id }
                    screenSessions.add(ScreenSessionEntry(host, ownerId, session, uiState, pending.createdAt))
                }
            } catch (e: Exception) {
                screenSessions.removeAll {
                    it.host.id == host.id && it.uiState === pendingUiState
                }
                if (screenFullscreenRequestHostId == host.id) screenFullscreenRequestHostId = null
                snackbarHostState.showSnackbar(appStrings.screen.connecting + " " + (e.message ?: ""))
            }
        }
    }

    // Screen Streaming Service Installation (Wizard Card Button): Reuse an authenticated session to run the installation script (streaming logs),
    // After installation, rebuild the streaming session to reconnect. This follows the same mode as the herdr installation guide.
    val installScreenService: (Host, String?) -> Unit = { host, sudoPassword ->
        screenReconnectJobs.remove(host.id)?.cancel()
        val entry = screenSessions.firstOrNull { it.host.id == host.id && it.session != null }
        if (entry != null) {
            entry.session?.installService(
                sudoPassword = sudoPassword,
                onLog = { log -> entry.uiState.installLog = log },
                onComplete = { ok ->
                    if (ok) {
                        screenReconnectJobs.remove(host.id)?.cancel()
                        // After installation, rebuild the streaming session (reconnect the read stream).
                        scope.launch {
                            try {
                                establishScreen(host) { session, uiState ->
                                    val latest = screenSessions.firstOrNull { it.host.id == host.id }
                                    if (latest?.uiState !== entry.uiState) {
                                        session.close()
                                        return@establishScreen
                                    }
                                    latest.session?.close()
                                    screenSessions.removeAll { it.host.id == host.id }
                                    uiState.preferredStreamFps = entry.uiState.preferredStreamFps
                                    uiState.adaptiveFpsChangedAtMillis = entry.uiState.adaptiveFpsChangedAtMillis
                                    screenSessions.add(
                                        ScreenSessionEntry(host, entry.ownerSessionId, session, uiState, entry.createdAt),
                                    )
                                }
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar(appStrings.screen.connecting + " " + (e.message ?: ""))
                            }
                        }
                    } else {
                        entry.uiState.installing = false
                        scope.launch {
                            snackbarHostState.showSnackbar(appStrings.screen.installService + " 失败，查看日志")
                        }
                    }
                },
            )
        }
    }

    // Screen Reconnect (Fullscreen In Place / Reconnect Button): Rebuild the streaming session (close the old session to prevent leaks)
    val reconnectScreenForHost: (Host) -> Unit = { host ->
        screenReconnectJobs.remove(host.id)?.cancel()
        val existingEntry = screenSessions.firstOrNull { it.host.id == host.id }
        val ownerId = existingEntry?.ownerSessionId ?: ""
        val refreshPermissions = existingEntry?.uiState?.let { it.recordingPermissionMissing || it.controlPermissionMissing } == true
        scope.launch {
            try {
                existingEntry?.session?.close()
                establishScreen(host, refreshPermissionsBeforeStart = refreshPermissions) { session, uiState ->
                    screenSessions.firstOrNull { it.host.id == host.id }?.session?.close()
                    screenSessions.removeAll { it.host.id == host.id }
                    uiState.preferredStreamFps = existingEntry?.uiState?.preferredStreamFps ?: 0
                    uiState.adaptiveFpsChangedAtMillis = existingEntry?.uiState?.adaptiveFpsChangedAtMillis ?: 0
                    screenSessions.add(
                        ScreenSessionEntry(
                            host,
                            ownerId,
                            session,
                            uiState,
                            existingEntry?.createdAt ?: Clock.System.now().toEpochMilliseconds(),
                        ),
                    )
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(appStrings.screen.connecting + " " + (e.message ?: ""))
            }
        }
    }

    // Close the screen session and remove the entry (fullscreen ✕ / Close button on the Agent page)
    val closeScreenForHost: (Host) -> Unit = { host ->
        screenReconnectJobs.remove(host.id)?.cancel()
        screenSessions.firstOrNull { it.host.id == host.id }?.session?.close()
        screenSessions.removeAll { it.host.id == host.id }
    }

    // Frame rate/image quality toggle: Write the remote relay configuration via SSH → The changes take effect after rebuilding the session.
    // When `userInitiated=false`, retain the user limit; it can be gradually resumed in subsequent stability windows.
    // ⚠️ The quality tier must be set on the new uiState after rebuild: Rebuild (establishScreen)
    // It will create a new ScreenUiState (default state); if set on an existing object, it will be replaced,
    // The number in the upper-right corner never changes (user feedback)
    applyScreenStreamConfig = { host, fps, scale, userInitiated ->
        screenReconnectJobs.remove(host.id)?.cancel()
        lateinit var configJob: Job
        configJob =
            scope.launch {
                // When switching frame rate/quality rapidly in the menu, only the last choice is applied. Previously each tap started
                // an independent SSH + direct-tcpip; a slow request, though superseded, can still arrive late and kick out the
                // The current screen repeatedly disconnects for a fixed number of seconds.
                delay(250)
                val entry = screenSessions.firstOrNull { it.host.id == host.id }
                // The old entry `uiState` has also been synced: Automatic reconnection upon stream loss (`onStreamLost`)
                // When rebuilding with the old `uiState`, the new settings are retained and not overwritten by `30` (user feedback:
                // The frame rate is still showing 30 in the upper-right corner after adjustment—(automatic reconnection and rebuild race condition)
                entry?.uiState?.streamFps = fps
                entry?.uiState?.streamQuality = ScreenSession.qualityIndexFor(scale)
                if (userInitiated) entry?.uiState?.preferredStreamFps = fps
                entry?.session?.setStreamConfig(fps, scale)
                establishScreen(host) { session, uiState ->
                    if (screenReconnectJobs[host.id] !== configJob) {
                        session.close()
                        return@establishScreen
                    }
                    screenSessions.firstOrNull { it.host.id == host.id }?.session?.close()
                    screenSessions.removeAll { it.host.id == host.id }
                    uiState.streamFps = fps
                    uiState.streamQuality = ScreenSession.qualityIndexFor(scale)
                    uiState.preferredStreamFps =
                        if (userInitiated) {
                            fps
                        } else {
                            entry?.uiState?.preferredStreamFps?.takeIf { it > 0 } ?: fps
                        }
                    uiState.adaptiveFpsChangedAtMillis =
                        entry?.uiState?.adaptiveFpsChangedAtMillis ?: 0
                    screenSessions.add(
                        ScreenSessionEntry(
                            host,
                            entry?.ownerSessionId ?: "",
                            session,
                            uiState,
                            entry?.createdAt ?: Clock.System.now().toEpochMilliseconds(),
                        ),
                    )
                }
            }
        screenReconnectJobs[host.id] = configJob
    }
    val streamConfigChange: (Host, Int, String) -> Unit = { host, fps, scale ->
        applyScreenStreamConfig(host, fps, scale, true)
    }

    // After the host is selected in the overlay layer: Establish an SFTP session (authentication/host key dialogs use the global `sftpAuth` and `sftpHostKey`).
    // If [initialPath] is not empty, open the file and navigate directly to that directory (Terminal “File Management” menu: Current Working Directory).
    val connectSftp: (Host, String?) -> Unit = { host, initialPath ->
        sftpPickerVisible = false
        scope.launch {
            try {
                establishSftp(host) { session, token ->
                    val entry = sessionManager.addSftp(host, session, token)
                    // Persisted favorites restored into the session (terminal + menu bookmarks read the same copy)
                    repository.loadFavorites(host.id).forEach { entry.uiState.favorites.add(it) }
                    if (!initialPath.isNullOrBlank()) {
                        entry.uiState.path = initialPath
                    }
                    // Shares the same `uiState` with `entry`: changes in the navigation path are reflected in persistence (saved in the background).
                    // From the terminal screen: push the current terminal tab onto the back history (Back returns to the terminal,
                    // (rather than returning directly to the home page—same back path as toggling via the tab bar)
                    (currentTab as? SessionTab.Terminal)?.let { tabHistory.add(it.id) }
                    currentTab = SessionTab.Sftp(host, session, entry.uiState)
                    navigate(Screen.Terminal)
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(appStrings.sftpConnectFailed(e.message ?: ""))
            }
        }
    }

    /** Host Capability Entry Point: Prioritize reuse on the same host and with the same launch type; otherwise, create a separate session. */
    fun openHostTerminal(
        host: Host,
        launchMode: TerminalLaunchMode,
    ) {
        val signature = sessionManager.signatureFor(host)
        val existing =
            sessionManager.sessions.lastOrNull {
                it.host.id == host.id && it.launchMode == launchMode && it.credentialKey == signature
            }
        if (existing != null) {
            currentTab = SessionTab.Terminal(existing)
            navigate(Screen.Terminal)
            return
        }
        pendingNavigate =
            sessionManager.open(host, settings.autoReconnect, launchMode) {
                hosts = repository.listHosts()
            }
    }

    /** The host screen entry point requires a terminal screen host; the screen SSH itself still uses an independent connection. */
    val openScreenFromHost: (Host) -> Unit = { host ->
        val signature = sessionManager.signatureFor(host)
        val owner =
            sessionManager.sessions.lastOrNull {
                it.host.id == host.id && it.credentialKey == signature
            } ?: sessionManager.open(host, settings.autoReconnect, TerminalLaunchMode.SHELL) {
                hosts = repository.listHosts()
            }
        currentTab = SessionTab.Terminal(owner)
        screenFullscreenRequestHostId = host.id
        navigate(Screen.Terminal)
        openScreen(host)
    }

    /** Re-enter an existing screen session: Reuse the streaming, only update the terminal screen host, and expand to fullscreen. */
    val showScreenSession: (ScreenSessionEntry) -> Unit = { entry ->
        val signature = sessionManager.signatureFor(entry.host)
        val owner =
            sessionManager.sessions.firstOrNull { it.sessionId == entry.ownerSessionId }
                ?: sessionManager.sessions.lastOrNull {
                    it.host.id == entry.host.id && it.credentialKey == signature
                } ?: sessionManager.open(
                entry.host,
                settings.autoReconnect,
                TerminalLaunchMode.SHELL,
            ) {
                hosts = repository.listHosts()
            }
        if (entry.ownerSessionId != owner.sessionId) {
            val index = screenSessions.indexOfFirst { it.uiState === entry.uiState }
            if (index >= 0) screenSessions[index] = entry.copy(ownerSessionId = owner.sessionId)
        }
        currentTab = SessionTab.Terminal(owner)
        screenFullscreenRequestHostId = entry.host.id
        navigate(Screen.Terminal)
    }

    LaunchedEffect(pendingNavigate) {
        val target = pendingNavigate ?: return@LaunchedEffect
        // TUI sessions (herdr workspace / launch commands) are not pre-connected: the list page has no terminal canvas, so the PTY can
        // only start at 80×24; herdr/tmux then lay out at the wrong size, and a later resize re-flows everything with visible jumps.
        // Go straight to the terminal screen, measure the canvas to its real size and only then connect, so the layout is right from the first frame.
        // (Pure-shell pre-connect doesn't have this issue — `resize` just reprints the prompt on a new line.)
        if (target.launchMode == TerminalLaunchMode.HERDR || target.host.startupCommand.isNotBlank()) {
            currentTab = SessionTab.Terminal(target)
            navigate(Screen.Terminal)
            pendingNavigate = null
            return@LaunchedEffect
        }
        // The list page does not have a terminal canvas: create the connection using the default size first (TerminalView will resize after the redirect).
        // (to the actual canvas size), otherwise the session will remain in IDLE and will never be able to establish a connection
        if (target.status == ConnStatus.IDLE) {
            target.connect(80, 24)
        }
        // Wait for connection results: If success, redirect to the terminal screen; if failure, remain in the list (avatar stops spinning).
        while (target.status == ConnStatus.CONNECTING || target.status == ConnStatus.AUTH) {
            delay(200)
        }
        pendingNavigate = null
        if (target.status == ConnStatus.CONNECTED) {
            currentTab = SessionTab.Terminal(target)
            navigate(Screen.Terminal)
        } else if (target.status == ConnStatus.ERROR) {
            // Connection failure (IP unreachable / authentication failure, etc.): Keep in the list and display the reason as a tooltip
            snackbarHostState.showSnackbar(target.errorMessage ?: appStrings.hostsConnectFailed)
        }
    }
    // Resume the session list from the last run (only once; if the process dies, the connection will be lost; resuming to an unconnected state allows for reconnection)
    var sessionsRestored by remember { mutableStateOf(false) }
    if (!sessionsRestored) {
        sessionManager.restoreRecent(hosts, settings.autoReconnect) {
            hosts = repository.listHosts()
        }
        sessionsRestored = true
    }

    // Android is kept alive by a foreground service and a wakelock; network changes are observed so dropped sessions reconnect.
    val disposeNetwork =
        observeNetworkChange { kind ->
            // Network events: SSH first detects changes in the default network before deciding whether to reconnect; mosh relies on UDP roaming for self-healing.
            // (Implementation of the NetworkChangeKind comment: mosh only performs automatic reconnection when the client exits with an exception.)
            sessionManager.sessions.forEach { it.onNetworkChanged(kind) }
        }
    DisposableEffect(Unit) {
        // Notification Center: Perform injection of a settings reader; provide foreground and background states to filter background event notifications;
        // "Reconnect" action → Find and reconnect to the session on this host (if there is no active session, open the host page)
        NotificationCenter.settingsProvider = { repository.loadSettings() }
        NotificationCenter.foreground = true
        NotificationCenter.onReconnectRequest = { hostId ->
            TermLog.i("notify") { "reconnect action hostId=$hostId" }
            scope.launch {
                sessionManager.sessions
                    .firstOrNull { it.host.id == hostId }
                    ?.let { controller ->
                        if (controller.status == ConnStatus.CLOSED ||
                            controller.status == ConnStatus.ERROR
                        ) {
                            controller.reconnect()
                        }
                    }
            }
        }
        val dispose =
            observeAppLifecycle { foreground ->
                TermLog.d("life") { "foreground=$foreground" }
                NotificationCenter.foreground = foreground
                if (foreground) {
                    notificationPermissionController.refresh { notificationPermissionState = it }
                    backgroundProtection = backgroundProtectionState()
                    // First read the recovery strategy, then rebuild the service registration: Android’s VERIFY is based on “during background operation”
                    // "The service was once suspended"; after resuming registration and restoration, `isActive` will change, but the old socket still needs to be verified.
                    val recovery = SessionKeepAlive.foregroundSshRecovery()
                    if (!SessionKeepAlive.isActive()) {
                        sessionManager.restoreKeepAliveRegistrations()
                    }
                    sessionManager.reconnectDroppedSessions(recovery)
                } else {
                    sessionManager.noteBackgrounded()
                    // Save the latest SFTP browsing path when exiting to the background: Resume at the previous directory after closing and reopening the app.
                    sessionManager.persistNow()
                }
            }
        onDispose {
            agentControllerPool.closeAll()
            dispose()
            disposeNetwork()
        }
    }

    // Global back stack: on the first-level screen, pop back to the actual source; on the second-level screen, back to settings; on the home screen, if not on the "Host" tab, switch to the "Host" tab.
    // If a subpage fails to intercept Back, the root layer pops only one level; the target is no longer hardcoded to home.
    var homeTab by remember { mutableStateOf(HomeTab.HOSTS) }
    var settingsSubPage by remember { mutableStateOf<SettingsSubPage?>(null) }
    val homeHasBackTarget = screen == Screen.Home && (settingsSubPage != null || homeTab != HomeTab.HOSTS)
    PlatformBackHandler(enabled = navigation.canPop || homeHasBackTarget) {
        when {
            navigation.canPop -> navigateBack()
            settingsSubPage != null -> settingsSubPage = null
            homeTab != HomeTab.HOSTS -> homeTab = HomeTab.HOSTS
        }
    }

    fun refreshHosts() {
        hosts = repository.listHosts()
    }

    val terminalTheme = TerminalThemes.ALL.getOrElse(settings.terminalThemeIndex) { TerminalThemes.ALL[0] }
    val agentSessionItems =
        agentControllers.values
            .filter { it.currentSession != null || it.busy || it.pendingApprovals.isNotEmpty() }
            .map { HostSessionItem.Agent(it) }
    val activeSessionCount =
        sessionManager.sessions.count { isActiveStatus(it.status) } +
            sessionManager.sftpSessions.count { it.session != null } +
            screenSessions.size +
            agentSessionItems.count { it.isActive }
    val hasConnectedSshSession =
        sessionManager.sessions.any { it.status == ConnStatus.CONNECTED && it.moshSession == null }

    LaunchedEffect(
        hasConnectedSshSession,
        settings.backgroundProtectionPrompted,
        backgroundProtection,
        backgroundVendor,
    ) {
        if (
            shouldShowBackgroundProtectionGuide(
                state = backgroundProtection,
                vendor = backgroundVendor,
                alreadyPrompted = settings.backgroundProtectionPrompted,
                hasConnectedSshSession = hasConnectedSshSession,
            )
        ) {
            repository.updateSettings { it.copy(backgroundProtectionPrompted = true) }
            showBackgroundProtectionGuide = true
        }
    }

    CompositionLocalProvider(
        LocalAppStrings provides appStrings,
        LocalTerminalFont provides TerminalFont.byId(settings.terminalFontId),
    ) {
        MacodesTheme(settings.theme) {
            // The status bar icon color depends solely on the actual brightness of the current screen’s background:
            // - Terminal tab → Adjust brightness based on terminal theme background brightness
            // - Other screens → Adjust brightness based on app theme
            val pageDark =
                if (screen is Screen.Terminal && currentTab is SessionTab.Terminal) {
                    terminalTheme.background().luminance() < 0.5f
                } else {
                    settings.theme != ThemeMode.LIGHT
                }
            PlatformStatusBarIcons(lightIcons = pageDark)
            Box(Modifier.fillMaxSize()) {
                // Global rendering of session-level dialogs: Authentication and host key confirmation dialogs should appear even while waiting for a connection on the home page,
                // There is no need to open the terminal screen first (otherwise, the connection will get stuck spinning without displaying the authorization request).
                sessionManager.sessions.forEach { controller ->
                    controller.authPrompt?.let { prompt ->
                        AuthPromptDialog(prompt.prompt) { answers ->
                            controller.respondToPrompt(answers)
                        }
                    }
                    controller.hostKeyPrompt?.let { hk ->
                        HostKeyDialog(
                            key = hk.key,
                            changed = hk.changed,
                            previousFingerprint = hk.previousFingerprint,
                        ) { accept ->
                            controller.respondToHostKey(accept)
                        }
                    }
                }
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        // The terminal features a continuously refreshing canvas/native video display that undergoes only subtle changes to avoid disruption during swiping.
                        // Two Surface instances move simultaneously; standard-level screens swipe in pairs according to the push/pop direction.
                        if (initialState is Screen.Terminal || targetState is Screen.Terminal) {
                            fadeIn(tween(140)).togetherWith(fadeOut(tween(120)))
                        } else if (navigation.direction == NavigationDirection.BACKWARD) {
                            (slideInHorizontally(tween(220)) { -it / 5 } + fadeIn(tween(180)))
                                .togetherWith(
                                    slideOutHorizontally(tween(200)) { it / 4 } + fadeOut(tween(160)),
                                )
                        } else {
                            (slideInHorizontally(tween(220)) { it / 4 } + fadeIn(tween(180)))
                                .togetherWith(
                                    slideOutHorizontally(tween(200)) { -it / 5 } + fadeOut(tween(160)),
                                )
                        }
                    },
                    contentKey = { it },
                    label = "page-transition",
                ) { targetScreen ->
                    when (val s = targetScreen) {
                        Screen.Home -> {
                            Scaffold(
                                // Each screen’s header should automatically adjust to avoid the status bar, and the bottom NavigationBar should automatically adjust to avoid the navigation bar,
                                // Do not apply it repeatedly to outer elements (otherwise, the status bar height above the title will double).
                                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                                bottomBar = {
                                    // In fullscreen mode, set subpages to open: the bottom tab is hidden (subpages are closed uniformly via the back button).
                                    if (settingsSubPage == null) {
                                        // Custom-drawn minimalist bottom bar: no capsule indicators; selected items = theme green; monospaced font for small tags
                                        val mono = monospaceFontFamily()
                                        Column {
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                            Row(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .background(MaterialTheme.colorScheme.surface)
                                                    .navigationBarsPadding(),
                                            ) {
                                                HomeTabItem(
                                                    appStrings.appTabHosts,
                                                    Icons.Default.Dns,
                                                    homeTab == HomeTab.HOSTS,
                                                    mono,
                                                    Modifier.weight(1f),
                                                ) {
                                                    homeTab = HomeTab.HOSTS
                                                }
                                                HomeTabItem(
                                                    appStrings.appTabConnections,
                                                    Icons.Default.Cable,
                                                    homeTab == HomeTab.CONNECTIONS,
                                                    mono,
                                                    Modifier.weight(1f),
                                                    badge = activeSessionCount,
                                                    badgeAlert =
                                                        agentSessionItems.any {
                                                            it.controller.pendingApprovals.isNotEmpty()
                                                        },
                                                ) {
                                                    homeTab = HomeTab.CONNECTIONS
                                                }
                                                HomeTabItem(
                                                    appStrings.appTabSettings,
                                                    Icons.Default.Settings,
                                                    homeTab == HomeTab.SETTINGS,
                                                    mono,
                                                    Modifier.weight(1f),
                                                ) {
                                                    homeTab = HomeTab.SETTINGS
                                                }
                                            }
                                        }
                                    }
                                },
                            ) { padding ->
                                Box(Modifier.padding(padding)) {
                                    Crossfade(
                                        targetState = homeTab,
                                        animationSpec = tween(160),
                                        label = "home-tab-transition",
                                    ) { targetHomeTab ->
                                        when (targetHomeTab) {
                                            HomeTab.HOSTS ->
                                                HostListScreen(
                                                    hosts = hosts,
                                                    hostSessions =
                                                        (
                                                            sessionManager.sessions.map { HostSessionItem.Terminal(it) } +
                                                                sessionManager.sftpSessions.map {
                                                                    HostSessionItem.Sftp(
                                                                        it.host,
                                                                        it.session,
                                                                        it.createdAt,
                                                                    )
                                                                } +
                                                                screenSessions.map { HostSessionItem.Screen(it) } +
                                                                agentSessionItems
                                                        ).groupBy { it.hostId },
                                                    onAdd = { navigate(Screen.Edit(null)) },
                                                    onEdit = { navigate(Screen.Edit(it.id)) },
                                                    onConnect = { host ->
                                                        openHostTerminal(host, TerminalLaunchMode.SHELL)
                                                    },
                                                    onOpenHerdr = { host ->
                                                        openHostTerminal(host, TerminalLaunchMode.HERDR)
                                                    },
                                                    onConnectBatch = { batch ->
                                                        // Batch connection: Establish sessions one by one (run in the background) without redirecting to the terminal screen
                                                        batch.forEach { host ->
                                                            sessionManager.open(host, settings.autoReconnect) {
                                                                hosts = repository.listHosts()
                                                            }
                                                        }
                                                    },
                                                    onDisconnect = { host ->
                                                        // Disconnect all sessions from this host: Keep terminal connections open + release SFTP connections (same as “Close All”)
                                                        sessionManager.closeAllForHost(host.id)
                                                        closeScreenForHost(host)
                                                        agentControllerPool.remove(host.id)
                                                    },
                                                    onOpenSession = { controller ->
                                                        // Tap the card = Connect to this host using the current configuration: Old sessions with changed configurations or credentials will not be reused,
                                                        // Create a new session using the current configuration (previous sessions remain on the Connections page and can be manually closed or re-entered).
                                                        if (controller.credentialKey !=
                                                            sessionManager.signatureFor(controller.host)
                                                        ) {
                                                            val fresh =
                                                                sessionManager.open(
                                                                    controller.host,
                                                                    settings.autoReconnect,
                                                                    controller.launchMode,
                                                                ) {
                                                                    hosts = repository.listHosts()
                                                                }
                                                            pendingNavigate = fresh
                                                        } else {
                                                            currentTab = SessionTab.Terminal(controller)
                                                            navigate(Screen.Terminal)
                                                        }
                                                    },
                                                    onOpenSftp = { host, session ->
                                                        // Use the entry's `uiState` (if available): re-enter without resetting the browser state/path
                                                        val entry =
                                                            sessionManager.sftpSessions.firstOrNull {
                                                                it.host.id ==
                                                                    host.id
                                                            }
                                                        currentTab =
                                                            SessionTab.Sftp(host, session, entry?.uiState ?: SftpUiState())
                                                        navigate(Screen.Terminal)
                                                    },
                                                    onOpenScreenSession = showScreenSession,
                                                    onStartSftp = { host -> connectSftp(host, null) },
                                                    onOpenScreen = openScreenFromHost,
                                                    onCloseAllSessions = { host ->
                                                        // Close all sessions on this host: Disconnect terminal session + Release SFTP connection
                                                        sessionManager.closeAllForHost(host.id)
                                                        closeScreenForHost(host)
                                                        agentControllerPool.remove(host.id)
                                                    },
                                                    onDelete = { host ->
                                                        sessionManager.closeForHost(host.id)
                                                        closeScreenForHost(host)
                                                        agentControllerPool.remove(host.id)
                                                        SecretStore.delete(
                                                            SECRET_SERVICE,
                                                            secretAccountFor(host.id, "password"),
                                                        )
                                                        SecretStore.delete(
                                                            SECRET_SERVICE,
                                                            secretAccountFor(host.id, "privateKey"),
                                                        )
                                                        repository.deleteHost(host.id)
                                                        refreshHosts()
                                                    },
                                                    onAgents = { host -> navigate(Screen.Agents(host.id)) },
                                                )

                                            HomeTab.CONNECTIONS ->
                                                ConnectionsScreen(
                                                    sessions =
                                                        sessionManager.sessions.map { HostSessionItem.Terminal(it) } +
                                                            sessionManager.sftpSessions.map {
                                                                HostSessionItem.Sftp(
                                                                    it.host,
                                                                    it.session,
                                                                    it.createdAt,
                                                                )
                                                            } +
                                                            screenSessions.map { HostSessionItem.Screen(it) } +
                                                            agentSessionItems,
                                                    onOpen = { item ->
                                                        when (item) {
                                                            is HostSessionItem.Terminal -> {
                                                                currentTab = SessionTab.Terminal(item.controller)
                                                                navigate(Screen.Terminal)
                                                            }
                                                            is HostSessionItem.Sftp -> {
                                                                // Page Re-entry: Use the `uiState` of the `entry` (preserve browsing state/path)
                                                                val entry =
                                                                    sessionManager.sftpSessions.firstOrNull {
                                                                        it.host.id == item.host.id &&
                                                                            it.session === item.session
                                                                    }
                                                                currentTab =
                                                                    SessionTab.Sftp(
                                                                        item.host,
                                                                        item.session,
                                                                        entry?.uiState ?: SftpUiState(),
                                                                    )
                                                                navigate(Screen.Terminal)
                                                            }
                                                            is HostSessionItem.Screen -> showScreenSession(item.entry)
                                                            is HostSessionItem.Agent ->
                                                                navigate(Screen.Agents(item.controller.host.id))
                                                        }
                                                    },
                                                    onClose = { item ->
                                                        when (item) {
                                                            is HostSessionItem.Terminal -> {
                                                                if (item.isActive) {
                                                                    sessionManager.disconnect(item.controller)
                                                                } else {
                                                                    sessionManager.remove(item.controller)
                                                                }
                                                            }
                                                            is HostSessionItem.Sftp -> {
                                                                // Use the same two-part syntax as in the terminal: active = disconnect (retains path to reconnect),
                                                                // Disconnected (session=null) = Removed from the list
                                                                val entry =
                                                                    sessionManager.sftpSessions
                                                                        .firstOrNull {
                                                                            it.session === item.session ||
                                                                                (
                                                                                    item.session == null &&
                                                                                        it.host.id == item.host.id
                                                                                )
                                                                        }
                                                                when {
                                                                    entry == null -> {}
                                                                    entry.session != null ->
                                                                        sessionManager.disconnectSftp(
                                                                            entry,
                                                                        )
                                                                    else -> sessionManager.closeSftp(entry)
                                                                }
                                                            }
                                                            is HostSessionItem.Screen -> closeScreenForHost(item.entry.host)
                                                            is HostSessionItem.Agent ->
                                                                agentControllerPool.remove(item.controller.host.id)
                                                        }
                                                    },
                                                )

                                            HomeTab.SETTINGS ->
                                                SettingsScreen(
                                                    settings = settings,
                                                    onChange = { new ->
                                                        // Save as you go
                                                        repository.saveSettings(new)
                                                    },
                                                    repository = repository,
                                                    notificationPermissionState = notificationPermissionState,
                                                    notificationPermissionController = notificationPermissionController,
                                                    onNotificationPermissionStateChange = {
                                                        notificationPermissionState = it
                                                    },
                                                    backgroundProtectionState = backgroundProtection,
                                                    subPage = settingsSubPage,
                                                    onOpenSub = { settingsSubPage = it },
                                                )
                                        }
                                    }
                                }
                            }
                        }

                        is Screen.Edit -> {
                            val existing = hosts.firstOrNull { it.id == s.hostId }
                            HostEditScreen(
                                existing = existing,
                                repository = repository,
                                onSave = { host, pw, key ->
                                    if (pw.isNotBlank()) {
                                        SecretStore.set(
                                            SECRET_SERVICE,
                                            secretAccountFor(host.id, "password"),
                                            pw,
                                        )
                                    }
                                    if (key.isNotBlank()) {
                                        SecretStore.set(
                                            SECRET_SERVICE,
                                            secretAccountFor(host.id, "privateKey"),
                                            key,
                                        )
                                    }
                                    repository.upsertHost(host)
                                    refreshHosts()
                                    navigateBack()
                                },
                                onCancel = ::navigateBack,
                            )
                        }

                        is Screen.Agents -> {
                            val host = hosts.firstOrNull { it.id == s.hostId }
                            if (host == null) {
                                navigateBack()
                            } else {
                                val agentController = acquireAgentController(host)
                                AgentScreen(
                                    host = host,
                                    repository = repository,
                                    controller = agentController,
                                    initialContext = s.context,
                                    onRelease = { agentControllerPool.release(host.id) },
                                    onBack = ::navigateBack,
                                    // Remote Screen Control (Reusing Terminal Screen Streaming Infrastructure): Session Entries/Callbacks
                                    // Owned by AppRoot; fullscreen playback, close/reconnect/install/tier on the Agent page
                                    // Follow the same establishScreen workflow (the agent connection is not interrupted when switching pages)
                                    screenEntry = screenSessions.firstOrNull { it.host.id == host.id },
                                    onStartScreen = openScreen,
                                    onCloseScreen = closeScreenForHost,
                                    onReconnectScreen = reconnectScreenForHost,
                                    onInstallScreenService = installScreenService,
                                    onScreenConfigChange = streamConfigChange,
                                )
                            }
                        }

                        // Return to the home page without disconnecting: Runs in the background by default, and the session is retained in SessionManager,
                        // Kept alive by the foreground service; you can re-enter from the “Connection” page (terminal buffer is preserved as-is).
                        // Terminal screens = All sessions (across hosts): Connect to any host on the same terminal screen,
                        // The tab bar is organized by “user@host + status point” (Termius-style global sessions)
                        is Screen.Terminal -> {
                            // Terminal session tab does not filter status: disconnected/failure sessions are retained (reflected in status points within the tab),
                            // Remove from the list only when the tab is closed; otherwise, reassembly after creating an SFTP connection will discard inactive terminal tabs.
                            val terminalTabs = sessionManager.sessions.map { SessionTab.Terminal(it) }
                            val sftpTabs =
                                sessionManager.sftpSessions
                                    .map { SessionTab.Sftp(it.host, it.session, it.uiState) }
                            val allTabs = terminalTabs + sftpTabs
                            val current =
                                currentTab?.takeIf { tab ->
                                    tab.id in allTabs.map { it.id }
                                } ?: allTabs.firstOrNull()
                            if (current != null) {
                                val tabs = allTabs
                                // "+" Add session affiliation: The host of the currently selected session (use the host from the SFTP tab)
                                val currentHost =
                                    when (current) {
                                        is SessionTab.Terminal -> current.controller.host
                                        is SessionTab.Sftp -> current.host
                                        is SessionTab.Screen -> current.host
                                    }
                                // Screen session entries (same-origin for floating window/fullscreen hostnames: no dependency on the current tab)
                                val pipEntry =
                                    (current as? SessionTab.Terminal)?.let { termTab ->
                                        screenSessions.firstOrNull {
                                            it.ownerSessionId == termTab.controller.sessionId
                                        }
                                    }
                                TerminalScreen(
                                    tabs = tabs,
                                    current = current,
                                    theme = terminalTheme,
                                    settings = settings,
                                    repository = repository,
                                    onBack = {
                                        // When going back, prioritize switching to the tab in history (SFTP/screen → Return to the previous terminal tab),
                                        // only when the stack is empty does it return home
                                        val prevId = tabHistory.removeLastOrNull()
                                        val target = prevId?.let { id -> allTabs.firstOrNull { it.id == id } }
                                        if (target != null && target.id != currentTab?.id) {
                                            currentTab = target
                                        } else {
                                            // The screen exit animation will still render in the terminal for approximately 120 ms; clearing it at this point will cause
                                            // current: Revert to allTabs.firstOrNull(), the second tab
                                            // The first tab should appear immediately upon exiting. Keep the selection until the next entry overwrites it.
                                            refreshHosts()
                                            navigateBack()
                                        }
                                    },
                                    onSwitchTab = { it ->
                                        // Manual tab taps are same-level switches and don't write back history; only function entries like “File Management” that explicitly enter a second-level tab record the source. Otherwise
                                        // The entry point only records the source when explicitly entering a second-level tab. Otherwise,
                                        // Terminal → SFTP → Terminal pushes both sides onto the stack, causing you to jump back and forth.
                                        currentTab = it
                                    },
                                    onAddSession = {
                                        val c =
                                            sessionManager.open(currentHost, settings.autoReconnect) {
                                                hosts = repository.listHosts()
                                            }
                                        pendingNavigate = c
                                    },
                                    onCloseTab = { tab ->
                                        when (tab) {
                                            is SessionTab.Terminal -> sessionManager.remove(tab.controller)
                                            is SessionTab.Sftp -> {
                                                sessionManager.sftpSessions
                                                    .firstOrNull { it.session === tab.session }
                                                    ?.let { sessionManager.closeSftp(it) }
                                            }
                                            is SessionTab.Screen -> {
                                                val entry = screenSessions.firstOrNull { it.session === tab.session }
                                                if (entry != null) {
                                                    entry.session?.close()
                                                    screenSessions.remove(entry)
                                                }
                                            }
                                        }
                                        // Clean up invalid tab IDs from the history stack (closed sessions)
                                        val liveIds =
                                            (
                                                sessionManager.sessions
                                                    .map { SessionTab.Terminal(it) } +
                                                    sessionManager.sftpSessions
                                                        .map { SessionTab.Sftp(it.host, it.session, it.uiState) } +
                                                    screenSessions.map {
                                                        SessionTab.Screen(
                                                            it.host,
                                                            it.ownerSessionId,
                                                            it.session,
                                                            it.uiState,
                                                        )
                                                    }
                                            ).map { it.id }
                                                .toSet()
                                        tabHistory.removeAll { it !in liveIds }
                                        val remaining =
                                            (
                                                sessionManager.sessions
                                                    .map { SessionTab.Terminal(it) } +
                                                    sessionManager.sftpSessions
                                                        .map { SessionTab.Sftp(it.host, it.session, it.uiState) }
                                            ).firstOrNull { it.id != tab.id }
                                        currentTab = remaining
                                        if (remaining == null) {
                                            refreshHosts()
                                            navigateBack()
                                        }
                                    },
                                    onOpenSftpPicker = { sftpPickerVisible = true },
                                    // File Management: Establish an SFTP session directly with the current host and switch to the SFTP tab
                                    // (Reuse the entire connectSftp workflow: authentication dialog / host key / reconnection)
                                    onOpenSftpForHost = connectSftp,
                                    // Favorites: read persisted favorites, pop a list to jump (if none, show a hint)
                                    onOpenFavorites = { host ->
                                        val favs = repository.loadFavorites(host.id)
                                        if (favs.isEmpty()) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    appStrings.sftpExt.favoritesEmpty,
                                                )
                                            }
                                        } else {
                                            favoritesDialog = host to favs
                                        }
                                    },
                                    // Screen: Create a streaming session and switch to the Screen tab
                                    onOpenScreen = openScreen,
                                    // Screen Streaming Service Installation (Guide Card Button): Streaming Log → Reconnect after installation and session rebuild
                                    onInstallScreenService = installScreenService,
                                    // Screen Reconnect (Fullscreen In-Place): Rebuild the session on the host
                                    onReconnectScreenForHost = reconnectScreenForHost,
                                    // Fullscreen frame rate/image quality toggle: Write the remote relay configuration via SSH → Rebuild the session for the changes to take effect.
                                    // ⚠️ The quality tier must be set on the new uiState after rebuild: Rebuild (establishScreen)
                                    // It will create a new ScreenUiState (default state); if set on an existing object, it will be replaced,
                                    // The number in the upper-right corner never changes (user feedback)
                                    onStreamConfigChange = streamConfigChange,
                                    // Reconnection after a screen disconnect: Rebuild the session and replace the tab
                                    onReconnectScreen = { tab ->
                                        scope.launch {
                                            try {
                                                establishScreen(tab.host) { session, uiState ->
                                                    val existingEntry =
                                                        screenSessions.firstOrNull { it.host.id == tab.host.id }
                                                    existingEntry?.session?.close()
                                                    screenSessions.removeAll { it.host.id == tab.host.id }
                                                    screenSessions.add(
                                                        ScreenSessionEntry(
                                                            tab.host,
                                                            tab.ownerSessionId,
                                                            session,
                                                            uiState,
                                                            existingEntry?.createdAt
                                                                ?: Clock.System.now().toEpochMilliseconds(),
                                                        ),
                                                    )
                                                }
                                            } catch (e: Exception) {
                                                snackbarHostState.showSnackbar(
                                                    appStrings.screen.connecting + " " + (e.message ?: ""),
                                                )
                                            }
                                        }
                                    },
                                    // Terminal-screen floating window: only show screen sessions **belonging to the current terminal tab** (hidden when switching away)
                                    screenPip = pipEntry?.uiState,
                                    screenFullscreenRequestHostId = screenFullscreenRequestHostId,
                                    onScreenFullscreenRequestConsumed = {
                                        screenFullscreenRequestHostId = null
                                    },
                                    // Screen session on your own host (shown in the fullscreen header, independent of the current tab)
                                    pipHost = pipEntry?.host,
                                    // Floating window ✕: Close the current screen session (destroy the streaming + remove the entry)
                                    onCloseScreenPip = {
                                        val ownerId = (current as? SessionTab.Terminal)?.controller?.sessionId
                                        if (ownerId != null) {
                                            val entry = screenSessions.firstOrNull { it.ownerSessionId == ownerId }
                                            if (entry != null) {
                                                entry.session?.close()
                                                screenSessions.remove(entry)
                                            }
                                        }
                                    },
                                    // Bookmark Changes: Adding or removing bookmarks on the SFTP page is now saved to disk immediately
                                    onFavoritesChanged = { host, favs ->
                                        repository.saveFavorites(host.id, favs)
                                    },
                                    // Instantly achieve browsing path persistence: Save as you navigate (fallback to saving in the background when minimized).
                                    onSftpPathChanged = { _, _ -> sessionManager.persistNow() },
                                    // Terminal output and SFTP remote paths go directly to the Agent on the same host, without passing through the clipboard or a file.
                                    onAskAgent = { host, context -> navigate(Screen.Agents(host.id, context)) },
                                    // SFTP Reconnection: Rebuild the session by replacing the tab (preserving the path/list in uiState)
                                    onReconnectSftp = { tab ->
                                        // session (optional; resumes entries upon process restart): host.id + reference double match,
                                        // Avoid mismatches when there are multiple "null-session" entries.
                                        val entry =
                                            sessionManager.sftpSessions.find {
                                                it.host.id == tab.host.id && it.session === tab.session
                                            }
                                        scope.launch {
                                            try {
                                                establishSftp(tab.host) { newSession, token ->
                                                    entry?.let { sessionManager.reconnectSftp(it, newSession, token) }
                                                    currentTab = SessionTab.Sftp(tab.host, newSession, tab.uiState)
                                                    // Reconnect success: Clears the "Reconnecting" or "Disconnected" status; SftpContent continues browsing using the original path.
                                                    tab.uiState.reconnecting = false
                                                    tab.uiState.disconnected = false
                                                }
                                            } catch (e: Exception) {
                                                // Reconnect failure: Keep the banner; users can click the button to retry.
                                                tab.uiState.reconnecting = false
                                                tab.uiState.loadError = e.message
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                // New SFTP connection overlay (displayed on top of the current screen)
                if (sftpPickerVisible) {
                    SftpHostPickerOverlay(
                        hosts = hosts,
                        onDismiss = { sftpPickerVisible = false },
                        onSelect = { host -> connectSftp(host, null) },
                    )
                }

                // Favorites dialog (terminal + menu entry): Lists favorite paths; tap to go directly
                favoritesDialog?.let { (host, favs) ->
                    SftpFavoritesDialog(
                        favorites = favs,
                        onOpen = { favorite ->
                            favoritesDialog = null
                            connectSftp(host, favorite)
                        },
                        onRemove = { favorite ->
                            val remaining = favs - favorite
                            favoritesDialog = host to remaining
                            repository.saveFavorites(host.id, remaining)
                        },
                        onDismiss = { favoritesDialog = null },
                    )
                }

                // SFTP Authentication / Host Key Confirmation (Reuse Global Dialog)
                sftpAuth?.let { req ->
                    AuthPromptDialog(req.prompt) { answers ->
                        req.deferred.complete(answers)
                        sftpAuth = null
                    }
                }
                sftpHostKey?.let { req ->
                    HostKeyDialog(
                        key = req.key,
                        changed = req.changed,
                        previousFingerprint = req.previousFingerprint,
                    ) { accept ->
                        req.deferred.complete(accept)
                        sftpHostKey = null
                    }
                }

                // The Agent controller remains active for one minute after leaving the screen; authentication requests must therefore also be rendered globally.
                agentAuth?.second?.let { req ->
                    AuthPromptDialog(req.prompt) { answers ->
                        req.deferred.complete(answers)
                        if (agentAuth?.second === req) agentAuth = null
                    }
                }
                agentHostKey?.second?.let { req ->
                    HostKeyDialog(
                        key = req.key,
                        changed = req.changed,
                        previousFingerprint = req.previousFingerprint,
                    ) { accept ->
                        req.deferred.complete(accept)
                        if (agentHostKey?.second === req) agentHostKey = null
                    }
                }

                if (showBackgroundProtectionGuide) {
                    BackgroundProtectionDialog(
                        vendor = backgroundVendor,
                        onOpenSettings = {
                            showBackgroundProtectionGuide = false
                            openApplicationSettings()
                        },
                        onDismiss = { showBackgroundProtectionGuide = false },
                    )
                }

                MacodesSnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun BackgroundProtectionDialog(
    vendor: BackgroundProtectionVendor,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    val vendorHint =
        when (vendor) {
            BackgroundProtectionVendor.OPPO_FAMILY -> s.permissions.backgroundOppoHint
            BackgroundProtectionVendor.XIAOMI_FAMILY -> s.permissions.backgroundXiaomiHint
            BackgroundProtectionVendor.VIVO_FAMILY -> s.permissions.backgroundVivoHint
            BackgroundProtectionVendor.SAMSUNG -> s.permissions.backgroundSamsungHint
            BackgroundProtectionVendor.GENERIC -> s.permissions.backgroundGenericHint
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.permissions.backgroundTitle) },
        text = {
            Column {
                Text(s.permissions.backgroundBody)
                Text(
                    vendorHint,
                    modifier = Modifier.padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text(s.permissions.openSettings) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s.permissions.notNow) }
        },
    )
}
