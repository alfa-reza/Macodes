package dev.macodes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.macodes.data.AppSettings
import dev.macodes.data.Host
import dev.macodes.data.HostRepository
import dev.macodes.data.SECRET_SERVICE
import dev.macodes.data.SecretStore
import dev.macodes.data.asrKeyAccount
import dev.macodes.ssh.SftpSession
import dev.macodes.term.argbToRgb
import dev.macodes.ui.theme.Sizes
import dev.macodes.ui.theme.Spacing
import dev.macodes.ui.theme.StatusColors
import dev.macodes.ui.theme.TerminalTheme
import dev.macodes.util.hapticTick
import dev.macodes.util.monospaceFontFamily
import dev.macodes.voice.AsrEngine
import dev.macodes.voice.MicrophoneRecorder
import dev.macodes.voice.createAsrEngine
import dev.macodes.voice.rememberMicPermissionRequester
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/** Terminal screen session tab: SSH/Mosh terminal or SFTP file browser. */
sealed interface SessionTab {
    val id: String

    data class Terminal(
        val controller: TerminalController,
    ) : SessionTab {
        override val id: String get() = controller.sessionId
    }

    data class Sftp(
        val host: Host,
        val session: SftpSession?,
        val uiState: SftpUiState = SftpUiState(),
    ) : SessionTab {
        override val id: String get() = "sftp:${host.id}:${session?.hashCode() ?: "restored"}"
    }
}

/** Color scheme for the current terminal screen tab: Terminal tabs use the terminal theme; app-type screen tabs use the app theme. */
@Composable
private fun tabColors(
    current: SessionTab,
    theme: TerminalTheme,
): Pair<Color, Color> =
    if (current is SessionTab.Terminal) {
        theme.background() to theme.foreground()
    } else {
        MaterialTheme.colorScheme.background to MaterialTheme.colorScheme.onSurface
    }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    tabs: List<SessionTab>,
    current: SessionTab,
    theme: TerminalTheme,
    settings: AppSettings,
    /** Insert command snippets in the terminal (read from the global library). */
    repository: HostRepository,
    onBack: () -> Unit,
    onSwitchTab: (SessionTab) -> Unit,
    onAddSession: () -> Unit,
    onCloseTab: (SessionTab) -> Unit,
    onOpenSftpPicker: () -> Unit,
    /** File Management (menu item): Directly opens the SFTP file management view for the current host and navigates to the specified directory. */
    onOpenSftpForHost: (Host, String?) -> Unit = { _, _ -> },
    /** Favorites (menu item): Lists the current host’s favorites directory and navigates to it. */
    onOpenFavorites: (Host) -> Unit = {},
    /** Persistence of changes to favorites (SFTP page callback, save to AppRoot). */
    onFavoritesChanged: (Host, List<String>) -> Unit = { _, _ -> },
    /** Instant persistence of SFTP browse paths (the last directory is not lost upon crash or force kill). */
    onSftpPathChanged: (Host, String) -> Unit = { _, _ -> },
    /** Pass the selected terminal output and the current directory to an Agent on the same host; the content is treated solely as untrusted context. */
    onAskAgent: (Host, AgentLaunchContext) -> Unit = { _, _ -> },
    /** SFTP reconnection: Replace the session in the tab after rebuilding the connection (implemented by AppRoot). */
    onReconnectSftp: (SessionTab.Sftp) -> Unit = {},
) {
    val s = LocalAppStrings.current
    // Confirm before closing a tab: The “x” is too easy to click by accident
    var pendingClose by remember { mutableStateOf<SessionTab?>(null) }
    // Background: Before `statusBarsPadding` was applied, the status bar/Dynamic Island region shared the same color as the screen,
    // Avoid exposing the app’s light background at the top when the terminal is black (or exposing a dark background when the terminal is white).
    val (pageBackground, pageForeground) = tabColors(current, theme)
    Box(Modifier.fillMaxSize().background(pageBackground)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            TerminalTabBar(
                tabs = tabs,
                current = current,
                barBackground = pageBackground,
                barForeground = pageForeground,
                onBack = onBack,
                onSwitch = onSwitchTab,
                onAdd = onAddSession,
                onClose = { pendingClose = it },
                onSftp = onOpenSftpPicker,
                theme = theme,
            )
            when (val tab = current) {
                is SessionTab.Terminal ->
                    TerminalBody(
                        controller = tab.controller,
                        theme = theme,
                        settings = settings,
                        repository = repository,
                        onBack = onBack,
                        onOpenSftpForHost = onOpenSftpForHost,
                        onOpenFavorites = onOpenFavorites,
                        onFavoritesChanged = onFavoritesChanged,
                        onSftpPathChanged = onSftpPathChanged,
                        onAskAgent = onAskAgent,
                    )
                is SessionTab.Sftp ->
                    SftpContent(
                        host = tab.host,
                        session = tab.session,
                        state = tab.uiState,
                        onBack = onBack,
                        onReconnect = { onReconnectSftp(tab) },
                        onFavoritesChanged = { favs -> onFavoritesChanged(tab.host, favs) },
                        onPathChanged = { p -> onSftpPathChanged(tab.host, p) },
                        onAskAgent = { context -> onAskAgent(tab.host, context) },
                    )
            }
        }

        pendingClose?.let { tab ->
            val host =
                when (tab) {
                    is SessionTab.Terminal -> tab.controller.host
                    is SessionTab.Sftp -> tab.host
                }
            AlertDialog(
                onDismissRequest = { pendingClose = null },
                title = { Text(s.terminalCloseTabTitle) },
                text = { Text(s.terminalCloseTabBody("${host.username}@${host.hostname}")) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingClose = null
                            onCloseTab(tab)
                        },
                    ) {
                        Text(s.terminalConfirm)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingClose = null }) {
                        Text(s.terminalCancel)
                    }
                },
            )
        }
    }
}

/** Terminal body: banner + canvas + toolbar + input field (toggle tabs based on session ID for reassembly). */
@Suppress("DEPRECATION") // Compose 1.8’s new Clipboard does not yet have a stable, cross-platform plain-text construction API.
@Composable
private fun TerminalBody(
    controller: TerminalController,
    theme: TerminalTheme,
    settings: AppSettings,
    repository: HostRepository,
    onBack: () -> Unit,
    /** File Management (menu item): Opens the SFTP file management view for the current host and navigates to the specified directory. */
    onOpenSftpForHost: (Host, String?) -> Unit = { _, _ -> },
    /** Favorites (menu item): Lists the current host’s favorites directory and navigates to it. */
    onOpenFavorites: (Host) -> Unit = {},
    /** Persistence of changes to favorites (SFTP page callback, save to AppRoot). */
    onFavoritesChanged: (Host, List<String>) -> Unit = { _, _ -> },
    /** Instant persistence of SFTP browse paths (the last directory is not lost upon crash or force kill). */
    onSftpPathChanged: (Host, String) -> Unit = { _, _ -> },
    /** Pass the selected terminal output and the current directory to an Agent on the same host. */
    onAskAgent: (Host, AgentLaunchContext) -> Unit = { _, _ -> },
) {
    val s = LocalAppStrings.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    var inputValue by remember { mutableStateOf(TextFieldValue("")) }
    // Submitted baseline: Input method compositions (Pinyin, etc.) are excluded from the diff and are updated only upon commit.
    var committedText by remember { mutableStateOf("") }
    // Command snippet insertion panel
    var snippetOpen by remember { mutableStateOf(false) }
    // Git panel (FAB and toolbar “⎇” key share the same switch)
    var gitPanelOpen by remember { mutableStateOf(false) }
    // Expanded state of the bottom-right feature menu (Voice / Git / Future Features)
    var toolMenuOpen by remember { mutableStateOf(false) }

    // ---- Terminal File Upload (Menu item: Select Directory → System File Selector → SFTP Stream Upload)----
    val uploader = remember(controller) { TerminalFileUploader(controller, scope) }
    var uploadState by remember { mutableStateOf<UploadUiState>(UploadUiState.Idle) }
    uploader.onState = { st -> uploadState = st }
    // Target Directory Dialog: Fill after workdir detection is complete (current directory + /tmp)
    var uploadDirDialog by remember { mutableStateOf(false) }
    var uploadTargets by remember { mutableStateOf<List<String>>(emptyList()) }
    // Selected target directory (used when the file selector callback is invoked)
    var uploadDir by remember { mutableStateOf("") }
    // System file selector: Multiple selection, file-by-file callback → Enqueue for serial upload
    val filePicker =
        rememberFilePicker(
            onPicked = { file ->
                val dir = uploadDir
                if (dir.isNotBlank()) {
                    uploader.enqueue(file, dir)
                } else {
                    file.close()
                }
            },
        )

    // ---- Voice Input (Floating Microphone FAB on the canvas; press and hold to speak, release to send)----
    val micPermission = rememberMicPermissionRequester()
    var voiceState by remember { mutableStateOf(VoiceUiState.IDLE) }
    var voiceSession by remember { mutableStateOf<AsrEngine?>(null) }
    // Pressed flag: permission dialog / async connect; if the user releases before the authorization callback, this flag cancels the start
    var voicePressActive by remember { mutableStateOf(false) }
    var voiceStartMs by remember { mutableStateOf(0L) }

    /** Real-time volume 0..1 (updated by recording callback, consumed by wave animation). */
    var voiceLevel by remember { mutableFloatStateOf(0f) }

    /** Real-time text transcription (intermediate results; text appears as you speak). */
    var voicePartial by remember { mutableStateOf("") }

    /** Seconds elapsed since recording began (central floating timer). */
    var voiceSeconds by remember { mutableIntStateOf(0) }

    /** Canvas region dimensions (bounds constrained by the recording group’s drag). */
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    /** Audio group drag offset (initial position centered horizontally + offset; maintained within the session; long press the standby button to reset to the center). */
    var voiceDrag by remember { mutableStateOf(Offset.Zero) }
    val recorder = remember { MicrophoneRecorder() }

    /** Maximum duration for voice input (foolproof; automatically sends when time is up). */
    val voiceMaxMs = 60_000L

    /** PCM16 little-endian → normalized RMS volume (called by the recording thread; UI is updated via `scope.launch`). */
    fun pcmLevel(pcm: ByteArray): Float {
        if (pcm.size < 2) return 0f
        var sum = 0.0
        var i = 0
        while (i < pcm.size - 1) {
            val s = ((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            sum += s.toDouble() * s
            i += 2
        }
        val rms = kotlin.math.sqrt(sum / (pcm.size / 2))
        // Mute the background noise to 0 (the waveform should not experience any jitter due to noise); leave a little headroom in the saturated sections.
        return (((rms / 32768.0) - 0.02) * 1.2).toFloat().coerceIn(0f, 1f)
    }

    fun resetVoice() {
        voiceSession = null
        voiceState = VoiceUiState.IDLE
        voicePressActive = false
        voiceLevel = 0f
        voicePartial = ""
        voiceSeconds = 0
    }

    fun onVoiceError(message: String) {
        recorder.stop()
        resetVoice()
        scope.launch { snackbar.showSnackbar(s.voice.error(message)) }
    }

    fun endVoice() {
        val session = voiceSession ?: return
        if (voiceState != VoiceUiState.LISTENING) return
        voicePressActive = false
        recorder.stop()
        val duration = Clock.System.now().toEpochMilliseconds() - voiceStartMs
        if (duration < 300L) {
            // Accidental click (one tap): Discard and display a tooltip
            session.abort()
            resetVoice()
            scope.launch { snackbar.showSnackbar(s.voice.holdToTalk) }
        } else {
            session.finish()
        }
    }

    fun beginVoice() {
        if (voiceState != VoiceUiState.IDLE) return
        if (settings.hapticFeedback) hapticTick()
        if (!settings.voiceInputEnabled) {
            scope.launch { snackbar.showSnackbar(s.voice.disabled) }
            return
        }
        // Use the first recognized service that has been enabled (from the provider list, which is extensible).
        val provider = settings.asrProviders.firstOrNull { it.enabled }
        if (provider == null) {
            scope.launch { snackbar.showSnackbar(s.voice.notConfigured) }
            return
        }
        val apiKey = SecretStore.get(SECRET_SERVICE, asrKeyAccount(provider.id))
        if (apiKey.isNullOrBlank()) {
            scope.launch { snackbar.showSnackbar(s.voice.notConfigured) }
            return
        }
        voicePressActive = true
        micPermission.request { granted ->
            // The user has released their finger during the authorization dialog: Do not launch
            if (!voicePressActive) return@request
            if (!granted) {
                resetVoice()
                scope.launch { snackbar.showSnackbar(s.voice.noPermission) }
                return@request
            }
            val session = createAsrEngine(provider, apiKey)
            voiceSession = session
            session.onState = { st ->
                scope.launch {
                    voiceState =
                        when (st) {
                            AsrEngine.State.LISTENING -> VoiceUiState.LISTENING
                            AsrEngine.State.FINALIZING -> VoiceUiState.RECOGNIZING
                            else -> VoiceUiState.IDLE
                        }
                }
            }
            session.onFinalText = { text ->
                scope.launch {
                    resetVoice()
                    // Normal submission: The terminal already displays input echo; do not show a toast (only display error, timeout, or accidental touch tooltips).
                    controller.sendText(text)
                }
            }
            session.onPartial = { text ->
                scope.launch { voicePartial = text }
            }
            session.onError = { msg -> scope.launch { onVoiceError(msg) } }
            session.start()
            val ok =
                recorder.start(
                    onData = { pcm ->
                        session.sendPcm(pcm)
                        // Volume waveform: RMS updated every 200 ms, with smooth interpolation in the animation layer.
                        // Write the recording thread directly (make the snapshot thread-safe): launch on the main thread
                        // incurs scheduling latency; when the main thread is busy, the silence detector reads a stale volume and may falsely trigger a silence auto-send
                        voiceLevel = pcmLevel(pcm)
                    },
                    onError = { msg -> scope.launch { onVoiceError(msg) } },
                )
            if (!ok) {
                session.abort()
                resetVoice()
            } else {
                voiceStartMs = Clock.System.now().toEpochMilliseconds()
                // Automatically stop and send when the timer runs out (foolproof: prevents forgetting to let go or holding down the button).
                scope.launch {
                    delay(voiceMaxMs)
                    if (voiceSession === session && voiceState == VoiceUiState.LISTENING) {
                        scope.launch { snackbar.showSnackbar(s.voice.timeout) }
                        endVoice()
                    }
                }
            }
        }
    }

    // Recording timer: Increments every second during LISTENING (a floating overlay shows the recorded duration)
    LaunchedEffect(voiceState) {
        if (voiceState == VoiceUiState.LISTENING) {
            voiceSeconds = 0
            while (voiceState == VoiceUiState.LISTENING) {
                delay(1000)
                voiceSeconds++
            }
        }
    }

    // Walkie-Talkie Mode: Automatically ends when muted (hands-free). When muted continuously for ~3.0s and more than 800ms of recording has been captured,
    // Automatic end-of-send—no need to tap “End” while speaking and looking at the screen; relaxed threshold to tolerate soft speech
    // (RMS ~650 corresponds to a volume of 0.02; normal speech is 0.05–0.3) and 1–3-second pauses within sentences
    // (2s may accidentally send the message during a pause in thought; users have provided feedback stating that “it cuts off mid-sentence”).
    LaunchedEffect(voiceState) {
        if (voiceState == VoiceUiState.LISTENING) {
            var silentTicks = 0
            while (voiceState == VoiceUiState.LISTENING) {
                delay(200)
                val elapsed = Clock.System.now().toEpochMilliseconds() - voiceStartMs
                if (elapsed > 800L) {
                    if (voiceLevel < 0.02f) {
                        silentTicks++
                        if (silentTicks >= 15) {
                            endVoice()
                            break
                        }
                    } else {
                        silentTicks = 0
                    }
                }
            }
        }
    }

    // Upload Success/Failure Tooltips (Listener Status Changes)
    LaunchedEffect(uploadState) {
        when (val st = uploadState) {
            is UploadUiState.Done -> {
                if (st.count > 0) {
                    // Automatically input remote paths into the terminal (multiple files concatenated with spaces; ready to use at the cursor position)
                    if (st.paths.isNotEmpty()) {
                        controller.sendText(st.paths.joinToString(" "))
                    }
                    snackbar.showSnackbar(s.upload.done(st.count))
                }
                uploadState = UploadUiState.Idle
            }
            is UploadUiState.Failed -> {
                snackbar.showSnackbar(s.upload.failed(st.message))
                uploadState = UploadUiState.Idle
            }
            else -> {}
        }
    }

    // Upload target directory selection dialog: Current directory (appears only if detected) / Temporary directory, card-style options
    if (uploadDirDialog) {
        AlertDialog(
            onDismissRequest = { uploadDirDialog = false },
            title = { Text(s.upload.dirTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    uploadTargets.forEach { dir ->
                        if (dir == "/tmp") {
                            UploadDirOptionCard(
                                icon = Icons.Filled.FolderOpen,
                                title = s.upload.dirTmp,
                                subtitle = "/tmp",
                                onClick = {
                                    uploadDirDialog = false
                                    uploadDir = dir
                                    filePicker()
                                },
                            )
                        } else {
                            UploadDirOptionCard(
                                icon = Icons.Filled.FolderOpen,
                                title = s.upload.dirCurrent,
                                subtitle = dir,
                                onClick = {
                                    uploadDirDialog = false
                                    uploadDir = dir
                                    filePicker()
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { uploadDirDialog = false }) { Text(s.terminalCancel) }
            },
        )
    }

    // Height of the bottom floating toolbar content (excluding navigation bar and keyboard padding), used to calculate the canvas translate amount
    // (Incremental coverage of the row when the keyboard pops up)
    var toolbarHeightPx by remember { mutableFloatStateOf(0f) }
    // The toolbar occupies two lines of height (excluding the expanded row, which takes up 3/4 of the height): the canvas layout is calculated based solely on this—expand/collapse
    // Do not change the canvas size, so as not to trigger a PTY resize (a screen flash caused by the TUI’s overall reflow); expand rows to cover the canvas
    var toolbarBaseHeightPx by remember { mutableFloatStateOf(0f) }
    // Total toolbar coverage height (including navigation bar and keyboard padding): The connection indicator is positioned accordingly at
    // Center within the visible region of the “Canvas–toolbar”; do not shift the visual focus upward (as in HerdrInstallGuide).
    var toolbarOverlayPx by remember { mutableFloatStateOf(0f) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    // Keyboard visibility: If the IME inset extends above the navigation bar, it is considered visible (cross-platform; `isImeVisible` is Android-specific).
    val imeVisible =
        WindowInsets.ime.getBottom(LocalDensity.current) >
            WindowInsets.navigationBars.getBottom(LocalDensity.current)
    // Clicking canvas/keyboard buttons: Focus changes and input connection establishment are async; calling `show()` immediately is often ignored,
    // Latency for a split second before explicitly bringing up the keyboard.
    val showKeyboard: () -> Unit = {
        inputFocusRequester.requestFocus()
        scope.launch {
            delay(150)
            keyboardController?.show()
        }
    }

    // The default colors for OSC 10/11/12 responses follow the current terminal theme:
    // TUI tools such as herdr query the default foreground and background colors to determine their own color schemes,
    // If dark mode values are hard-coded, the entire UI will be rendered in black on light themes.
    LaunchedEffect(theme) {
        val b = controller.buffer
        b.defaultFgRgb = argbToRgb(theme.foreground)
        b.defaultBgRgb = argbToRgb(theme.background)
        b.defaultCursorRgb = argbToRgb(theme.cursor)
    }

    // Only one back handler is registered on the terminal screen; items are collapsed from top to bottom according to the visual hierarchy.
    // Avoid depending on the registration order of Compose BackHandler; this must be included when adding a fullscreen layer in the terminal.
    PlatformBackHandler(enabled = true) {
        when (
            terminalBackTarget(
                voiceActive = voiceState != VoiceUiState.IDLE,
                gitOpen = gitPanelOpen,
                toolMenuOpen = toolMenuOpen,
                snippetsOpen = snippetOpen,
                selectionActive = controller.selection.isActive,
            )
        ) {
            TerminalBackTarget.CANCEL_VOICE -> {
                voiceSession?.abort()
                recorder.stop()
                resetVoice()
            }
            TerminalBackTarget.CLOSE_GIT -> gitPanelOpen = false
            TerminalBackTarget.CLOSE_TOOL_MENU -> toolMenuOpen = false
            TerminalBackTarget.CLOSE_SNIPPETS -> snippetOpen = false
            TerminalBackTarget.CLEAR_SELECTION -> {
                controller.selection.clear()
                controller.frame++
            }
            TerminalBackTarget.EXIT -> onBack()
        }
    }

    // OSC 52: Writing to the system clipboard from a remote program (nvim/yazi/tmux) (silent write, no tooltip,
    // (Avoid repeated interruptions caused by accidental copying while dragging or scrolling in Herdr.)
    controller.onRemoteClipboard = { text ->
        clipboard.setText(AnnotatedString(text))
    }

    // Cursor blinking: Periodically toggles visibility and redraws after a connection is established
    LaunchedEffect(controller.status, settings.cursorBlink) {
        while (controller.status == ConnStatus.CONNECTED) {
            delay(530)
            if (settings.cursorBlink) {
                controller.blinkCursor()
            }
        }
    }

    // Haptic feedback for a successful connection (the first time “CONNECTED” appears)
    var prevStatus by remember { mutableStateOf(controller.status) }
    LaunchedEffect(controller.status) {
        if (controller.status == ConnStatus.CONNECTED && prevStatus != ConnStatus.CONNECTED) {
            if (settings.hapticFeedback) hapticTick()
        }
        prevStatus = controller.status
    }

    // The top safe area is inset uniformly by TerminalScreen; the body follows the session bar directly.
    Column(Modifier.fillMaxSize()) {
        // Session Body: When toggling tabs, perform reassembly of the entire session based on the unique session ID (input fields and local state remain independent).
        key(controller.sessionId) {
            // Wait until TerminalView has measured the actual canvas size before establishing the connection, to avoid PTY starting at 80x24,
            // and herdr and similar remote relayout tools do not readjust to a wrong window size.
            // Place within the `key` block: Each session is independent; toggling tabs or creating a new tab will re-establish the connection.
            var connectSent by remember { mutableStateOf(false) }
            // Toolbar expanded to row 3/4 (persistent within the session): Status raised to the caller; canvas set to fixed height
            // layout and expanded rows cover the canvas (no resize, no flicker); the state is remembered inside the key block, per session
            // Toolbar expanded to row 3/4 (persistent within the session): Status raised to the caller; canvas set to fixed height
            // layout and expanded rows cover the canvas (no resize, no flicker); the state is remembered inside the key block, per session
            var toolbarExpanded by remember { mutableStateOf(false) }

            // Top banner text: Error/Disconnect/Offline (with a centered indicator on the canvas during the connection; see below)
            val bannerText =
                when {
                    controller.linkLostSeconds >= LINK_LOST_THRESHOLD_SECONDS ->
                        s.terminalMoshLostContact(controller.linkLostSeconds)
                    controller.errorMessage != null -> controller.errorMessage
                    controller.status == ConnStatus.ERROR -> s.terminalFailed
                    else -> null
                }
            // Status indicators: Used for overlay banners (showing/hiding the "Close" and "Reconnect" buttons)
            val bannerIsError = controller.status == ConnStatus.ERROR || controller.status == ConnStatus.CLOSED
            // ✕ appears only when the banner actually shows the errorMessage text: the "Connecting..." / "Connection lost" text
            // This has higher priority; in this case, clearing `errorMessage` does not change the text and only adds to the confusion.
            val bannerDismissable =
                controller.errorMessage != null &&
                    controller.status != ConnStatus.CONNECTING &&
                    controller.linkLostSeconds < LINK_LOST_THRESHOLD_SECONDS
            val bannerReconnectable = controller.status == ConnStatus.ERROR || controller.status == ConnStatus.CLOSED

            // Terminal canvas + floating toolbar: Ensure the bottom edge of the canvas aligns with the top edge of the toolbar (so it is not obscured by the toolbar).
            // The canvas is laid out with a “two-line” height ([toolbarBaseHeightPx]): expanded rows cover 3/4 of the height.
            // At the bottom of the canvas rather than compressing the canvas—does not trigger PTY resize, and TUIs such as herdr do not reflow or cause screen flickering.
            // When the keyboard pops up, do not squeeze the canvas (adjustNothing); moving the toolbar upward will obscure the bottom of the canvas,
            // Translate up from TerminalView to ensure the cursor is visible; the translation amount includes rows that have been expanded in addition to keyboard input.
            // Coverage height (the keyboard returns to its original position when closed; the top menu and other elements remain visible at all times).
            // The final 12dp is the visual spacing margin between the canvas and the toolbar.
            val density = LocalDensity.current
            // Navigation bar height (px, value when the keyboard is hidden): The toolbar is raised by `navigationBarsPadding`,
            // TerminalView’s positioning is based on the bottom of the Box (extending to the bottom of the screen)—if the nav bar isn’t padded, it will overlap.
            // nav-9dp (gesture navigation ~25dp → the terminal’s bottom row would be mostly covered). Use remember to pin the first measured
            // Value: The `navigationBars inset` may change (report 0) when the keyboard pops up; a fixed value prevents the canvas from
            // from resizing with the keyboard (TUI re-layout flicker)
            val navInsets = WindowInsets.navigationBars
            val navBarsBottomPx = remember { navInsets.getBottom(density) }
            val coveredBottomPx =
                WindowInsets.ime.getBottom(density).toFloat() +
                    with(density) { 12.dp.toPx() } +
                    // Expand to 3/4 of the row height (when expanded, >0; translate only when the keyboard is popped up)
                    (toolbarHeightPx - toolbarBaseHeightPx)
            // Leave a small margin around the canvas; text does not touch the screen edges; rows/columns are recomputed from the inset width
            Box(
                Modifier
                    .weight(1f)
                    .clipToBounds()
                    .background(theme.background())
                    .onSizeChanged { canvasSize = it },
            ) {
                when {
                    controller.herdrNeedsInstall || controller.herdrInstalling -> {
                        // Leave space at the bottom for the toolbar height: Center the card within the “canvas–toolbar” region so that the visual center of gravity is not shifted upward.
                        HerdrInstallGuide(controller, with(density) { (toolbarHeightPx + navBarsBottomPx).toDp() })
                    }
                    controller.moshNeedsInstall || controller.moshInstalling -> {
                        MoshInstallGuide(controller, with(density) { (toolbarHeightPx + navBarsBottomPx).toDp() })
                    }
                    else -> {
                        TerminalView(
                            controller = controller,
                            theme = theme,
                            fontSizeSp = settings.terminalFontSize.toFloat(),
                            targetCols = settings.terminalTargetCols,
                            coveredBottomPx = coveredBottomPx,
                            keyboardVisible = imeVisible,
                            onReady = { cols, rows ->
                                // Once the toolbar height is measured (sizeStable), establish a connection: the canvas size has been adjusted to account for the toolbar region,
                                // avoid starting the PTY with the wrong row count (the one that includes the covered region)
                                if (!connectSent && toolbarBaseHeightPx > 0f) {
                                    connectSent = true
                                    controller.connect(cols, rows)
                                }
                            },
                            // If the canvas is too tall when the first-frame toolbar hasn’t been measured yet (for medium sizes): skip resize/onReady,
                            // avoid starting the PTY with the wrong row count and then resizing (the whole TUI re-lays out and jumps)
                            sizeStable = toolbarBaseHeightPx > 0f,
                            onCopy = { text ->
                                clipboard.setText(AnnotatedString(text))
                                scope.launch { snackbar.showSnackbar(s.terminalCopied) }
                            },
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                                    // Allocate only two rows of height: expand the rows to cover 3/4 of the canvas (without compression,
                                    // (No resizing or reflow; expand and collapse without screen flickering.)
                                    // Make room for the navigation bar: The toolbar is raised by `navigationBarsPadding`,
                                    // If not padded, the bottom row of the terminal will be obscured by the toolbar (see navBarsBottomPx).
                                    .padding(
                                        bottom =
                                            with(density) {
                                                (toolbarBaseHeightPx + navBarsBottomPx).toDp() +
                                                    12.dp
                                            },
                                    ),
                        )
                    }
                }

                // Connection: A centered indicator is visible on the canvas (shared for initial connection and reconnect), fading in and out—connection complete
                // Fade out smoothly without leaving a trace of the top overlay (errors, disconnections, or loss of connection still trigger the top banner).
                // Color follows the terminal theme: when a light theme is applied, do not display bright-colored capsules on a dark canvas
                ConnectingIndicator(
                    visible = controller.status == ConnStatus.CONNECTING,
                    text =
                        if (controller.reconnectCount > 0) {
                            when (controller.reconnectCause) {
                                SessionReconnectCause.FOREGROUND_RESUME ->
                                    s.terminalRestoringAfterBackgroundN(controller.reconnectCount)
                                SessionReconnectCause.NETWORK_CHANGED ->
                                    s.terminalRestoringAfterNetworkChangeN(controller.reconnectCount)
                                SessionReconnectCause.CONNECTION_LOST, null ->
                                    s.terminalReconnectingN(controller.reconnectCount)
                            }
                        } else {
                            s.terminalConnecting
                        },
                    bottomOverlay = with(density) { toolbarOverlayPx.toDp() },
                    containerColor = theme.background(),
                    contentColor = theme.foreground(),
                    modifier = Modifier.align(Alignment.Center),
                )

                // Top-level floating banner: Overlays the canvas (does not occupy layout space or push the canvas down)
                bannerText?.let { message ->
                    ErrorBanner(
                        message = message,
                        isError = bannerIsError,
                        onDismiss =
                            if (bannerDismissable) {
                                { controller.dismissError() }
                            } else {
                                null
                            },
                        onReconnect =
                            if (bannerReconnectable) {
                                { controller.reconnect() }
                            } else {
                                null
                            },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }

                MacodesSnackbarHost(snackbar, Modifier.align(Alignment.TopCenter))

                // Git Floating Panel: Floating button on the right (draggable) + status/diff panel (matches terminal theme).
                // Git commands run through a separate exec channel (SSH reuses an authenticated connection / mosh control plane connection),
                // Do not perform injection into the interactive terminal; use the “⎇” key on the toolbar to expand the panel.
                GitOverlay(
                    connected = controller.status == ConnStatus.CONNECTED,
                    inAltScreen = controller.buffer.altScreen,
                    runner = remember(controller) { TerminalGitCommandRunner(controller) },
                    theme = theme,
                    open = gitPanelOpen,
                    onOpenChange = { gitPanelOpen = it },
                    bottomInset = with(density) { (toolbarHeightPx + navBarsBottomPx).toDp() + 12.dp },
                    onToast = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                    modifier = Modifier.align(Alignment.Center).fillMaxSize(),
                )

                // Function menu in the lower-right corner: + Expand (Upload / File Management / Git; expandable).
                // The voice feature has been moved to a permanent button in the center of the screen and does not appear in the menu.
                if (voiceState == VoiceUiState.IDLE) {
                    CanvasToolMenu(
                        menuOpen = toolMenuOpen,
                        onMenuOpenChange = { toolMenuOpen = it },
                        items =
                            listOf(
                                CanvasMenuAction(
                                    id = "agent",
                                    label = s.nativeAgents.askSelectedOutput,
                                    icon = Icons.Filled.SmartToy,
                                    onClick = {
                                        toolMenuOpen = false
                                        val selected = controller.selection.selectedText().trim()
                                        if (selected.isEmpty()) {
                                            scope.launch { snackbar.showSnackbar(s.nativeAgents.selectOutputFirst) }
                                        } else {
                                            scope.launch {
                                                val wd =
                                                    runCatching {
                                                        TerminalGitCommandRunner(controller).fetchWorkdir()
                                                    }.getOrNull()
                                                controller.selection.clear()
                                                controller.frame++
                                                terminalAgentLaunchContext(
                                                    selected,
                                                    wd,
                                                    s.nativeAgents.terminalContextPrompt,
                                                )?.let { context -> onAskAgent(controller.host, context) }
                                            }
                                        }
                                    },
                                ),
                                CanvasMenuAction(
                                    id = "git",
                                    label = s.git.menuLabel,
                                    icon = Icons.Filled.AccountTree,
                                    badge = MaterialTheme.colorScheme.primary,
                                    onClick = {
                                        toolMenuOpen = false
                                        gitPanelOpen = true
                                    },
                                ),
                                CanvasMenuAction(
                                    id = "upload",
                                    label = s.upload.menuLabel,
                                    icon = Icons.Filled.Upload,
                                    onClick = {
                                        toolMenuOpen = false
                                        // Detect the current directory (reuse Git detection: tmux / /proc / prompt),
                                        // In case of failure, only provide the /tmp option
                                        scope.launch {
                                            val wd =
                                                runCatching {
                                                    TerminalGitCommandRunner(
                                                        controller,
                                                    ).fetchWorkdir()
                                                }.getOrNull()
                                            uploadTargets =
                                                buildList {
                                                    if (!wd.isNullOrBlank()) add(wd)
                                                    add("/tmp")
                                                }
                                            uploadDirDialog = true
                                        }
                                    },
                                ),
                                CanvasMenuAction(
                                    id = "favorites",
                                    label = s.sftpExt.favoritesTitle,
                                    icon = Icons.Filled.Star,
                                    onClick = {
                                        toolMenuOpen = false
                                        onOpenFavorites(controller.host)
                                    },
                                ),
                                CanvasMenuAction(
                                    id = "filemanager",
                                    label = s.upload.fileManagerLabel,
                                    icon = Icons.Filled.FolderOpen,
                                    onClick = {
                                        toolMenuOpen = false
                                        // Detect the terminal’s current working directory (reuse the Git detection chain),
                                        // Navigate directly to this directory when opening the SFTP file manager
                                        scope.launch {
                                            val wd =
                                                runCatching {
                                                    TerminalGitCommandRunner(
                                                        controller,
                                                    ).fetchWorkdir()
                                                }.getOrNull()
                                            onOpenSftpForHost(controller.host, wd)
                                        }
                                    },
                                ),
                            ),
                        theme = theme,
                        modifier =
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 14.dp)
                                .padding(
                                    bottom = with(density) { (toolbarBaseHeightPx + navBarsBottomPx).toDp() + 20.dp },
                                ),
                    )
                }

                // Voice input: The button is permanently located **horizontally centered** on the screen (vertically above the bottom toolbar,
                // (Do not obscure the output in the middle of the terminal.) Standby = the big green button (click once to start);
                // Recording/Recognition in Progress = Red button at the same location + overlay (transcription/sound wave/timer); the entire group is draggable
                // (Drag up to the top; do not enter the toolbar). The two states are in the same position; tapping toggles between them in place without jumping.
                if (settings.voiceInputEnabled && voiceState == VoiceUiState.IDLE) {
                    var startSize by remember { mutableStateOf(IntSize.Zero) }
                    val density = LocalDensity.current
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = with(density) { (toolbarBaseHeightPx + navBarsBottomPx).toDp() + 20.dp })
                            .offset { IntOffset(voiceDrag.x.roundToInt(), voiceDrag.y.roundToInt()) }
                            .onSizeChanged { startSize = it }
                            // Drag: Restrict movement to within the canvas (do not extend beyond or enter the toolbar).
                            .pointerInput(canvasSize, startSize) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    val w = startSize.width
                                    val h = startSize.height
                                    if (canvasSize.width <= 0 || w <= 0) return@detectDragGestures
                                    val margin = with(density) { 8.dp.toPx() }
                                    voiceDrag =
                                        Offset(
                                            (voiceDrag.x + drag.x).coerceIn(
                                                -(canvasSize.width - w) / 2f + margin,
                                                (canvasSize.width - w) / 2f - margin,
                                            ),
                                            (voiceDrag.y + drag.y).coerceIn(
                                                -(canvasSize.height - h - with(density) { 24.dp.toPx() }),
                                                0f,
                                            ),
                                        )
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        VoiceStartButton(
                            onClick = { beginVoice() },
                            // Long press: Reset to the default position in the center of the screen horizontally
                            onLongClick = {
                                voiceDrag = Offset.Zero
                                scope.launch { snackbar.showSnackbar(s.voice.resetDone) }
                            },
                        )
                        // After dragging: A small reset icon appears in the upper-right corner (tap to center).
                        if (voiceDrag != Offset.Zero) {
                            VoiceResetBadge(
                                onClick = {
                                    voiceDrag = Offset.Zero
                                    scope.launch { snackbar.showSnackbar(s.voice.resetDone) }
                                },
                                modifier =
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 3.dp, y = (-3).dp),
                            )
                        }
                    }
                } else if (
                    (voiceState == VoiceUiState.LISTENING || voiceState == VoiceUiState.RECOGNIZING)
                ) {
                    var recordGroupSize by remember { mutableStateOf(IntSize.Zero) }
                    val density = LocalDensity.current
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = with(density) { (toolbarBaseHeightPx + navBarsBottomPx).toDp() + 20.dp })
                            .offset { IntOffset(voiceDrag.x.roundToInt(), voiceDrag.y.roundToInt()) }
                            .onSizeChanged { recordGroupSize = it }
                            .pointerInput(canvasSize, recordGroupSize) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    val w = recordGroupSize.width
                                    val h = recordGroupSize.height
                                    if (canvasSize.width <= 0 || w <= 0) return@detectDragGestures
                                    val margin = with(density) { 16.dp.toPx() }
                                    voiceDrag =
                                        Offset(
                                            (voiceDrag.x + drag.x).coerceIn(
                                                -(canvasSize.width - w) / 2f + margin,
                                                (canvasSize.width - w) / 2f - margin,
                                            ),
                                            // Initially at the bottom (horizontally centered): Allows dragging up to the top, but does not allow dragging into the toolbar
                                            (voiceDrag.y + drag.y).coerceIn(
                                                -(canvasSize.height - h - with(density) { 24.dp.toPx() }),
                                                0f,
                                            ),
                                        )
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            VoiceRecordingOverlayHost(
                                visible = true,
                                state = voiceState,
                                level = voiceLevel,
                                partialText = voicePartial,
                                seconds = voiceSeconds,
                                theme = theme,
                            )
                            BigVoiceStopButton(
                                state = voiceState,
                                onClick = { endVoice() },
                            )
                        }
                    }
                }

                // Upload progress overlay: Above the menu in the lower-right corner (file name + progress bar + percentage)
                if (uploadState is UploadUiState.Uploading) {
                    val up = uploadState as UploadUiState.Uploading
                    TransferProgressCard(
                        title = up.name,
                        progress = if (up.total > 0) (up.sent.toFloat() / up.total).coerceIn(0f, 1f) else 0f,
                        percent = if (up.total > 0) "${up.sent * 100 / up.total}%" else "",
                        subtitle = s.upload.uploading("${up.doneCount + 1}/${up.totalCount}"),
                        modifier =
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 14.dp)
                                .padding(
                                    bottom =
                                        with(density) {
                                            (toolbarBaseHeightPx + navBarsBottomPx).toDp() + 20.dp +
                                                56.dp
                                        },
                                ),
                    )
                }

                // Bottom-floating keyboard toolbar: opaque background + top divider to create visual separation from the system keyboard/terminal content.
                // The outer layer is responsible for avoiding the navigation bar and keyboard; the inner layer measures the content height separately (padding is included in the calculation).
                // (Total height of the outer layer; measuring the outer layer directly will count the keyboard height twice when the layer is translated.)
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(theme.background())
                        .navigationBarsPadding()
                        .imePadding()
                        // Measure the total coverage height (including padding; when the keyboard pops up, the toolbar follows imePadding
                        // (As it moves upward, the coverage height increases, and the center section of the indicator automatically contracts above the keyboard.)
                        .onSizeChanged { toolbarOverlayPx = it.height.toFloat() },
                ) {
                    Column(Modifier.onSizeChanged { toolbarHeightPx = it.height.toFloat() }) {
                        HorizontalDivider(color = theme.foreground().copy(alpha = 0.2f))
                        Spacer(Modifier.height(6.dp))
                        // Keyboard toolbar always visible: Phone system keyboards lack CTRL/ESC/arrow keys; unavailable when the terminal is closed
                        KeyToolbar(
                            ctrlActive = ctrlActive,
                            altActive = altActive,
                            onToggleCtrl = { ctrlActive = !ctrlActive },
                            onToggleAlt = { altActive = !altActive },
                            expanded = toolbarExpanded,
                            onExpandedChange = { toolbarExpanded = it },
                            // The height of the return must include all of KeyToolbar’s own padding (including the 10dp bottom padding).
                            // ——The canvas adjusts to make room for it; if any layer of padding is missing, it will quietly cover the bottom row of the terminal.
                            onBaseHeightChanged = { toolbarBaseHeightPx = it.toFloat() },
                            onKey = { key ->
                                if (settings.hapticFeedback) hapticTick()
                                controller.sendSpecialKey(key)
                                // After any key is pressed, clear any sticky modifier keys to prevent CTRL from contaminating subsequent input.
                                // (Residual characters may cause the next letter to be interpreted as Ctrl+D/C, etc., resulting in an unexpected exit from the session.)
                                ctrlActive = false
                                altActive = false
                            },
                            onToggleKeyboard = {
                                if (settings.hapticFeedback) hapticTick()
                                if (imeVisible) keyboardController?.hide() else showKeyboard()
                            },
                            onSnippets = {
                                if (settings.hapticFeedback) hapticTick()
                                snippetOpen = true
                            },
                            onGit = {
                                if (settings.hapticFeedback) hapticTick()
                                gitPanelOpen = true
                            },
                            onPaste = {
                                val text = clipboard.getText()?.text
                                if (!text.isNullOrEmpty()) {
                                    // bracketed paste: When 2004 is enabled on the remote server, escape sequences are wrapped,
                                    // Tools like Vim and nvim will not treat pasted content as typed text (to avoid incorrect automatic indentation).
                                    val payload =
                                        if (controller.buffer.bracketedPaste) {
                                            "\u001b[200~$text\u001b[201~"
                                        } else {
                                            text
                                        }
                                    controller.sendText(payload)
                                    scope.launch { snackbar.showSnackbar(s.terminalPasted) }
                                }
                                ctrlActive = false
                                altActive = false
                            },
                            onChar = { text -> controller.sendBytes(text.encodeToByteArray()) },
                            theme = theme,
                        )
                        // Command Snippet Panel (triggered by the “{}” key on the keyboard toolbar)
                        if (snippetOpen) {
                            SnippetInsertSheet(
                                repository = repository,
                                theme = theme,
                                onUse = { content, run ->
                                    controller.sendText(if (run) content + "\n" else content)
                                    snippetOpen = false
                                    scope.launch { snackbar.showSnackbar(s.snippetInserted) }
                                },
                                onDismiss = { snippetOpen = false },
                            )
                        }
                        // Hidden input field: Serves only as an input method interface; does not render a visible UI.
                        // The content entered should be based on the terminal echo (the remote echo reflects the actual state, while the local display may cause unnecessary duplication and interference).
                        BasicTextField(
                            value = inputValue,
                            onValueChange = { new ->
                                if (new.composition != null) {
                                    // For input method compositions (such as Pinyin): Update the view only; do not send.
                                    inputValue = new
                                    return@BasicTextField
                                }
                                val oldText = committedText
                                val newText = new.text
                                if (newText != oldText) {
                                    // Common prefix "diff": Pure additions or deletions at the end are precise operations;
                                    // Edits in the middle cannot be mapped to the remote cursor; handle by trimming the tail and resending
                                    var p = 0
                                    val maxP = minOf(oldText.length, newText.length)
                                    while (p < maxP && oldText[p] == newText[p]) p++
                                    repeat(oldText.length - p) { controller.sendBytes(byteArrayOf(0x7f)) }
                                    val added = newText.substring(p)
                                    if (added.isNotEmpty()) {
                                        sendTyped(controller, added, ctrlActive, altActive)
                                        ctrlActive = false
                                        altActive = false
                                    }
                                }
                                committedText = newText
                                // Clear the input field after pressing Enter or if buffering is too long.
                                if (newText.contains('\n') || newText.length > 64) {
                                    committedText = ""
                                    inputValue = TextFieldValue("")
                                } else {
                                    inputValue = new
                                }
                            },
                            modifier =
                                Modifier
                                    .size(1.dp)
                                    .alpha(0f)
                                    .focusRequester(inputFocusRequester)
                                    // Backspace: Do not intercept when the input field contains content (platform delete → onValueChange diff sent,
                                    // (The cursor/selection is managed by the system and will not become disorganized); only when the input field is empty (after pressing Enter or after a quick command)
                                    // Intercept and send 0x7f directly; otherwise, content on the remote row cannot be deleted even though it exists.
                                    // Excluding IME composing states: The combined text has not yet been sent to the remote server; let the IME handle the deletion itself.
                                    .onPreviewKeyEvent { ev ->
                                        if (ev.type == KeyEventType.KeyDown &&
                                            ev.key == Key.Backspace &&
                                            inputValue.composition == null &&
                                            committedText.isEmpty()
                                        ) {
                                            controller.sendBytes(byteArrayOf(0x7f))
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    // Fallback: Call `show()` only after focus is actually obtained; this resolves the `requestFocus` issue on certain device models.
                                    // the immediate show() being ignored (the keyboard fails to come up)
                                    .onFocusChanged {
                                        // DECSET 1004: Focus/Unfocus Events (vim/tmux refreshes the UI state accordingly)
                                        if (controller.buffer.focusEvents) {
                                            controller.sendBytes(
                                                if (it.isFocused) {
                                                    "\u001b[I".encodeToByteArray()
                                                } else {
                                                    "\u001b[O".encodeToByteArray()
                                                },
                                            )
                                        }
                                        if (it.isFocused) keyboardController?.show()
                                    },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = theme.foreground()),
                            cursorBrush = SolidColor(Color.Transparent),
                            keyboardOptions =
                                KeyboardOptions(
                                    // Use Text instead of Ascii: Ascii makes the input method disable Chinese candidate suggestions
                                    keyboardType = KeyboardType.Text,
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false,
                                ),
                            // Multi-line mode to catch the Enter key from the input method (\n → CR)
                            singleLine = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Terminal screen multi-session tab bar: Back button + swipeable session tabs (system logo + user@host + X)
 * + Add buttons (Connect / Connect via SFTP; both have icons).
 */
@Composable
private fun TerminalTabBar(
    tabs: List<SessionTab>,
    current: SessionTab,
    barBackground: Color,
    barForeground: Color,
    onBack: () -> Unit,
    onSwitch: (SessionTab) -> Unit,
    onAdd: () -> Unit,
    onClose: (SessionTab) -> Unit,
    onSftp: () -> Unit,
    theme: TerminalTheme,
) {
    val s = LocalAppStrings.current
    var addOpen by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Layout positions of the scroll container and each tab: Scroll to the center when a tab is tapped
    val containerX = remember { mutableStateOf(0f) }
    val containerWidth = remember { mutableStateOf(0f) }
    val chipLayout = remember { mutableStateMapOf<String, Pair<Float, Float>>() }

    fun scrollToCenter(sessionId: String) {
        val (x, w) = chipLayout[sessionId] ?: return
        // chip's offset within the content = rendering position - container position + current scroll position
        val offsetInContent = x - containerX.value + scroll.value
        val target = offsetInContent - (containerWidth.value - w) / 2
        scope.launch {
            scroll.animateScrollTo(target.toInt().coerceIn(0, scroll.maxValue))
        }
    }

    // Automatically scroll to the center when the current tab changes (entering from the home page / toggling tabs / selecting a new session):
    // Both chip layout and scroll range are measured async (onGloballyPositioned / horizontalScroll),
    // The first frame may not have been measured yet; poll until several frames are ready before proceeding (if not ready, abandon the operation; do not engage in blocking).
    LaunchedEffect(current.id) {
        var attempts = 0
        while (attempts < 20 &&
            (chipLayout[current.id] == null || containerWidth.value <= 0f || scroll.maxValue <= 0)
        ) {
            delay(16)
            attempts++
        }
        scrollToCenter(current.id)
    }

    Column(Modifier.fillMaxWidth().background(barBackground)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.HeaderCompact)
                .padding(horizontal = Spacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = s.navBack,
                    tint = barForeground,
                )
            }
            // Session tab: Swipe horizontally. The visible width is measured by the outer Box (horizontalScroll’s
            // The `Row` will measure itself to an unlimited width based on its content; `onSizeChanged` returns the total width of the content rather than the viewport width,
            // (Centered formulas will overflow.)
            Box(
                Modifier
                    .weight(1f)
                    .onSizeChanged { containerWidth.value = it.width.toFloat() },
            ) {
                Row(
                    Modifier
                        .horizontalScroll(scroll)
                        .onGloballyPositioned { containerX.value = it.positionInRoot().x },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEach { tab ->
                        val host =
                            when (tab) {
                                is SessionTab.Terminal -> tab.controller.host
                                is SessionTab.Sftp -> tab.host
                            }
                        // Terminal sessions on the same host are numbered according to the **order of the current tab list** (1, 2, 3…):
                        // Consider only the currently active tabs; automatically reorder after deletion (if the second one is deleted, the original third becomes the second).
                        val hostTerminalTabs =
                            tabs.filter {
                                (it as? SessionTab.Terminal)?.controller?.host?.id == host.id
                            }
                        val seq = (tab as? SessionTab.Terminal)?.let { hostTerminalTabs.indexOf(it) + 1 } ?: 0
                        val statusColor =
                            (tab as? SessionTab.Terminal)?.let {
                                statusColor(it.controller.status, it.controller.linkLostSeconds)
                            }
                        // Disconnected/failure sessions tabs are grayed out (status indicators remain: gray dot = disconnected, red dot = failure)
                        val inactive =
                            (tab as? SessionTab.Terminal)?.let {
                                val st = it.controller.status
                                st != ConnStatus.CONNECTED && st != ConnStatus.CONNECTING && st != ConnStatus.AUTH
                            } ?: false
                        val typeLabel =
                            when (tab) {
                                is SessionTab.Terminal ->
                                    if (tab.controller.launchMode == TerminalLaunchMode.HERDR) {
                                        s.hostsActionHerdr
                                    } else {
                                        s.hostsActionTerminal
                                    }
                                is SessionTab.Sftp -> s.hostsActionFiles
                            }
                        SessionTabChip(
                            host = host,
                            seq = seq,
                            showSeq = hostTerminalTabs.size > 1,
                            typeLabel = typeLabel,
                            statusDotColor = statusColor,
                            inactive = inactive,
                            selected = tab.id == current.id,
                            foreground = barForeground,
                            onClick = { onSwitch(tab) },
                            onClose = { onClose(tab) },
                            onPositioned = { x, w -> chipLayout[tab.id] = x to w },
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { addOpen = true }) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = s.hostsAdd,
                        tint = barForeground,
                    )
                }
                DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(s.hostsConnect) },
                        leadingIcon = { Icon(Icons.Filled.Link, null) },
                        onClick = {
                            addOpen = false
                            onAdd()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(s.hostsConnectSftp) },
                        leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                        onClick = {
                            addOpen = false
                            onSftp()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Session tab title: **Show user-defined names first**; if no name is specified (the "name" field reverts to
 * (hostname) Fallback to `user@host`—custom names serve as identifiers in the user’s mind, while IP addresses and domain names are alternatives.
 * When multiple sessions exist on the same host ([showSeq]), append **the current tab list number** `(n)` to the title.
 * (Numbered in order of remaining tabs: 1, 2, 3…; automatically renumbered after deleting them).
 */
internal fun sessionTabTitle(
    host: Host,
    seq: Int = 0,
    showSeq: Boolean = false,
): String {
    val base =
        if (host.name.isNotBlank() && host.name != host.hostname) {
            host.name
        } else {
            "${host.username}@${host.hostname}"
        }
    return if (showSeq) "$base ($seq)" else base
}

/** Single session tab: System avatar + title + close X; the current tab is highlighted and shows a connection status dot. */
@Composable
private fun SessionTabChip(
    host: Host,
    /** Host session number: Show `(n)` only when [showSeq] is set. */
    seq: Int = 0,
    showSeq: Boolean = false,
    /** With mixed terminal types on the same host (Terminal/Herdr/SFTP) this is visible at a glance, avoiding indistinguishable same-name tabs. */
    typeLabel: String,
    statusDotColor: Color?,
    /** Disconnected/failed sessions: Reduce the opacity of text and background (status dots remain distinguishable). */
    inactive: Boolean = false,
    selected: Boolean,
    foreground: Color,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onPositioned: (x: Float, width: Float) -> Unit,
) {
    val sys = host.system.ifBlank { host.hostname }
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) {
                    foreground.copy(alpha = if (inactive) 0.09f else 0.18f)
                } else {
                    foreground.copy(alpha = if (inactive) 0.03f else 0.07f)
                },
            ).clickable(onClick = onClick)
            .padding(start = 8.dp, top = 5.dp, bottom = 5.dp, end = 2.dp)
            .onGloballyPositioned { coords ->
                onPositioned(coords.positionInRoot().x, coords.size.width.toFloat())
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // System Avatar
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(systemColor(sys)),
            contentAlignment = Alignment.Center,
        ) {
            SystemAvatarIcon(sys, 11.dp)
        }
        Spacer(Modifier.size(6.dp))
        Text(
            "${sessionTabTitle(host, seq, showSeq)} · $typeLabel",
            style = MaterialTheme.typography.labelSmall,
            color = if (inactive) foreground.copy(alpha = 0.4f) else foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(4.dp))
        if (selected && statusDotColor != null) {
            Box(
                Modifier.size(8.dp).clip(CircleShape).background(statusDotColor),
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(22.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = null,
                tint = foreground.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private fun sendTyped(
    controller: TerminalController,
    text: String,
    ctrl: Boolean,
    alt: Boolean,
) {
    if (alt) controller.sendBytes(byteArrayOf(0x1b))
    for (ch in text) {
        if (ch == '\n') {
            // Press Enter in the terminal to send a CR (carriage return).
            controller.sendBytes(byteArrayOf(0x0d))
        } else if (ctrl && ch in 'a'..'z') {
            controller.sendBytes(byteArrayOf((ch.code and 0x1f).toByte()))
        } else {
            controller.sendBytes(ch.toString().encodeToByteArray())
        }
    }
}

/** herdr installation guidance card: Displayed in the center of the canvas; guides users to install if not already installed on the remote device + real-time installation log.
 *  @param bottomInset Bottom height (toolbar); the card is centered within this height to maintain visual balance. */
@Composable
private fun HerdrInstallGuide(
    controller: TerminalController,
    bottomInset: Dp,
) {
    val s = LocalAppStrings.current
    Box(
        Modifier
            .fillMaxSize()
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp + bottomInset),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Filled.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    s.herdrMissing,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    s.herdrInstallHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (controller.herdrInstalling) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            s.herdrInstalling,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Install real-time logging: Show the last 8 rows; visible when the process is hung and no new progress is being made.
                    val log = controller.herdrInstallLog
                    if (log.isNotBlank()) {
                        Text(
                            log.lines().takeLast(8).joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = monospaceFontFamily(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(10.dp),
                        )
                    }
                } else {
                    Button(onClick = { controller.installHerdr() }) {
                        Text(s.herdrInstall)
                    }
                }
            }
        }
    }
}

/** Mosh Installation Guide Card: Guides the installation of `mosh-server` on the remote machine (via a package manager) and allows for a fallback to SSH.
 *  @param bottomInset Bottom height (toolbar); the card is centered within this height to maintain visual balance. */
@Composable
private fun MoshInstallGuide(
    controller: TerminalController,
    bottomInset: Dp,
) {
    val s = LocalAppStrings.current
    Box(
        Modifier
            .fillMaxSize()
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp + bottomInset),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Filled.Link,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    s.moshMissing,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    s.moshInstallHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                // Provide specific guidance on the cause of failure (excluding “not installed” scenarios: locale, dependencies, port conflicts, etc.)
                controller.moshInstallReason?.let { reason ->
                    Text(
                        reason,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                if (controller.moshInstalling) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            s.moshInstalling,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Install real-time logging: Show the last 8 rows; visible when the process is hung and no new progress is being made.
                    val log = controller.moshInstallLog
                    if (log.isNotBlank()) {
                        Text(
                            log.lines().takeLast(8).joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = monospaceFontFamily(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(10.dp),
                        )
                    }
                } else {
                    // sudo requires a password: input field (the password is used only for this installation and is transmitted via an encrypted SSH channel)
                    val sudoPwd = remember { mutableStateOf("") }
                    if (controller.moshNeedsSudoPassword) {
                        OutlinedTextField(
                            value = sudoPwd.value,
                            onValueChange = { sudoPwd.value = it },
                            label = { Text(s.moshSudoPasswordLabel) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            s.moshSudoPasswordHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Button(
                        onClick = {
                            controller.installMosh(
                                if (controller.moshNeedsSudoPassword) sudoPwd.value else null,
                            )
                        },
                        enabled = !controller.moshNeedsSudoPassword || sudoPwd.value.isNotBlank(),
                    ) {
                        Text(s.moshInstall)
                    }
                    // Fallback option: Users can choose not to install it and continue using SSH
                    TextButton(onClick = { controller.degradeMoshToSsh() }) {
                        Text(s.moshDegradeToSsh)
                    }
                }
            }
        }
    }
}

/** Top-level error/status banner: A rectangular banner overlaying the canvas (does not occupy layout space or push the canvas down). */
@Composable
private fun ErrorBanner(
    message: String,
    isError: Boolean,
    onDismiss: (() -> Unit)?,
    onReconnect: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val s = LocalAppStrings.current
    val color = if (isError) StatusColors.Error else StatusColors.Warning
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.35f))
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            color = color,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        if (onReconnect != null) {
            TextButton(onClick = onReconnect) {
                Text(s.terminalReconnect, color = color, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (onDismiss != null) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = s.settingsClose,
                    tint = color,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
