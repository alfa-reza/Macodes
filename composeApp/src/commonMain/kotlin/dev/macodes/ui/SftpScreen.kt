package dev.macodes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.macodes.data.Host
import dev.macodes.generated.resources.Res
import dev.macodes.generated.resources.folder
import dev.macodes.notify.showDownloadDoneNotification
import dev.macodes.ssh.SftpEntry
import dev.macodes.ssh.SftpSession
import dev.macodes.ui.theme.StatusColors
import dev.macodes.util.TermLog
import dev.macodes.util.decodeImage
import dev.macodes.util.ioDispatcher
import dev.macodes.util.monospaceFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.painterResource

/** SFTP sort method. */
enum class SftpSort { NAME, DATE, SIZE, KIND }

/**
 * SFTP browsing status: Survives with the session entry (held by SessionManager),
 * Tabbing away from or closing the terminal screen and returning does not reset the path or list.
 */
class SftpUiState {
    var path by mutableStateOf("")
    var entries by mutableStateOf<List<SftpEntry>?>(null)
    var loadError by mutableStateOf<String?>(null)

    /** The underlying connection has been disconnected (onClosed push notification): A banner is shown immediately; users can manually reconnect.
     *  Previously, disconnections went unnoticed (empty implementation of `onClosed`), and the issue was only revealed in the event of reloading failure after tab switching and reassembly of the page. */
    var disconnected by mutableStateOf(false)

    /** Reconnection after a disconnection: An amber “Reconnecting…” banner shows at the top (same style as terminal reconnection). */
    var reconnecting by mutableStateOf(false)

    /** Has automatic reconnection upon disconnection been attempted? Retain across reassembly; automatically attempt only once per session lifecycle (to prevent loops). */
    var autoReconnectAttempted by mutableStateOf(false)
    var sort by mutableStateOf(SftpSort.NAME)
    var showHidden by mutableStateOf(false)
    var searching by mutableStateOf(false)
    var query by mutableStateOf("")
    var newFolderDialog by mutableStateOf(false)

    /** File entries that show the context menu (files only; tapping on directories opens them directly). */
    var fileMenu by mutableStateOf<SftpEntry?>(null)

    /** Multiple-selection mode: A set of selected item names (non-empty = multiple-selection mode; toggle checkmarks on list rows). */
    val selection = mutableStateListOf<String>()

    /** Collection of bookmarked directory paths (quick navigation). */
    val favorites = mutableStateListOf<String>()

    /** Rename the dialog target (null = disabled). */
    var renameTarget by mutableStateOf<SftpEntry?>(null)

    /** Delete the list of confirmation targets (null = disabled). */
    var deleteTargets by mutableStateOf<List<SftpEntry>?>(null)

    /** Path to files selected for batch download (async processing via the DirectorySaver callback). */
    var pendingDownloadMulti by mutableStateOf<List<String>?>(null)

    /** Refreshing via pull-to-refresh. */
    var refreshing by mutableStateOf(false)

    /** File entries currently being previewed (null = preview panel closed). */
    var previewEntry by mutableStateOf<SftpEntry?>(null)

    /** The stable remote path and playback position for video previews are resumed when switching tabs. */
    var previewRemotePath by mutableStateOf<String?>(null)
    var previewVideoPositionMillis by mutableStateOf(0L)

    /** Preview loading... */
    var previewLoading by mutableStateOf(false)

    /** Preview text (loading complete; null = not ready). */
    var previewText by mutableStateOf<String?>(null)

    /** Image preview bitmap (image file; null = not ready/not an image). */
    var previewImage by mutableStateOf<ImageBitmap?>(null)

    /** Preview with truncation (file exceeds read limit; only the first paragraph is shown). */
    var previewTruncated by mutableStateOf(false)

    /** Preview the cause of the failure (null = no error). */
    var previewError by mutableStateOf<String?>(null)

    /** Files that begin downloading after the user selects a save location (the save callback is async; do not depend on `fileMenu`. */
    var pendingDownload by mutableStateOf<SftpEntry?>(null)

    /** Switch for the drop-down parent menu in the breadcrumb "…". */
    var showParents by mutableStateOf(false)

    /**
     * Directory navigation history stack: Push the old path onto the stack when navigating to a directory or using breadcrumbs,
     * Pressing Back pops the stack to the previous browsing location (not mechanically the parent directory).
     */
    val history = mutableStateListOf<String>()
}

/** Remote path concatenation: Do not include a double slash in the root directory. */
internal fun joinPath(
    base: String,
    name: String,
): String = if (base == "/") "/$name" else "$base/$name"

/** Time-based sorting groups for DATE: Today / This Week / Earlier (if the time is unknown, it falls under “Earlier”). */
internal fun dateBucket(
    modifiedAt: Long,
    s: AppStrings,
): String {
    if (modifiedAt <= 0) return s.sftpExt.groupEarlier
    val tz = TimeZone.currentSystemDefault()
    val today = Clock.System.todayIn(tz)
    val date = Instant.fromEpochMilliseconds(modifiedAt).toLocalDateTime(tz).date
    return when {
        date == today -> s.sftpExt.groupToday
        date >= today.minus(DatePeriod(days = 6)) -> s.sftpExt.groupWeek
        else -> s.sftpExt.groupEarlier
    }
}

/** Single-line entry rendering: Tap or long press to select multiple items; when no items are selected, access the directory or preview the file. */
@Composable
private fun EntryRow(
    entry: SftpEntry,
    selecting: Boolean,
    selection: List<String>,
    path: String,
    onToggle: (String) -> Unit,
    onEnterSelection: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onPreview: (SftpEntry) -> Unit,
) {
    SftpRowItem(
        name = entry.name,
        // Show permission strings for directories; show file sizes (file size is more useful than permissions on mobile devices).
        subtitle = if (entry.isDirectory) entry.permissions else formatSize(entry.size),
        time = formatTime(entry.modifiedAt),
        isDirectory = entry.isDirectory,
        selected = entry.name in selection,
        onClick = {
            when {
                selecting -> onToggle(entry.name)
                entry.isDirectory -> onNavigate(joinPath(path, entry.name))
                else -> onPreview(entry)
            }
        },
        onLongClick = { if (!selecting) onEnterSelection(entry.name) },
    )
}

/**
 * Recursively download directories: Write to the local [DirectorySink] using relative paths. Skip `.`/`..`;
 * If a single-file download fails, an exception is thrown up the call stack (prompted by the caller), and any files that have been written are closed.
 */
internal fun downloadDir(
    session: SftpSession,
    remotePath: String,
    sink: DirectorySink,
    rel: String = "",
) {
    for (e in session.list(remotePath)) {
        if (e.name == "." || e.name == "..") continue
        val childRel = if (rel.isEmpty()) e.name else "$rel/${e.name}"
        if (e.isDirectory) {
            downloadDir(session, joinPath(remotePath, e.name), sink, childRel)
        } else {
            val file = sink.openFile(childRel)
            try {
                session.download(joinPath(remotePath, e.name)) { chunk -> file.write(chunk) }
            } finally {
                file.close()
            }
        }
    }
}

/** Batch download selected remote files to a local sink (used in conjunction with directory downloads: via the multi-select toolbar). */
internal fun downloadFilesToSink(
    session: SftpSession,
    paths: List<String>,
    sink: DirectorySink,
) {
    paths.forEach { remotePath ->
        val name = remotePath.substringAfterLast('/').ifBlank { "file" }
        val file = sink.openFile(name)
        try {
            session.download(remotePath) { chunk -> file.write(chunk) }
        } finally {
            file.close()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION") // Compose 1.8’s new Clipboard does not yet have a stable, cross-platform plain-text construction API.
@Composable
fun SftpContent(
    host: Host,
    session: SftpSession?,
    state: SftpUiState,
    onBack: () -> Unit,
    /** Reconnection (AppRoot will rebuild the session and replace the current tab). */
    onReconnect: () -> Unit,
    /** Persistence callback for favorites changes (written to disk in AppRoot; null = no persistence). */
    onFavoritesChanged: (List<String>) -> Unit = {},
    /** Navigation path change callback (AppRoot provides persistence of data in real time; data is not lost upon crash or force kill). */
    onPathChanged: (String) -> Unit = {},
    /** Pass the selected remote path directly to the Agent on the same host; do not download to a phone or re-upload. */
    onAskAgent: (AgentLaunchContext) -> Unit = {},
) {
    val s = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    // Delegation of browsing states to the session-level [SftpUiState]: states are retained even after switching tabs or exiting the composition.
    var path by state::path
    var entries by state::entries
    var loadError by state::loadError
    var sort by state::sort
    var showHidden by state::showHidden
    var searching by state::searching
    var query by state::query
    var newFolderDialog by state::newFolderDialog
    var fileMenu by state::fileMenu
    var pendingDownload by state::pendingDownload
    var showParents by state::showParents
    var previewEntry by state::previewEntry
    var previewLoading by state::previewLoading
    var previewText by state::previewText
    var previewImage by state::previewImage
    var previewTruncated by state::previewTruncated
    var previewError by state::previewError

    /** A remote directory that waits for the user to make a save directory selection before starting a recursive download. */
    var pendingDownloadDir by remember { mutableStateOf<String?>(null) }

    /** Single-file download progress (null = no download in progress); data source for the top progress bar banner. */
    var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }

    /** Header search box focus: Automatically focus the search box and bring up the keyboard when expanded. */
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(searching) {
        if (searching) {
            delay(150)
            searchFocusRequester.requestFocus()
        }
    }
    // Recursive search: When input is provided for a keyword, traverse the subtree of the current directory and display the results in a flat format (filename + relative path).
    // Resume normal browsing when keywords are empty; any change to query, path, or session immediately cancels the previous iteration (LaunchedEffect automatically cancels + debounce).
    var searchLoading by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<SftpSearchHit>?>(null) }
    LaunchedEffect(query, path, session) {
        if (query.isBlank()) {
            searchResults = null
            searchLoading = false
            return@LaunchedEffect
        }
        val sc = session
        if (sc == null) {
            searchResults = emptyList()
            searchLoading = false
            return@LaunchedEffect
        }
        searchLoading = true
        delay(300) // Input debounce
        val results =
            withContext(ioDispatcher()) {
                runCatching { searchRecursive(sc, path, query) }.getOrElse { emptyList() }
            }
        searchResults = results
        searchLoading = false
    }

    /** Standardized directory navigation: Add the current path to the browsing history, then toggle between directories. */
    fun navigateTo(newPath: String) {
        if (newPath == path) return
        state.history.add(path)
        path = newPath
        onPathChanged(newPath)
    }

    // SFTP registers only one screen-level return handler and collapses elements strictly according to the visual hierarchy.
    // If preview/multi-select fail to intercept Back, it falls through to the terminal page — landing from the file page straight home.
    PlatformBackHandler(enabled = true) {
        when (
            sftpBackTarget(
                previewOpen = previewEntry != null,
                selectionActive = state.selection.isNotEmpty(),
                searchOpen = searching,
                hasDirectoryHistory = state.history.isNotEmpty(),
            )
        ) {
            SftpBackTarget.CLOSE_PREVIEW -> {
                previewEntry = null
                state.previewRemotePath = null
                state.previewVideoPositionMillis = 0L
                previewText = null
                previewImage = null
                previewError = null
                previewTruncated = false
                previewLoading = false
            }
            SftpBackTarget.CLEAR_SELECTION -> state.selection.clear()
            SftpBackTarget.CLOSE_SEARCH -> {
                searching = false
                query = ""
            }
            SftpBackTarget.PREVIOUS_DIRECTORY -> path = state.history.removeAt(state.history.lastIndex)
            SftpBackTarget.EXIT -> onBack()
        }
    }

    suspend fun reload() {
        // Do not touch the old session during reconnection: During composition, both the reload effect and the auto-reconnect effect will run,
        // If the old connection is dead, failure is inevitable—skip it and reconnect using the new connection (then reload the new composition).
        if (state.reconnecting) return
        val s = session ?: return // Items to be resumed after a process restart: Wait for automatic reconnection and rebuilding, then resume the new composition.
        entries = null
        loadError = null
        try {
            entries = withContext(ioDispatcher()) { s.list(path) }
        } catch (e: Exception) {
            TermLog.w("sftp") { "list failed $path: ${e.message}" }
            loadError = e.message
            entries = emptyList()
        }
    }

    // Reconnection after a disconnection: Entries resumed after a failure of the previous session (connection lost) or a process restart (session=null)
    // entering the tab auto-reconnects once; afterwards the user can tap “Reconnect” on the banner to retry manually.
    // Put the flag in uiState: tab reassembly (session replaced after reconnect) resets remember,
    // Set `uiState` to ensure that the entire session lifecycle is automatically reconnected only once, preventing loops.
    LaunchedEffect(Unit) {
        if ((loadError != null || session == null || state.disconnected) && !state.autoReconnectAttempted) {
            state.autoReconnectAttempted = true
            state.reconnecting = true
            onReconnect()
        }
    }

    fun requestReconnect() {
        if (state.reconnecting) return
        state.reconnecting = true
        onReconnect()
    }

    val pickFile =
        rememberFilePicker { picked ->
            val sc = session ?: return@rememberFilePicker
            // Multiple choice: The selector calls the callback once for each selected file, launching a separate upload coroutine for each (concurrency in uploads).
            scope.launch {
                try {
                    withContext(ioDispatcher()) {
                        var lastEmitted = 0L
                        sc.upload(
                            joinPath(path, picked.name),
                            picked.size,
                            onProgress = { sent, total ->
                                // Throttling is the same as for downloads: update once per 1% (or 64 KB, when the total is unknown).
                                val step = if (total > 0) (total / 100).coerceAtLeast(1) else 64L * 1024
                                if (sent - lastEmitted >= step || (total > 0 && sent >= total)) {
                                    lastEmitted = sent
                                    scope.launch(Dispatchers.Main) {
                                        downloadProgress = DownloadProgress(picked.name, sent, total)
                                    }
                                }
                            },
                        ) { picked.readChunk() }
                    }
                    downloadProgress = null
                    snackbar.showSnackbar(s.sftpUploaded)
                    reload()
                } catch (e: Exception) {
                    downloadProgress = null
                    snackbar.showSnackbar(s.sftpLoadFailed(e.message ?: "upload"))
                }
            }
        }

    // After selecting a save location, the download proceeds in real time; if saving is canceled, no callback is triggered at all (no download traffic is generated).
    val savePicker =
        rememberFileSaver { _, sink ->
            val target = pendingDownload ?: return@rememberFileSaver
            val sc = session ?: return@rememberFileSaver
            val remotePath = joinPath(path, target.name)
            scope.launch {
                try {
                    withContext(ioDispatcher()) {
                        var lastEmitted = 0L
                        sc.download(
                            remotePath,
                            onProgress = { loaded, total ->
                                // Throttling: Update only once per 1% (or 64 KB, when the total is unknown) to avoid frequent reassembly.
                                val step = if (total > 0) (total / 100).coerceAtLeast(1) else 64L * 1024
                                if (loaded - lastEmitted >= step || (total > 0 && loaded >= total)) {
                                    lastEmitted = loaded
                                    scope.launch(Dispatchers.Main) {
                                        downloadProgress = DownloadProgress(target.name, loaded, total)
                                    }
                                }
                            },
                        ) { chunk -> sink.write(chunk) }
                        sink.close()
                    }
                    downloadProgress = null
                    snackbar.showSnackbar(s.sftpDownloaded)
                    showDownloadDoneNotification(s.sftpDownloadComplete, target.name, sink.openUri)
                } catch (e: Exception) {
                    downloadProgress = null
                    try {
                        sink.close()
                    } catch (_: Exception) {
                    }
                    snackbar.showSnackbar(s.sftpDownloadFailed(e.message ?: "download"))
                }
            }
        }

    fun startDownload(entry: SftpEntry) {
        pendingDownload = entry
        savePicker(entry.name)
    }

    /** Text preview loading: Stream-based reading of remote files (up to [PREVIEW_MAX_BYTES]). */
    fun loadTextPreview(
        sc: SftpSession,
        entry: SftpEntry,
    ) {
        scope.launch {
            try {
                val result =
                    withContext(ioDispatcher()) {
                        readSftpPreview(sc, joinPath(path, entry.name))
                    }
                if (previewEntry !== entry) return@launch // If the file is closed or switched to another file: discard the result
                previewText = result.text
                previewTruncated = result.truncated
            } catch (e: SftpPreviewBinaryException) {
                if (previewEntry !== entry) return@launch
                previewError = s.sftpPreviewBinary
            } catch (e: Exception) {
                if (previewEntry !== entry) return@launch
                previewError = s.sftpPreviewFailed(e.message ?: "preview")
            } finally {
                if (previewEntry === entry) previewLoading = false
            }
        }
    }

    /** Preview entry points: Images, videos, and text are loaded separately; results are stored in session-level `uiState`. */
    fun startPreview(entry: SftpEntry) {
        val sc = session ?: return
        previewEntry = entry
        state.previewRemotePath = joinPath(path, entry.name)
        state.previewVideoPositionMillis = 0L
        previewText = null
        previewImage = null
        previewError = null
        previewTruncated = false
        previewLoading = true
        if (fileKindOf(entry.name) == SftpFileKind.VIDEO) {
            previewLoading = false // Native Player buffering and on-demand reading
        } else if (isImageName(entry.name)) {
            scope.launch {
                try {
                    val bytes =
                        withContext(ioDispatcher()) {
                            readSftpPreviewBytes(sc, joinPath(path, entry.name), PREVIEW_IMAGE_MAX_BYTES)
                        }
                    if (previewEntry !== entry) return@launch // If the file is closed or switched to another file: discard
                    val bmp = withContext(ioDispatcher()) { decodeImage(bytes, isSvg = extensionOf(entry.name) == "svg") }
                    if (previewEntry !== entry) return@launch
                    if (bmp == null) {
                        previewError = s.sftpPreviewFailed("decode")
                    } else {
                        previewImage = bmp
                    }
                } catch (e: SftpPreviewTooLargeException) {
                    if (previewEntry !== entry) return@launch
                    previewError = s.sftpPreviewImageTooLarge
                } catch (e: Exception) {
                    if (previewEntry !== entry) return@launch
                    previewError = s.sftpPreviewFailed(e.message ?: "image")
                } finally {
                    if (previewEntry === entry) previewLoading = false
                }
            }
        } else {
            loadTextPreview(sc, entry)
        }
    }

    fun closePreview() {
        previewEntry = null
        state.previewRemotePath = null
        state.previewVideoPositionMillis = 0L
        previewText = null
        previewImage = null
        previewError = null
        previewTruncated = false
        previewLoading = false
    }

    // After a directory save selection, recursively download the current directory / batch download selected files; if saving is canceled, no callback will be triggered.
    val saveDir =
        rememberDirectorySaver { _, sink ->
            val dir = pendingDownloadDir
            val files = state.pendingDownloadMulti
            if (dir == null && files == null) {
                // No targets to download (defensive path): Abort immediately; do not call `sink.close()`——
                // (on an empty directory, calling it would also finalize an empty export)
                return@rememberDirectorySaver
            }
            val sc = session ?: return@rememberDirectorySaver
            scope.launch {
                try {
                    withContext(ioDispatcher()) {
                        if (dir != null) {
                            downloadDir(sc, dir, sink)
                        } else {
                            downloadFilesToSink(sc, files!!, sink)
                        }
                    }
                    sink.close()
                    snackbar.showSnackbar(s.sftpDownloaded)
                } catch (e: Exception) {
                    // Do not call `sink.close()` on failure: export only when all operations are successful, which prevents the export of incomplete directories.
                    snackbar.showSnackbar(s.sftpDownloadFailed(e.message ?: "download"))
                }
            }
        }

    fun startDownloadDir() {
        val remotePath = path
        val name = remotePath.substringAfterLast('/').ifBlank { "root" }
        pendingDownloadDir = remotePath
        saveDir(name)
    }

    // First entry (path uninitialized): realpath parses the real user's home directory (~),
    // If a failure occurs, fall back to /home/{user}; if that fails, fall back to the root directory; switching back does not reset.
    // key contains session: Resume entry (null); re-execute after successful reconnection (non-null)——
    // Otherwise, "home" is never parsed when the path is empty and skipped for the first time.
    LaunchedEffect(session) {
        if (state.path.isEmpty() && session != null) {
            path =
                withContext(ioDispatcher()) {
                    val home =
                        runCatching { session.home() }
                            .getOrNull()
                            ?.let { raw ->
                                if (raw.length > 1) raw.trimEnd('/') else raw
                            }?.takeIf { it.isNotBlank() }
                    when {
                        home != null -> home
                        runCatching { session.list("/home/${host.username}") }.isSuccess -> "/home/${host.username}"
                        else -> "/"
                    }
                }
        }
    }
    // key contains session: After a successful reconnect (null → new object), it must be reloaded—
    // The first reload of a composition may be skipped by "reconnecting" based solely on changes to path or sort.
    // ...otherwise it stays “Connecting…” forever and only recovers on tab switch.
    LaunchedEffect(path, sort, showHidden, session) {
        if (path.isNotEmpty()) reload()
    }

    // Breadcrumbs dynamically show the current path: always a maximum of two levels deep
    // - 1 section: User (root) or A
    // - 2 segments: A > B
    // - 3+ segments: ... > B (… tap to drop down all parent directories)
    val parts = path.split('/').filter { it.isNotBlank() }
    val displayParts = if (parts.firstOrNull() == "home") listOf(s.sftpUser) + parts.drop(1) else parts
    val breadcrumbs =
        if (displayParts.size > 2) {
            listOf("...", displayParts.last())
        } else {
            displayParts.ifEmpty { listOf(s.sftpUser) }
        }
    // Collapsed parent directories (all levels before the last segment)
    val hiddenParents = if (displayParts.size > 2) displayParts.dropLast(1) else emptyList()

    fun pathForBreadcrumb(index: Int): String {
        val mapped = displayParts.take(index + 1).map { if (it == s.sftpUser) "home" else it }
        return "/" + mapped.joinToString("/")
    }
    val visible =
        (entries ?: emptyList())
            .filter { showHidden || !it.isHidden }
            .filter { matchesQuery(it.name, query) }
            .let { list ->
                when (sort) {
                    SftpSort.NAME -> list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    SftpSort.DATE -> list.sortedWith(compareByDescending { it.modifiedAt })
                    SftpSort.SIZE -> list.sortedWith(compareBy({ !it.isDirectory }, { it.size }))
                    SftpSort.KIND ->
                        list.sortedWith(
                            compareBy({ !it.isDirectory }, { it.name.substringAfterLast('.').lowercase() }),
                        )
                }
            }

    // ---- Select Multiple / Delete / Rename / refresh ----
    fun selecting() = state.selection.isNotEmpty()

    fun enterSelection(name: String) {
        state.selection.clear()
        state.selection.add(name)
    }

    fun toggleSelect(name: String) {
        if (name in state.selection) state.selection.remove(name) else state.selection.add(name)
    }

    fun clearSelection() = state.selection.clear()

    fun selectAllVisible() {
        state.selection.clear()
        visible.forEach { state.selection.add(it.name) }
    }

    /** Delete confirmation (shared between single-file menu and multi-select bottom bar). */
    fun confirmDelete(targets: List<SftpEntry>) {
        state.deleteTargets = targets
    }

    /** Perform deletion (recursively; the platform handles internal directories); after success, refresh and exit the multi-select mode. */
    fun doDelete(targets: List<SftpEntry>) {
        val sc = session ?: return
        scope.launch {
            try {
                withContext(ioDispatcher()) {
                    targets.forEach { sc.delete(joinPath(path, it.name)) }
                }
                clearSelection()
                reload()
                snackbar.showSnackbar(
                    s.sftpExt
                        .deleteConfirm(targets.size)
                        .removeSuffix("?")
                        .removeSuffix("？"),
                )
            } catch (e: Exception) {
                snackbar.showSnackbar(s.sftpExt.delete + " " + (e.message ?: ""))
            }
        }
    }

    /** Perform renaming. */
    fun doRename(
        entry: SftpEntry,
        newName: String,
    ) {
        val sc = session ?: return
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == entry.name) {
            state.renameTarget = null
            return
        }
        scope.launch {
            try {
                withContext(ioDispatcher()) {
                    sc.rename(joinPath(path, entry.name), joinPath(path, trimmed))
                }
                state.renameTarget = null
                reload()
            } catch (e: Exception) {
                snackbar.showSnackbar(s.sftpExt.rename + " " + (e.message ?: ""))
            }
        }
    }

    /** Manually refresh or use the pull-to-refresh gesture to refresh the current directory. */
    fun refreshNow() {
        if (state.refreshing) return
        state.refreshing = true
        scope.launch {
            reload()
            state.refreshing = false
        }
    }

    /** Bookmarks dialog switch (access via MoreMenu). */
    var favoritesOpen by remember { mutableStateOf(false) }

    /** Switch the preview panel ⋮ action menu. */
    var previewMenu by remember { mutableStateOf(false) }

    /** Bookmark/Cancel the current directory (star icon in the header); persistence is changed. */
    fun toggleFavorite(p: String) {
        if (p in state.favorites) state.favorites.remove(p) else state.favorites.add(p)
        onFavoritesChanged(state.favorites.toList())
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            // Multi-select toolbar at the top: Count + select all + Exit (immediately above the header)
            if (selecting()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        s.sftpExt.selected(state.selection.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            val selectedPaths =
                                visible
                                    .filter { it.name in state.selection }
                                    .map { joinPath(path, it.name) }
                            if (selectedPaths.isNotEmpty()) {
                                clearSelection()
                                sftpAgentLaunchContext(
                                    selectedPaths,
                                    path,
                                    s.nativeAgents.fileContextPrompt,
                                )?.let(onAskAgent)
                            }
                        },
                    ) {
                        Icon(
                            Icons.Filled.SmartToy,
                            contentDescription = s.nativeAgents.askAboutFiles,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    TextButton(onClick = { selectAllVisible() }) {
                        Text(s.sftpExt.selectAll, style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = { clearSelection() }) {
                        Icon(Icons.Filled.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            // Disconnection banner (red + reconnect button); change the canvas centering indicator while performing a reconnection
            // (Same as the terminal screen; see ConnectingIndicator)
            val bannerText =
                when {
                    state.reconnecting -> null
                    state.disconnected || loadError != null -> s.sftpDisconnected
                    else -> null
                }
            if (bannerText != null) {
                val bannerColor = StatusColors.Error
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(bannerColor.copy(alpha = 0.12f))
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        bannerText,
                        color = bannerColor,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                    )
                    if (!state.reconnecting) {
                        TextButton(onClick = { requestReconnect() }) {
                            Text(s.sftpReconnect, color = bannerColor, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            // Simplify the header: no borders, small font size, and icons in the terminal’s foreground color (visible against a dark background).
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Breadcrumb navigation is always displayed on the left (tap to navigate; tap “…” to expand and view parent levels).
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    breadcrumbs.forEachIndexed { i, crumb ->
                        if (crumb == "...") {
                            Box {
                                Text(
                                    "...",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontFamily = monospaceFontFamily(),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier =
                                        Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickable { showParents = true }
                                            .padding(horizontal = 2.dp),
                                )
                                DropdownMenu(
                                    expanded = showParents,
                                    onDismissRequest = { showParents = false },
                                ) {
                                    hiddenParents.forEach { parent ->
                                        DropdownMenuItem(
                                            text = { Text(parent, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            leadingIcon = {
                                                Icon(
                                                    painterResource(Res.drawable.folder),
                                                    null,
                                                    modifier = Modifier.size(18.dp),
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                            },
                                            onClick = {
                                                showParents = false
                                                navigateTo(pathForBreadcrumb(displayParts.indexOf(parent)))
                                            },
                                        )
                                    }
                                }
                            }
                            if (i < breadcrumbs.lastIndex) {
                                Text(
                                    " > ",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Text(
                                crumb,
                                style = MaterialTheme.typography.labelLarge,
                                fontFamily = monospaceFontFamily(),
                                color =
                                    if (i == breadcrumbs.lastIndex) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier =
                                    Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { navigateTo(pathForBreadcrumb(displayParts.indexOf(crumb))) }
                                        .padding(horizontal = 2.dp),
                            )
                            if (i < breadcrumbs.lastIndex) {
                                Text(
                                    " > ",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                // Bookmark the current directory (star): View/navigate to the bookmarks list via the “Bookmarks” option in the three-dot menu.
                IconButton(onClick = { toggleFavorite(path) }) {
                    Icon(
                        if (path in state.favorites) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription =
                            if (path in
                                state.favorites
                            ) {
                                s.sftpExt.favoriteRemove
                            } else {
                                s.sftpExt.favoriteAdd
                            },
                        tint =
                            if (path in state.favorites) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                }
                TextButton(onClick = { pickFile() }) {
                    Icon(
                        Icons.Filled.Upload,
                        null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(s.sftpUpload, color = MaterialTheme.colorScheme.onSurface)
                }
                SftpMoreMenu(
                    sort = sort,
                    showHidden = showHidden,
                    tint = MaterialTheme.colorScheme.onSurface,
                    isFavorite = path in state.favorites,
                    onToggleFavorite = { toggleFavorite(path) },
                    onOpenFavorites = { favoritesOpen = true },
                    onRefresh = { refreshNow() },
                    onSort = { sort = it },
                    onToggleHidden = { showHidden = !showHidden },
                    onNewFolder = { newFolderDialog = true },
                    onCopyPath = {
                        clipboard.setText(AnnotatedString(path))
                        scope.launch { snackbar.showSnackbar(s.sftpCopied) }
                    },
                    onChangeDownload = {
                        startDownloadDir()
                    },
                )
                // Search switch: Toggle icon (Search ⇄ Off); the search box expands vertically at the bottom
                IconButton(
                    onClick = {
                        if (searching) {
                            searching = false
                            query = ""
                        } else {
                            searching = true
                        }
                    },
                ) {
                    Icon(
                        if (searching) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = if (searching) s.navBack else s.sftpSearch,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            // Search bar: Same design as the Host/Connection page—tap the header icon to expand vertically + fade in, with automatic focus
            AnimatedVisibility(
                visible = searching,
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
                    placeholder = { Text(s.sftpSearch) },
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
            when {
                // Initial connection/directory loading (entries not ready): Do not display tooltips in the list area,
                // Unify the use of the outer-layer ConnectingIndicator capsule (same as the terminal screen).
                entries == null -> Box(Modifier.fillMaxSize())
                // Disconnect/Loading Failure: Main tooltip in the top banner (red + reconnect button, same as terminal style),
                // Leave only neutral secondary tooltips in the content area; do not repeat the red error text.
                loadError != null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(s.sftpDisconnected, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                // Recursive search: Display cross-directory results (file name + relative path) when performing input.
                query.isNotBlank() ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
                    ) {
                        when {
                            searchLoading ->
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.size(8.dp))
                                        Text(
                                            s.sftpSearching,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            searchResults.isNullOrEmpty() ->
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        s.sftpNoMatch,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            else ->
                                LazyColumn(Modifier.fillMaxSize()) {
                                    items(searchResults.orEmpty(), key = { it.fullPath }) { hit ->
                                        SftpSearchRow(
                                            hit = hit,
                                            onClick = {
                                                val target =
                                                    if (hit.isDirectory) {
                                                        hit.fullPath
                                                    } else {
                                                        hit.fullPath.substringBeforeLast('/', "/").ifBlank { "/" }
                                                    }
                                                query = ""
                                                searching = false
                                                navigateTo(target)
                                            },
                                        )
                                    }
                                }
                        }
                    }
                else ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
                    ) {
                        if (visible.isEmpty()) {
                            // Empty state
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Filled.FolderOpen,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(40.dp),
                                    )
                                    Spacer(Modifier.size(10.dp))
                                    Text(
                                        s.sftpExt.empty,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        } else {
                            // Pull-to-refresh + List (sorted by DATE and grouped by Today/This Week/Earlier)
                            PullToRefreshBox(
                                isRefreshing = state.refreshing,
                                onRefresh = { refreshNow() },
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                LazyColumn(Modifier.fillMaxSize()) {
                                    if (path != "/") {
                                        item("..") {
                                            SftpRowItem(
                                                name = "..",
                                                subtitle = "",
                                                time = "",
                                                isDirectory = true,
                                                onClick = {
                                                    if (selecting()) {
                                                        clearSelection()
                                                    } else {
                                                        navigateTo(path.substringBeforeLast('/', "/").ifBlank { "/" })
                                                    }
                                                },
                                            )
                                        }
                                    }
                                    if (sort == SftpSort.DATE && !selecting()) {
                                        // Time group headers: Today / This Week / Earlier (unknown times are grouped under “Earlier”)
                                        val buckets = linkedMapOf<String, MutableList<SftpEntry>>()
                                        visible.forEach { e ->
                                            buckets.getOrPut(dateBucket(e.modifiedAt, s)) { mutableListOf() }.add(e)
                                        }
                                        buckets.forEach { (bucket, list) ->
                                            item(key = "hdr-$bucket") {
                                                Text(
                                                    bucket,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier =
                                                        Modifier.padding(
                                                            start = 14.dp,
                                                            top = 10.dp,
                                                            bottom = 2.dp,
                                                        ),
                                                )
                                            }
                                            items(list, key = { it.name }) { entry ->
                                                EntryRow(
                                                    entry,
                                                    selecting(),
                                                    state.selection,
                                                    path,
                                                    onToggle = { toggleSelect(it) },
                                                    onEnterSelection = { enterSelection(it) },
                                                    onNavigate = { navigateTo(it) },
                                                    onPreview = { startPreview(it) },
                                                )
                                            }
                                        }
                                    } else {
                                        items(visible, key = { it.name }) { entry ->
                                            EntryRow(
                                                entry,
                                                selecting(),
                                                state.selection,
                                                path,
                                                onToggle = { toggleSelect(it) },
                                                onEnterSelection = { enterSelection(it) },
                                                onNavigate = { navigateTo(it) },
                                                onPreview = { startPreview(it) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
        }
        // Multi-select bottom action bar: Download / Delete / Copy Path / Cancel (overlapping the bottom of the list)
        if (selecting()) {
            val selEntries = visible.filter { it.name in state.selection }
            Surface(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    TextButton(
                        onClick = {
                            state.pendingDownloadMulti = selEntries.map { joinPath(path, it.name) }
                            saveDir("selection")
                        },
                        enabled = selEntries.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(s.sftpDownload)
                    }
                    TextButton(onClick = { confirmDelete(selEntries) }, enabled = selEntries.isNotEmpty()) {
                        Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(s.sftpExt.delete)
                    }
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(selEntries.joinToString(" ") { joinPath(path, it.name) }))
                            clearSelection()
                            scope.launch { snackbar.showSnackbar(s.sftpCopied) }
                        },
                        enabled = selEntries.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(s.sftpCopyPath)
                    }
                    TextButton(onClick = { clearSelection() }) {
                        Text(s.terminalCancel)
                    }
                }
            }
        }

        // Single-file download progress: Floating card in the lower-right corner (same as the TransferProgressCard used for terminal uploads)
        val downloading = downloadProgress
        if (downloading != null) {
            TransferProgressCard(
                title = downloading.name,
                progress = if (downloading.total > 0) downloading.loaded.toFloat() / downloading.total else 0f,
                percent = if (downloading.total > 0) "${downloading.loaded * 100 / downloading.total}%" else "",
                subtitle = s.sftpDownload,
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .zIndex(10f),
            )
        }

        // ---- Rename / Delete / Favorites Dialog ----
        state.renameTarget?.let { entry ->
            var newName by remember(entry.name) { mutableStateOf(entry.name) }
            AlertDialog(
                onDismissRequest = { state.renameTarget = null },
                title = { Text(s.sftpExt.renameTitle) },
                text = {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { doRename(entry, newName) }) { Text(s.terminalConfirm) }
                },
                dismissButton = {
                    TextButton(onClick = { state.renameTarget = null }) { Text(s.terminalCancel) }
                },
            )
        }
        state.deleteTargets?.let { targets ->
            AlertDialog(
                onDismissRequest = { state.deleteTargets = null },
                title = { Text(s.sftpExt.deleteConfirm(targets.size)) },
                text = { Text(s.sftpExt.deleteConfirmBody) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            state.deleteTargets = null
                            doDelete(targets)
                        },
                    ) { Text(s.terminalConfirm) }
                },
                dismissButton = {
                    TextButton(onClick = { state.deleteTargets = null }) { Text(s.terminalCancel) }
                },
            )
        }
        if (favoritesOpen) {
            AlertDialog(
                onDismissRequest = { favoritesOpen = false },
                title = { Text(s.sftpExt.favoritesTitle) },
                text = {
                    if (state.favorites.isEmpty()) {
                        Text(
                            s.sftpExt.favoritesEmpty,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column {
                            state.favorites.toList().forEach { fav ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            favoritesOpen = false
                                            navigateTo(fav)
                                        }.padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.FolderOpen,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        fav,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                                    )
                                    IconButton(onClick = {
                                        state.favorites.remove(fav)
                                        onFavoritesChanged(state.favorites.toList())
                                    }, modifier = Modifier.size(28.dp)) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { favoritesOpen = false }) { Text(s.terminalCancel) }
                },
            )
        }

        // File preview overlay: Fullscreen modal covering the list; header (name + size + copy + close) + monospaced text scroll area.
        // Preview state is stored in session-level `uiState`: when you switch tabs, leave, and return, the preview panel remains exactly as it was.
        previewEntry?.let { entry ->
            val isVideo = fileKindOf(entry.name) == SftpFileKind.VIDEO
            val lines = remember(previewText) { previewText.orEmpty().lines() }
            val err = previewError // Delegated properties cannot be smart-cast; retrieve the local value instead.
            // Image preview: true = screen-optimized; false = original pixels (scrollable)
            var imageFit by remember(entry.name) { mutableStateOf(true) }
            // Markdown: Toggle between rendered and source code views (default rendered mode); show only md/markdown files
            val isMarkdown = extensionOf(entry.name) in setOf("md", "markdown")
            var mdRender by remember(entry.name) { mutableStateOf(true) }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            rememberVectorPainter(Icons.AutoMirrored.Filled.InsertDriveFile),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                formatSize(entry.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Copy All: The preview text is loaded in memory; click to copy to the clipboard (SelectionContainer
                        // (In LazyColumn, you can only make in-row selections; use this button as a fallback for copying long text.)
                        val previewCopyable = previewText // Delegated properties cannot be smart-casted
                        if (previewCopyable != null) {
                            IconButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(previewCopyable))
                                    scope.launch { snackbar.showSnackbar(s.sftpPreviewCopied) }
                                },
                            ) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = s.sftpPreviewCopied,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        // Image preview: Toggle between resized and original sizes (scroll to view details in the original size)
                        if (previewImage != null) {
                            IconButton(onClick = { imageFit = !imageFit }) {
                                Icon(
                                    if (imageFit) Icons.Filled.ZoomIn else Icons.Filled.ZoomOut,
                                    contentDescription = s.sftpPreviewZoom,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        // Toggle between Markdown rendering and source code
                        if (isMarkdown && previewText != null) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(2.dp),
                            ) {
                                TextButton(
                                    onClick = { mdRender = true },
                                    modifier = Modifier.height(30.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) {
                                    Text(
                                        "预览",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (mdRender) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(
                                    onClick = { mdRender = false },
                                    modifier = Modifier.height(30.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) {
                                    Text(
                                        "源码",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (!mdRender) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        // More actions: Download / Rename / Delete / Copy Path (file operations entry point,
                        // (List clicking has been changed to direct preview; operations are now centralized here + multi-select bar)
                        Box {
                            IconButton(onClick = { previewMenu = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            DropdownMenu(expanded = previewMenu, onDismissRequest = { previewMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(s.sftpDownload) },
                                    leadingIcon = { Icon(Icons.Filled.Download, null) },
                                    onClick = {
                                        previewMenu = false
                                        startDownload(entry)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(s.sftpExt.rename) },
                                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                    onClick = {
                                        previewMenu = false
                                        state.renameTarget = entry
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(s.sftpExt.delete) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                    onClick = {
                                        previewMenu = false
                                        confirmDelete(listOf(entry))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(s.sftpCopyPath) },
                                    leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                                    onClick = {
                                        previewMenu = false
                                        clipboard.setText(AnnotatedString(joinPath(path, entry.name)))
                                        scope.launch { snackbar.showSnackbar(s.sftpCopied) }
                                    },
                                )
                            }
                        }
                        IconButton(onClick = { closePreview() }) {
                            Icon(Icons.Filled.Close, contentDescription = s.navBack)
                        }
                    }
                    HorizontalDivider()
                    when {
                        previewLoading ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                            }
                        // Image preview: Centered on a black background; in adaptive mode, the entire image is visible; in original mode, scroll to view details
                        previewImage != null ->
                            Box(
                                Modifier.fillMaxSize().background(Color.Black),
                            ) {
                                if (imageFit) {
                                    Image(
                                        previewImage!!,
                                        contentDescription = entry.name,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit,
                                    )
                                } else {
                                    val hScroll = rememberScrollState()
                                    val vScroll = rememberScrollState()
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .verticalScroll(vScroll)
                                            .horizontalScroll(hScroll),
                                    ) {
                                        Image(
                                            previewImage!!,
                                            contentDescription = entry.name,
                                            modifier = Modifier.size(previewImage!!.width.dp, previewImage!!.height.dp),
                                            contentScale = ContentScale.FillBounds,
                                        )
                                    }
                                }
                            }
                        err != null ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    err,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 24.dp),
                                )
                            }
                        isVideo && session != null ->
                            // Two file tabs can occupy the same composition position; a separate key prevents the old player from
                            // onDispose writes the playback position to another session that has just been switched to.
                            key(state, session, state.previewRemotePath) {
                                SftpVideoPlayer(
                                    session = session,
                                    remotePath = state.previewRemotePath ?: joinPath(path, entry.name),
                                    initialPositionMillis = state.previewVideoPositionMillis,
                                    onPositionChanged = { if (previewEntry === entry) state.previewVideoPositionMillis = it },
                                    onError = { if (previewEntry === entry) previewError = s.sftpVideoFailed },
                                    modifier = Modifier.fillMaxSize().background(Color.Black),
                                )
                            }
                        isVideo ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        else ->
                            Column(Modifier.fillMaxSize()) {
                                if (previewTruncated) {
                                    Text(
                                        s.sftpPreviewTruncated(formatSize(PREVIEW_MAX_BYTES.toLong())),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f))
                                                .padding(horizontal = 12.dp, vertical = 6.dp),
                                    )
                                }
                                val srcText = previewText.orEmpty()
                                if (isMarkdown && mdRender) {
                                    // Rendering mode: Markdown preview (copyable), vertical scrolling
                                    Column(
                                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                    ) {
                                        SelectionContainer {
                                            Text(
                                                renderMarkdown(
                                                    srcText,
                                                    baseFontSize = 14.sp,
                                                    codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                                    linkColor = MaterialTheme.colorScheme.primary,
                                                ),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                            )
                                        }
                                    }
                                } else {
                                    // Source code mode: line-by-line monospaced (consistent with Markdown and other text files)
                                    LazyColumn(
                                        Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                    ) {
                                        items(lines.size) { i ->
                                            SelectionContainer {
                                                Text(
                                                    lines[i],
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontFamily = monospaceFontFamily(),
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                    }
                }
            }
        }

        // Place SnackbarHost after the preview layer (highest z-order): in preview mode, hints like “Copy successful” aren't covered by the panel.
        // Multi-select bottom action bar: Download / Delete / Copy Path / Cancel (overlapping the bottom of the list)
        if (selecting()) {
            val selEntries = visible.filter { it.name in state.selection }
            Surface(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    TextButton(
                        onClick = {
                            state.pendingDownloadMulti = selEntries.map { joinPath(path, it.name) }
                            saveDir("selection")
                        },
                        enabled = selEntries.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(s.sftpDownload)
                    }
                    TextButton(onClick = { confirmDelete(selEntries) }, enabled = selEntries.isNotEmpty()) {
                        Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(s.sftpExt.delete)
                    }
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(selEntries.joinToString(" ") { joinPath(path, it.name) }))
                            clearSelection()
                            scope.launch { snackbar.showSnackbar(s.sftpCopied) }
                        },
                        enabled = selEntries.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(s.sftpCopyPath)
                    }
                    TextButton(onClick = { clearSelection() }) {
                        Text(s.terminalCancel)
                    }
                }
            }
        }

        MacodesSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))

        // The initial connection (list not loading) and reconnection after a disconnection share the same centered capsule (same as the terminal screen, with fade-in and fade-out effects):
        // The plain text “Connection…” no longer appears on the same page, and the visual presentation of the connection status is completely consistent across both pages.
        ConnectingIndicator(
            visible = state.reconnecting || entries == null,
            text = if (state.reconnecting) s.sftpReconnecting else s.sftpConnecting,
            modifier = Modifier.align(Alignment.Center),
        )
    }

    if (newFolderDialog) {
        NewFolderDialog(
            onConfirm = { name ->
                newFolderDialog = false
                val sc = session
                if (sc != null) {
                    scope.launch {
                        try {
                            withContext(ioDispatcher()) {
                                sc.mkdir(joinPath(path, name))
                            }
                            reload()
                        } catch (e: Exception) {
                            snackbar.showSnackbar(s.sftpLoadFailed(e.message ?: "mkdir"))
                        }
                    }
                }
            },
            onDismiss = { newFolderDialog = false },
        )
    }
}

/** Three-dot menu: New File Folder / Sort / Favorites / Refresh / Download Directory / Copy Path / Hidden Files. */
@Composable
private fun SftpMoreMenu(
    sort: SftpSort,
    showHidden: Boolean,
    tint: Color,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenFavorites: () -> Unit,
    onRefresh: () -> Unit,
    onSort: (SftpSort) -> Unit,
    onToggleHidden: () -> Unit,
    onNewFolder: () -> Unit,
    onCopyPath: () -> Unit,
    onChangeDownload: () -> Unit,
) {
    val s = LocalAppStrings.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = s.navMore, tint = tint)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(s.sftpNewFolder) },
                leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                onClick = {
                    open = false
                    onNewFolder()
                },
            )
            DropdownMenuItem(
                text = { Text(if (isFavorite) s.sftpExt.favoriteRemove else s.sftpExt.favoriteAdd) },
                leadingIcon = { Icon(if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder, null) },
                onClick = {
                    open = false
                    onToggleFavorite()
                },
            )
            DropdownMenuItem(
                text = { Text(s.sftpExt.refresh) },
                leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                onClick = {
                    open = false
                    onRefresh()
                },
            )
            HorizontalDivider()
            SftpSortItem(s.sftpSortName, Icons.Filled.SortByAlpha, sort == SftpSort.NAME) { onSort(SftpSort.NAME) }
            SftpSortItem(s.sftpSortDate, Icons.Filled.Schedule, sort == SftpSort.DATE) { onSort(SftpSort.DATE) }
            SftpSortItem(s.sftpSortSize, Icons.Filled.DataUsage, sort == SftpSort.SIZE) { onSort(SftpSort.SIZE) }
            SftpSortItem(s.sftpSortKind, Icons.Filled.Description, sort == SftpSort.KIND) { onSort(SftpSort.KIND) }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(s.sftpChangeDownload) },
                leadingIcon = { Icon(Icons.Filled.Download, null) },
                onClick = {
                    open = false
                    onChangeDownload()
                },
            )
            DropdownMenuItem(
                text = { Text(s.sftpCopyPath) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                onClick = {
                    open = false
                    onCopyPath()
                },
            )
            DropdownMenuItem(
                text = { Text(s.sftpHiddenFiles) },
                leadingIcon = { Icon(Icons.Filled.Visibility, null) },
                trailingIcon = { if (showHidden) Icon(Icons.Filled.Check, null) },
                onClick = {
                    open = false
                    onToggleHidden()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(s.sftpExt.favoritesTitle) },
                leadingIcon = { Icon(Icons.Filled.Star, null) },
                onClick = {
                    open = false
                    onOpenFavorites()
                },
            )
        }
    }
}

@Composable
private fun SftpSortItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, null) },
        trailingIcon = { if (selected) Icon(Icons.Filled.Check, null) },
        onClick = onClick,
    )
}

/** File/folder list rows: icon + name (directory = permissions / file = size subtitle) + time;
 *  Supports multi-select mode and long press (to enter multi-select mode). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SftpRowItem(
    name: String,
    subtitle: String,
    time: String,
    isDirectory: Boolean,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter =
                if (isDirectory) {
                    painterResource(Res.drawable.folder)
                } else {
                    rememberVectorPainter(fileKindIcon(fileKindOf(name)))
                },
            contentDescription = null,
            // Use the theme color (emerald green) for folders; use the type-specific color for files (ensuring small linear icons remain clear while retaining color distinctions).
            tint =
                if (isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    fileKindColor(fileKindOf(name))
                },
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = monospaceFontFamily(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (time.isNotEmpty()) {
            Text(
                time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun NewFolderDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalAppStrings.current
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.sftpNewFolder) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(s.sftpFolderName) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(s.sftpCreate)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.terminalCancel) } },
    )
}

internal fun formatTime(millis: Long): String {
    if (millis <= 0L) return ""
    val dt = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())

    fun p(n: Int) = n.toString().padStart(2, '0')
    return "${dt.year}-${p(dt.monthNumber)}-${p(dt.dayOfMonth)} ${p(dt.hour)}:${p(dt.minute)}"
}

/**
 * SFTP file name matching: multiple keywords separated by spaces (AND relationship); keywords containing `*`/`?` are matched as wildcards,
 * others match as case-insensitive substring match. Examples: `*.log`, `conf nginx`, `readme?`.
 */
internal fun matchesQuery(
    name: String,
    query: String,
): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return q.split(Regex("\\s+")).all { part ->
        if (part.any { it == '*' || it == '?' }) {
            globMatch(name, part)
        } else {
            name.contains(part, ignoreCase = true)
        }
    }
}

internal fun globMatch(
    name: String,
    pattern: String,
): Boolean {
    val regex =
        buildString {
            append('^')
            for (ch in pattern) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(ch.toString()))
                }
            }
            append('$')
        }
    return Regex(regex, RegexOption.IGNORE_CASE).containsMatchIn(name)
}

/** Recursive search results: file name + full remote path + path relative to the current directory. */
data class SftpSearchHit(
    val name: String,
    val fullPath: String,
    val relPath: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long,
)

/** Single-file download progress (data source for the top progress bar banner); total=0 indicates that the server did not report the file size. */
data class DownloadProgress(
    val name: String,
    val loaded: Long,
    val total: Long,
)

/**
 * Recursive search: Traverse the directory tree starting from [root] and return entries whose file names match [query].
 * (Match using [matchesQuery]: multiple keywords AND + glob). Depth-first stack traversal;
 * [maxDepth] limits the depth, and [maxResults] limits the number of results, to prevent excessively deep directories or a massive number of results from crashing the session.
 * If a single directory list fails (permission/disconnect), skip that subtree and continue; each directory level responds to coroutine cancellation.
 */
suspend fun searchRecursive(
    session: SftpSession,
    root: String,
    query: String,
    maxDepth: Int = 8,
    maxResults: Int = 500,
): List<SftpSearchHit> {
    val results = ArrayList<SftpSearchHit>()
    val stack = ArrayDeque<Triple<String, String, Int>>()
    stack.addLast(Triple(root, "", 0))
    while (stack.isNotEmpty() && results.size < maxResults) {
        currentCoroutineContext().ensureActive()
        val (dir, rel, depth) = stack.removeLast()
        val entries = runCatching { session.list(dir) }.getOrNull() ?: continue
        for (e in entries) {
            if (results.size >= maxResults) break
            if (e.name == "." || e.name == "..") continue
            val childRel = if (rel.isEmpty()) e.name else "$rel/${e.name}"
            val childPath = joinPath(dir, e.name)
            if (matchesQuery(e.name, query)) {
                results.add(SftpSearchHit(e.name, childPath, childRel, e.isDirectory, e.size, e.modifiedAt))
            }
            if (e.isDirectory && depth < maxDepth) {
                stack.addLast(Triple(childPath, childRel, depth + 1))
            }
        }
    }
    return results
}

/** Recursive search results row: File/directory icon + name + relative path (highlighted in the primary color), with the time displayed in the lower-right corner. */
@Composable
private fun SftpSearchRow(
    hit: SftpSearchHit,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter =
                if (hit.isDirectory) {
                    painterResource(Res.drawable.folder)
                } else {
                    rememberVectorPainter(fileKindIcon(fileKindOf(hit.name)))
                },
            contentDescription = null,
            tint =
                if (hit.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    fileKindColor(fileKindOf(hit.name))
                },
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                hit.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                hit.relPath,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = monospaceFontFamily(),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (hit.modifiedAt > 0) {
            Text(
                formatTime(hit.modifiedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }
}

// ---------- File Type Recognition (List Icon + Preview Routing) ----------

/** SFTP file types: Determine the list icons and preview methods. */
enum class SftpFileKind {
    IMAGE,
    VIDEO,
    AUDIO,
    ARCHIVE,
    APK,
    DOC,
    SHEET,
    SLIDES,
    EXEC,
    FONT,
    DB,
    DISK,
    KEY,
    MD,
    TORRENT,
    CONFIG,
    CODE,
    TEXT,
    PDF,
    OTHER,
}

internal fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

/** Image formats that can be previewed online (platform decoding: Android BitmapFactory; GIFs show the first frame). */
internal val PREVIEW_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")

/** Image files: Use the bitmap preview branch (the NUL detection in the text preview may incorrectly flag images). */
internal fun isImageName(name: String): Boolean = extensionOf(name) in PREVIEW_IMAGE_EXTENSIONS

/** Identify file types by extension (used for list icons); unknown types are classified as OTHER. */
internal fun fileKindOf(name: String): SftpFileKind =
    when (extensionOf(name)) {
        in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "ico", "avif") -> SftpFileKind.IMAGE
        in setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "ts", "m2ts") -> SftpFileKind.VIDEO
        in setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "mid", "wma") -> SftpFileKind.AUDIO
        in
        setOf(
            "zip",
            "tar",
            "gz",
            "bz2",
            "xz",
            "7z",
            "rar",
            "tgz",
            "zst",
            "tar.gz",
            "tar.bz2",
            "tar.xz",
        ),
        -> SftpFileKind.ARCHIVE
        in setOf("iso", "img") -> SftpFileKind.DISK
        in setOf("doc", "docx", "odt", "rtf", "pages", "wps") -> SftpFileKind.DOC
        in setOf("xls", "xlsx", "ods", "numbers", "et") -> SftpFileKind.SHEET
        in setOf("ppt", "pptx", "odp", "key", "dps") -> SftpFileKind.SLIDES
        in setOf("exe", "msi", "deb", "rpm", "appimage", "app", "bat", "cmd", "com", "dmg", "pkg") -> SftpFileKind.EXEC
        "apk" -> SftpFileKind.APK
        in setOf("pem", "key", "crt", "cer", "p12", "pfx", "der", "jks") -> SftpFileKind.KEY
        "torrent" -> SftpFileKind.TORRENT
        in
        setOf(
            "env",
            "yml",
            "yaml",
            "toml",
            "ini",
            "conf",
            "properties",
            "cfg",
            "editorconfig",
            "gitconfig",
        ),
        -> SftpFileKind.CONFIG
        in setOf("ttf", "otf", "woff", "woff2", "eot") -> SftpFileKind.FONT
        in setOf("db", "sqlite", "sqlite3", "mdb") -> SftpFileKind.DB
        in
        setOf(
            "kt",
            "kts",
            "java",
            "c",
            "h",
            "cpp",
            "hpp",
            "cc",
            "py",
            "js",
            "ts",
            "tsx",
            "jsx",
            "go",
            "rs",
            "swift",
            "sh",
            "bash",
            "zsh",
            "rb",
            "php",
            "sql",
            "html",
            "css",
            "scss",
            "xml",
            "yml",
            "yaml",
            "json",
            "toml",
            "gradle",
            "properties",
            "ini",
            "conf",
            "cfg",
            "dockerfile",
            "makefile",
            "lock",
            "patch",
            "diff",
            "vue",
            "svelte",
            "dart",
            "lua",
            "pl",
            "r",
        ),
        -> SftpFileKind.CODE
        in setOf("md", "markdown") -> SftpFileKind.MD
        in setOf("txt", "log", "rst", "adoc", "text", "nfo") -> SftpFileKind.TEXT
        "pdf" -> SftpFileKind.PDF
        else -> SftpFileKind.OTHER
    }

/** File type icon (material-icons-extended linear icons; tint is provided by the caller based on type color/theme color). */
@Composable
private fun fileKindIcon(kind: SftpFileKind): ImageVector =
    when (kind) {
        SftpFileKind.IMAGE -> Icons.Filled.Image
        SftpFileKind.VIDEO -> Icons.Filled.VideoFile
        SftpFileKind.AUDIO -> Icons.Filled.MusicNote
        SftpFileKind.ARCHIVE -> Icons.Filled.Archive
        SftpFileKind.APK -> Icons.Filled.Android
        SftpFileKind.DOC -> Icons.Filled.Description
        SftpFileKind.SHEET -> Icons.Filled.TableChart
        SftpFileKind.SLIDES -> Icons.Filled.Slideshow
        SftpFileKind.EXEC -> Icons.Filled.PlayArrow
        SftpFileKind.FONT -> Icons.Filled.TextFields
        SftpFileKind.DB -> Icons.Filled.Storage
        SftpFileKind.DISK -> Icons.Filled.Album
        SftpFileKind.KEY -> Icons.Filled.Key
        SftpFileKind.MD -> Icons.AutoMirrored.Filled.Notes
        SftpFileKind.TORRENT -> Icons.Filled.Download
        SftpFileKind.CONFIG -> Icons.Filled.Tune
        SftpFileKind.CODE -> Icons.Filled.Code
        SftpFileKind.TEXT -> Icons.AutoMirrored.Filled.Article
        SftpFileKind.PDF -> Icons.Filled.PictureAsPdf
        SftpFileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

/** File type highlighting (linear icon tint; color-coded list rows; folders retain their theme colors). */
internal fun fileKindColor(kind: SftpFileKind): Color =
    when (kind) {
        SftpFileKind.IMAGE -> Color(0xFF43A047)
        SftpFileKind.VIDEO -> Color(0xFF8E24AA)
        SftpFileKind.AUDIO -> Color(0xFFF57C00)
        SftpFileKind.ARCHIVE -> Color(0xFF6D4C41)
        SftpFileKind.APK -> Color(0xFF00A862)
        SftpFileKind.DOC -> Color(0xFF1E88E5)
        SftpFileKind.SHEET -> Color(0xFF2E7D32)
        SftpFileKind.SLIDES -> Color(0xFFF4511E)
        SftpFileKind.EXEC -> Color(0xFF7E57C2)
        SftpFileKind.FONT -> Color(0xFF00ACC1)
        SftpFileKind.DB -> Color(0xFF3949AB)
        SftpFileKind.DISK -> Color(0xFF546E7A)
        SftpFileKind.KEY -> Color(0xFFE6A23C)
        SftpFileKind.MD -> Color(0xFF9C27B0)
        SftpFileKind.TORRENT -> Color(0xFF00BFA5)
        SftpFileKind.CONFIG -> Color(0xFF90A4AE)
        SftpFileKind.CODE -> Color(0xFF455A64)
        SftpFileKind.TEXT -> Color(0xFF607D8B)
        SftpFileKind.PDF -> Color(0xFFE53935)
        SftpFileKind.OTHER -> Color(0xFF78909C)
    }

// ---------- Text Preview ----------

/** Preview read limit: Truncation occurs when the read limit is exceeded (to prevent large files from being loaded entirely into memory and to ensure smooth rendering). */
const val PREVIEW_MAX_BYTES: Int = 512 * 1024

/** Image preview size limit: Since decoding requires the full data, the limit has been raised to 8MB (the "preview unavailable" tooltip indicates the image is too large, not that there is truncation or corruption). */
const val PREVIEW_IMAGE_MAX_BYTES: Int = 8 * 1024 * 1024

/** Binary sampling size: a NUL byte appears in the first 4KB → classified as binary. */
internal const val PREVIEW_BINARY_SAMPLE = 4096

/** Text preview result: UTF-8 decoded content + truncation. */
data class SftpPreviewResult(
    val text: String,
    val truncated: Boolean,
)

/** Binary files (with NUL content) cannot be previewed. */
class SftpPreviewBinaryException : Exception()

/** Exceeded read limit (internal signal: download stream interrupted; result subject to truncation). */
class SftpPreviewTooLargeException : Exception()

/**
 * Stream the first [maxBytes] bytes of a remote file (for image previews: do not read large files in their entirety).
 * If the limit is exceeded, throw a [SftpPreviewTooLargeException] (image subject to truncation and cannot be decoded; caller is notified of the limit exceeded);
 * Do not check for NUL characters: image content will inevitably contain NUL characters.
 */
suspend fun readSftpPreviewBytes(
    session: SftpSession,
    remotePath: String,
    maxBytes: Int,
): ByteArray =
    withContext(ioDispatcher()) {
        val chunks = ArrayList<ByteArray>()
        var total = 0
        session.download(remotePath) { chunk ->
            val room = maxBytes - total
            if (room <= 0) throw SftpPreviewTooLargeException()
            val n = minOf(chunk.size, room)
            chunks += chunk.copyOfRange(0, n)
            total += n
        }
        val bytes = ByteArray(total)
        var off = 0
        for (c in chunks) {
            c.copyInto(bytes, off)
            off += c.size
        }
        bytes
    }

/**
 * Stream-read the first [maxBytes] bytes of the remote file and decode them into UTF-8 text:
 * - Immediately interrupt the download if the limit is exceeded (do not read large files in their entirety); perform truncation on the result
 * - If the first 4KB of content contains NUL bytes, it is deemed binary, and a [SftpPreviewBinaryException] is thrown.
 * - Invalid UTF-8 sequences are handled as replacement characters and will not cause a crash.
 *
 * Reuse the chunked callback from [SftpSession.download]: throwing an exception in `onChunk` will interrupt the transfer
 * (Both platform implementations close the channel in the `finally` block), so no additional `close` statement is needed.
 */
suspend fun readSftpPreview(
    session: SftpSession,
    remotePath: String,
    maxBytes: Int = PREVIEW_MAX_BYTES,
): SftpPreviewResult =
    withContext(ioDispatcher()) {
        val chunks = ArrayList<ByteArray>()
        var total = 0
        var truncated = false
        var binary = false
        try {
            session.download(remotePath) { chunk ->
                // NUL detection in the sample area: Does not depend on a file extension whitelist; text files without extensions (such as LICENSE or Makefile) can also be previewed.
                if (!binary && total < PREVIEW_BINARY_SAMPLE) {
                    val n = minOf(chunk.size, PREVIEW_BINARY_SAMPLE - total)
                    for (i in 0 until n) {
                        if (chunk[i] == 0.toByte()) {
                            binary = true
                            break
                        }
                    }
                }
                if (binary) throw SftpPreviewBinaryException()
                val room = maxBytes - total
                if (room <= 0) throw SftpPreviewTooLargeException()
                val n = minOf(chunk.size, room)
                chunks += chunk.copyOfRange(0, n)
                total += n
            }
        } catch (e: SftpPreviewTooLargeException) {
            truncated = true
        }
        val bytes = ByteArray(total)
        var off = 0
        for (c in chunks) {
            c.copyInto(bytes, off)
            off += c.size
        }
        // Common API: `decodeToString` defaults to UTF-8 and replaces invalid sequences with characters (consistent with JVM `toString(Charsets.UTF_8)`)
        SftpPreviewResult(bytes.decodeToString(), truncated)
    }

/** Human-readable file sizes (base 1024, rounded to one decimal place starting at KB): e.g., 512 B / 1.5 KB / 2.3 MB. */
internal fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return if (i == 0) "$bytes B" else "${((v * 10).toInt() / 10.0)} ${units[i]}"
}
