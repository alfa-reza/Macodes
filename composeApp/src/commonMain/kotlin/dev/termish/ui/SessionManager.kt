package dev.termish.ui

import androidx.compose.runtime.mutableStateListOf
import dev.termish.crypto.Sha256
import dev.termish.data.Host
import dev.termish.data.HostRepository
import dev.termish.data.HostRepository.RecentSftpEntry
import dev.termish.data.HostRepository.RecentTerminalEntry
import dev.termish.data.resolveCredentials
import dev.termish.ssh.SftpSession
import dev.termish.util.ForegroundSshRecovery
import dev.termish.util.TermLog
import dev.termish.util.base64Encode
import kotlinx.datetime.Clock

/** SFTP session entries (managed at the same level as terminal sessions; persist across screens).
 *  [session] Optional: Entries resumed after a process restart are without connections (session=null),
 *  Trigger an automatic reconnect when entering the SFTP tab (see SftpContent / AppRoot.onReconnectSftp). */
data class SftpSessionEntry(
    val host: Host,
    val session: SftpSession?,
    /** Browsing state (path/list/sorting, etc.): Persists with the entry; switching tabs does not reset it. */
    val uiState: SftpUiState = SftpUiState(),
    /** The identity identifier for the connection associated with the current [session] (generated each time by `establishSftp`).
     *  close() syncs onClosed: the callback for an old connection that was closed during reconnect, replacement, or removal still exists,
     *  It is essential to distinguish between “the current connection was unexpectedly disconnected” and “the old connection was actively closed” based on the connection generation,
     *  Otherwise, the old callback will incorrectly set `uiState.disconnected` back to `true` (causing the disconnected banner to never disappear). */
    val connectionToken: Any = Any(),
    /** The time the entry was first created; this remains unchanged when the underlying connection is replaced during a reconnect. */
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
)

internal enum class BackgroundReconnectAction {
    NONE,
    RECONNECT,
    VERIFY,
    REBUILD,
}

/** Pure decision-making layer; avoid mistakenly identifying other sessions on the same host as active background sessions within lifecycle callbacks. */
internal fun backgroundReconnectAction(
    sessionId: String,
    activeSessionIds: Set<String>,
    autoReconnect: Boolean,
    status: ConnStatus,
    usesMoshTransport: Boolean,
    recovery: ForegroundSshRecovery,
): BackgroundReconnectAction =
    when {
        sessionId !in activeSessionIds || !autoReconnect -> BackgroundReconnectAction.NONE
        status == ConnStatus.CLOSED || status == ConnStatus.ERROR -> BackgroundReconnectAction.RECONNECT
        recovery == ForegroundSshRecovery.REBUILD &&
            !usesMoshTransport &&
            (status == ConnStatus.CONNECTED || status == ConnStatus.AUTH) -> BackgroundReconnectAction.REBUILD
        recovery == ForegroundSshRecovery.VERIFY &&
            !usesMoshTransport &&
            status == ConnStatus.CONNECTED -> BackgroundReconnectAction.VERIFY
        else -> BackgroundReconnectAction.NONE
    }

/**
 * Session Manager: Holds all active [TerminalController] instances and persists across screens.
 * Reuse when there is an active session on the same host (terminal buffer is retained; re-entering resumes from where you left off);
 * Leaving the terminal screen (going back to the home page) does not disconnect; the connection is kept alive by the foreground service.
 */
class SessionManager(
    private val repository: HostRepository,
    /** Connection-error message provider (fetch the latest AppStrings on language switch). */
    private val strings: () -> AppStrings = { appStringsFor("en") },
) {
    val sessions = mutableStateListOf<TerminalController>()

    /** SFTP sessions (managed in the same way as terminal sessions: visible on the Connections page, can be closed by clicking the “Close” button on the card, and deleting a host releases the session). */
    val sftpSessions = mutableStateListOf<SftpSessionEntry>()

    /** Session ID that remains active when the app is in the background (used to automatically reconnect when the app is brought back to the foreground; iOS scenario). */
    private val activeAtBackground = HashSet<String>()

    /** Go to the background: Record the current active session so it can be resumed when returning to the foreground. */
    fun noteBackgrounded() {
        activeAtBackground.clear()
        sessions.forEach { controller ->
            val st = controller.status
            if (st == ConnStatus.CONNECTED || st == ConnStatus.AUTH || st == ConnStatus.CONNECTING) {
                activeAtBackground.add(controller.sessionId)
            }
        }
    }

    /**
     * Return to the foreground: Automatically reconnect active sessions that were disconnected while the process was in the background (with buffering).
     *
     * [recovery] Determined by the platform: On iOS, rebuild immediately after suspension; on Android, when the background service is stopped,
     * Perform end-to-end checks only to avoid falsely flagging healthy sockets.
     */
    fun reconnectDroppedSessions(recovery: ForegroundSshRecovery = ForegroundSshRecovery.KEEP) {
        if (activeAtBackground.isEmpty()) return
        val ids = activeAtBackground.toSet()
        activeAtBackground.clear()
        sessions.forEach { controller ->
            when (
                backgroundReconnectAction(
                    sessionId = controller.sessionId,
                    activeSessionIds = ids,
                    autoReconnect = controller.autoReconnectEnabled,
                    status = controller.status,
                    usesMoshTransport = controller.moshSession != null,
                    recovery = recovery,
                )
            ) {
                BackgroundReconnectAction.REBUILD -> {
                    TermLog.i("session") { "foreground rebuild ${controller.host.name} session=${controller.sessionId}" }
                    controller.forceReconnect(SessionReconnectCause.FOREGROUND_RESUME)
                }

                BackgroundReconnectAction.RECONNECT -> controller.reconnect()
                BackgroundReconnectAction.VERIFY -> {
                    TermLog.i("session") { "foreground verify ${controller.host.name} session=${controller.sessionId}" }
                    controller.verifySshConnection(SessionReconnectCause.FOREGROUND_RESUME)
                }
                BackgroundReconnectAction.NONE -> Unit
            }
        }
    }

    /** After the Android background service is rebuilt, re-register all sessions that are still connected; the sessionId collection ensures idempotency. */
    fun restoreKeepAliveRegistrations() {
        sessions.forEach { it.restoreKeepAliveRegistration() }
    }

    /** Resume the list of sessions left over from the last run (if the process dies, the connection will be lost; it will revert to an unconnected state; tap to reconnect).
     *  Both terminal and SFTP entries are restored (SFTP `session=null`; entering the tab auto-reconnects). */
    fun restoreRecent(
        hosts: List<Host>,
        autoReconnect: Boolean,
        onSystemDetected: ((Host) -> Unit)? = null,
    ) {
        if (sessions.isNotEmpty()) return
        TermLog.i("session") {
            "restoreRecent terminals=${repository.loadRecentTerminalEntries().size} sftp=${repository.loadRecentSftpEntries().size}"
        }
        val byId = hosts.associateBy { it.id }
        repository.loadRecentTerminalEntries().forEach { entry ->
            val host = byId[entry.hostId] ?: return@forEach
            val (pw, key) = resolveCredentials(host)
            TerminalController(
                host,
                pw,
                key,
                repository,
                autoReconnect,
                launchMode = if (entry.launchHerdr) TerminalLaunchMode.HERDR else TerminalLaunchMode.SHELL,
                strings = strings,
                createdAt = entry.createdAt.takeIf { it > 0L } ?: Clock.System.now().toEpochMilliseconds(),
            ).also {
                it.onSystemDetected = onSystemDetected
                sessions.add(it)
            }
        }
        // SFTP: restored as unconnected entries (session=null); entering the tab auto-reconnects;
        // Resume the browsing path from persistence (return to the last directory after a process restart, rather than the home directory)
        if (sftpSessions.isEmpty()) {
            repository.loadRecentSftpEntries().forEach { entry ->
                val host = byId[entry.hostId] ?: return@forEach
                sftpSessions.add(
                    SftpSessionEntry(
                        host = host,
                        session = null,
                        uiState = SftpUiState().also { it.path = entry.path },
                        createdAt = entry.createdAt.takeIf { it > 0L } ?: Clock.System.now().toEpochMilliseconds(),
                    ),
                )
            }
        }
        // The old entry lacks a `createdAt`: The current time has been added above and must be written back immediately; otherwise, every time the process
        // The time is regenerated every time the system restarts, so the “Created” time in the menu will appear to change constantly.
        persist()
    }

    private fun persist() {
        repository.saveRecentTerminalEntries(
            sessions.map {
                RecentTerminalEntry(
                    hostId = it.host.id,
                    launchHerdr = it.launchMode == TerminalLaunchMode.HERDR,
                    createdAt = it.createdAt,
                )
            },
        )
        repository.saveRecentSftpEntries(
            sftpSessions.map { RecentSftpEntry(it.host.id, it.uiState.path, it.createdAt) },
        )
    }

    /** To be called when AppRoot goes into the background: Save the latest SFTP browsing path (resume in the previous directory after the app is terminated). */
    fun persistNow() = persist()

    /** Open a host: If an active session exists, reuse it; otherwise, create a new one after parsing the credentials. */
    fun open(
        host: Host,
        autoReconnect: Boolean,
        launchMode: TerminalLaunchMode = TerminalLaunchMode.SHELL,
        onSystemDetected: ((Host) -> Unit)? = null,
    ): TerminalController {
        TermLog.i("session") { "open ${host.name} (${sessions.count { it.host.id == host.id } + 1}th)" }
        // Support for multiple sessions on the same host (Termius-style): a new session is created each time it is opened,
        // Old sessions are retained in the list and can be re-entered via card dropdown / the Connections page.
        val (pw, key) = resolveCredentials(host)
        val controller =
            TerminalController(
                host,
                pw,
                key,
                repository,
                autoReconnect,
                launchMode = launchMode,
                strings = strings,
            )
        controller.onSystemDetected = onSystemDetected
        sessions.add(controller)
        persist()
        return controller
    }

    /** Disconnected but still listed (gray dot; tap to reconnect; buffering retained). */
    fun disconnect(controller: TerminalController) {
        controller.close()
    }

    /** Remove from the list (for disconnected sessions): Destroy the controller and reclaim the coroutine scope. */
    fun remove(controller: TerminalController) {
        TermLog.i("session") { "remove ${controller.host.name}" }
        controller.destroy()
        sessions.remove(controller)
        persist()
    }

    /**
     * Current host configuration + signature of stored credentials. Compare with [TerminalController.credentialKey]:
     * Inconsistencies indicate that host configuration or credentials have changed—old sessions should not be reused; a new one should be created (old sessions can be manually closed).
     */
    fun signatureFor(host: Host): String {
        val (pw, key) = resolveCredentials(host)
        return credentialSignature(host, pw, key)
    }

    /**
     * Register an SFTP session (added by the caller after a successful connection). Returned entry: Use the same `uiState` when the caller creates a tab.
     * One session per host: Replace existing entries (preserve browsing state; ensure consistent reconnection semantics) —
     * Otherwise, the process resumption entry and the new connection will result in two entries for the same host coexisting.
     */
    fun addSftp(
        host: Host,
        session: SftpSession,
        connectionToken: Any,
    ): SftpSessionEntry {
        val existing = sftpSessions.firstOrNull { it.host.id == host.id }
        val entry =
            SftpSessionEntry(
                host = host,
                session = session,
                uiState = existing?.uiState ?: SftpUiState(),
                connectionToken = connectionToken,
                createdAt = existing?.createdAt ?: Clock.System.now().toEpochMilliseconds(),
            )
        // First remove the old entry (register the new generation), then close the old connection: close synchronously triggers the old connection's
        // generation no longer matches → it won't wrongly flag the shared uiState as disconnected.
        if (existing != null) {
            sftpSessions.remove(existing)
        }
        sftpSessions.add(entry)
        existing?.session?.let { old ->
            try {
                old.close()
            } catch (_: Exception) {
            }
        }
        persist()
        return entry
    }

    /**
     * SFTP Reconnection: Replace "session" in the entry (uiState is preserved, and the browsing path/list is retained).
     * Close the old session first to release the connection; the caller is responsible for prompting the user in case of a reconnection failure.
     */
    fun reconnectSftp(
        entry: SftpSessionEntry,
        newSession: SftpSession,
        connectionToken: Any,
    ) {
        TermLog.i("sftp") { "reconnect ${entry.host.name}" }
        val idx = sftpSessions.indexOf(entry)
        if (idx >= 0) {
            val old = entry.session
            // First replace (the new instance takes effect immediately), then close the old connection: closing the old connection triggers `onClosed` sync-style,
            // Generation mismatch → Do not incorrectly label as "disconnected" (otherwise, the "Reconnect success" banner will not disappear)
            sftpSessions[idx] =
                SftpSessionEntry(
                    entry.host,
                    newSession,
                    entry.uiState,
                    connectionToken,
                    entry.createdAt,
                )
            old?.let {
                try {
                    it.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Disconnect the SFTP session but retain the entry (same semantics as a terminal session: the gray dot indicates it can be reconnected, and the browsing state is preserved). */
    fun disconnectSftp(entry: SftpSessionEntry) {
        // First clear the session, then close it: closing the session triggers `onClosed` in sync; at this point, the entry no longer has a session,
        // The callback cannot find a target to mark (the "disconnected" flag is set here proactively and is not affected by the old callback).
        val idx = sftpSessions.indexOf(entry)
        if (idx >= 0) {
            sftpSessions[idx] =
                SftpSessionEntry(
                    host = entry.host,
                    session = null,
                    uiState = entry.uiState,
                    createdAt = entry.createdAt,
                )
            entry.uiState.disconnected = true
            // User-initiated disconnection: No automatic reconnection (a disconnection banner shows when opening the tab; manually click to reconnect);
            // Unexpected link breaks (onClosed path) will still trigger one automatic reconnect
            entry.uiState.autoReconnectAttempted = true
        }
        try {
            entry.session?.close()
        } catch (_: Exception) {
        }
        persist()
    }

    fun closeSftp(entry: SftpSessionEntry) {
        // Remove first, then close: Closing triggers `onClosed` in sync, at which point the entry is no longer in the list,
        // No mislabeling or incorrect logging
        sftpSessions.remove(entry)
        try {
            entry.session?.close()
        } catch (_: Exception) {
        }
        persist()
    }

    /** Close all sessions on the host: Disconnect but retain re-entry capability (reconnect via the gray dot in the terminal; resume browsing via SFTP by reconnecting with the same path). */
    fun closeAllForHost(hostId: String) {
        sessions.filter { it.host.id == hostId }.forEach { disconnect(it) }
        sftpSessions.filter { it.host.id == hostId && it.session != null }.toList().forEach { disconnectSftp(it) }
    }

    /** When a host is deleted, its sessions are disconnected and removed as well. */
    fun closeForHost(hostId: String) {
        sessions.filter { it.host.id == hostId }.forEach { it.destroy() }
        sessions.removeAll { it.host.id == hostId }
        sftpSessions.filter { it.host.id == hostId }.toList().forEach { closeSftp(it) }
        persist()
    }
}

/** Credentials/connection parameters/launch command signature: Any change will be treated as the old session expiring (a rebuild of the session is required after editing the host). */
internal fun credentialSignature(
    host: Host,
    password: String?,
    privateKeyPem: String?,
): String {
    // Retain only hashes for sensitive fields: Avoid storing plaintext passwords or private keys in memory as signature strings.
    fun hash(s: String?): String = if (s == null) "-" else base64Encode(Sha256.digest(s.encodeToByteArray()))
    return "${host.username}@${host.hostname}:${host.port}|${host.authMethod}|" +
        "${hash(password)}|${hash(privateKeyPem)}|" +
        "${host.startupCommand}|${host.connectionMode}|${host.moshUdpPort}|${host.moshThemeSync}"
}
