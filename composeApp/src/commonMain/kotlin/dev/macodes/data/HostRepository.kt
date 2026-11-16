package dev.macodes.data

import com.russhwolf.settings.Settings
import dev.macodes.util.TermLog
import dev.macodes.util.withRepoLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Hosts and settings repository. Non-secret data is stored in `multiplatform-settings` (JSON),
 * Store sensitive data (passwords/private keys) securely in platform storage ([SecretStore]).
 */
class HostRepository(
    private val settings: Settings = Settings(),
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        },
) {
    private val lock = Any()
    private val hostsKey = "macodes.hosts.v1"
    private val hostFoldersKey = "macodes.host_folders.v1"
    private val settingsKey = "macodes.settings.v1"
    private val tagGroupsKey = "macodes.tag_groups.v1"
    private val snippetsKey = "macodes.snippets.v1"
    private val credentialsKey = "macodes.keychain.v1"
    private val connectionLogsKey = "macodes.connection_logs.v1"
    private val sessionLogsKey = "macodes.session_logs.v1"
    private val mutableAppSettings = MutableStateFlow(readSettings())
    private val mutableSessionLogsVersion = MutableStateFlow(0L)

    /**
     * A single source of truth for settings within the process. The Settings page, Agent page, and connection layer all read from here to avoid each having their own
     * After maintaining the entire [AppSettings] snapshot for an extended period, the fields will overwrite each other.
     */
    val appSettings: StateFlow<AppSettings> = mutableAppSettings.asStateFlow()
    val sessionLogsVersion: StateFlow<Long> = mutableSessionLogsVersion.asStateFlow()

    // ---------- host ----------

    fun listHosts(): List<Host> {
        val raw = settings.getStringOrNull(hostsKey) ?: return emptyList()
        return try {
            json.decodeFromString<List<Host>>(raw)
        } catch (e: Exception) {
            // Do not clear data on parse failure: back up the original string for recovery/troubleshooting to prevent subsequent `upsert` operations from overwriting all hosts and causing data loss.
            backupCorrupt(hostsKey, raw)
            TermLog.w("data") { "corrupt hosts backed up: ${e.message}" }
            emptyList()
        }
    }

    fun getHost(id: String): Host? = listHosts().firstOrNull { it.id == id }

    fun upsertHost(host: Host) {
        withRepoLock(lock) {
            val hosts = listHosts().toMutableList()
            val idx = hosts.indexOfFirst { it.id == host.id }
            if (idx >= 0) hosts[idx] = host else hosts.add(host)
            saveHosts(hosts)
        }
    }

    fun deleteHost(id: String) {
        withRepoLock(lock) {
            saveHosts(listHosts().filter { it.id != id })
        }
    }

    fun touchConnected(
        id: String,
        fingerprint: String?,
    ) {
        patchHost(id) {
            it.copy(
                lastConnectedAt = currentTimeMillis(),
                knownHostFingerprint = fingerprint ?: it.knownHostFingerprint,
            )
        }
    }

    /** Only record the host key fingerprint (called immediately after clicking “Trust” in the TOFU dialog, decoupled from the success or failure of subsequent authentication). */
    fun recordHostKey(
        id: String,
        fingerprint: String,
    ) {
        patchHost(id) { it.copy(knownHostFingerprint = fingerprint) }
    }

    /**
     * Partial field updates: Transform based on the latest values in the repository; do not overwrite the entire entry—controller-held
     * `host` is a snapshot taken when it is created; other fields may have been updated during the connection (TOFU fingerprint,
     * (lastConnectedAt), using a snapshot upsert will wipe them out (new host fingerprints will be lost)
     * → The root cause of the confirmation dialog repeatedly popping up every time the connection is reconnected).
     */
    fun patchHost(
        id: String,
        transform: (Host) -> Host,
    ) {
        withRepoLock(lock) {
            val hosts = listHosts().map { h -> if (h.id == id) transform(h) else h }
            saveHosts(hosts)
        }
    }

    private fun saveHosts(hosts: List<Host>) {
        settings.putString(hostsKey, json.encodeToString(hosts))
    }

    // ---------- host folders ----------

    fun listFolders(): List<HostFolder> {
        val raw = settings.getStringOrNull(hostFoldersKey) ?: return emptyList()
        return try {
            json.decodeFromString<List<HostFolder>>(raw)
        } catch (e: Exception) {
            backupCorrupt(hostFoldersKey, raw)
            TermLog.w("data") { "corrupt host folders backed up: ${e.message}" }
            emptyList()
        }
    }

    fun upsertFolder(folder: HostFolder) {
        val cleanName = folder.name.trim()
        if (cleanName.isEmpty()) return
        withRepoLock(lock) {
            val folders = listFolders().toMutableList()
            val validParent =
                folder.parentId
                    ?.takeIf { pid -> folders.any { it.id == pid } }
                    ?.takeUnless { pid -> wouldCreateFolderCycle(folders, folder.id, pid) }
            val normalized = folder.copy(name = cleanName, parentId = validParent)
            val idx = folders.indexOfFirst { it.id == normalized.id }
            if (idx >= 0) folders[idx] = normalized else folders.add(normalized)
            settings.putString(hostFoldersKey, json.encodeToString(folders))
        }
    }

    /**
     * Delete folder without losing hosts or subfolders:
     * Direct child folders and hosts are reparented to the deleted folder's parent (or root).
     */
    fun deleteFolder(id: String) {
        withRepoLock(lock) {
            val folders = listFolders()
            val target = folders.firstOrNull { it.id == id } ?: return@withRepoLock
            val fallbackParent = target.parentId?.takeIf { pid -> pid != id && folders.any { it.id == pid } }
            val updatedFolders =
                folders
                    .filter { it.id != id }
                    .map { f -> if (f.parentId == id) f.copy(parentId = fallbackParent) else f }
            settings.putString(hostFoldersKey, json.encodeToString(updatedFolders))

            val hosts = listHosts()
            val updatedHosts = hosts.map { h -> if (h.folderId == id) h.copy(folderId = fallbackParent) else h }
            if (updatedHosts != hosts) {
                saveHosts(updatedHosts)
            }
        }
    }

    fun moveHostsToFolder(
        hostIds: Set<String>,
        folderId: String?,
    ) {
        if (hostIds.isEmpty()) return
        withRepoLock(lock) {
            val validFolderId = folderId?.takeIf { fid -> listFolders().any { it.id == fid } }
            val hosts = listHosts()
            val updated = hosts.map { h -> if (h.id in hostIds) h.copy(folderId = validFolderId) else h }
            if (updated != hosts) {
                saveHosts(updated)
            }
        }
    }

    // ---------- Tag Groups (Global, Snippet References) ----------

    fun listTagGroups(): List<TagGroup> {
        val raw = settings.getStringOrNull(tagGroupsKey) ?: return emptyList()
        return try {
            json.decodeFromString<List<TagGroup>>(raw)
        } catch (e: Exception) {
            backupCorrupt(tagGroupsKey, raw)
            emptyList()
        }
    }

    fun upsertTagGroup(group: TagGroup) {
        withRepoLock(lock) {
            val groups = listTagGroups().toMutableList()
            val idx = groups.indexOfFirst { it.id == group.id }
            if (idx >= 0) groups[idx] = group else groups.add(group)
            settings.putString(tagGroupsKey, json.encodeToString(groups))
        }
    }

    /** Delete tag group: Cascading cleanup of all references to this tag across all snippets (without deleting the snippets themselves). */
    fun deleteTagGroup(id: String) {
        withRepoLock(lock) {
            settings.putString(tagGroupsKey, json.encodeToString(listTagGroups().filter { it.id != id }))
            // Cascade cleanup of snippet references (write back only when references are actually cleaned up to avoid unnecessary writes)
            val before = listSnippets()
            val cleaned = before.map { s -> if (id in s.tagIds) s.copy(tagIds = s.tagIds - id) else s }
            if (cleaned != before) {
                settings.putString(snippetsKey, json.encodeToString(cleaned))
            }
        }
    }

    // ---------- Command Snippets (Global Library) ----------

    fun listSnippets(): List<Snippet> {
        val raw = settings.getStringOrNull(snippetsKey) ?: return emptyList()
        return try {
            json.decodeFromString<List<Snippet>>(raw)
        } catch (e: Exception) {
            backupCorrupt(snippetsKey, raw)
            emptyList()
        }
    }

    fun getSnippet(id: String): Snippet? = listSnippets().firstOrNull { it.id == id }

    fun upsertSnippet(snippet: Snippet) {
        withRepoLock(lock) {
            val snippets = listSnippets().toMutableList()
            val idx = snippets.indexOfFirst { it.id == snippet.id }
            if (idx >= 0) snippets[idx] = snippet else snippets.add(snippet)
            settings.putString(snippetsKey, json.encodeToString(snippets))
        }
    }

    fun deleteSnippet(id: String) {
        withRepoLock(lock) {
            settings.putString(snippetsKey, json.encodeToString(listSnippets().filter { it.id != id }))
        }
    }

    // ---------- settings ----------

    private fun readSettings(): AppSettings {
        val raw = settings.getStringOrNull(settingsKey) ?: return AppSettings()
        return try {
            json.decodeFromString<AppSettings>(raw)
        } catch (e: Exception) {
            backupCorrupt(settingsKey, raw)
            TermLog.w("data") { "corrupt app settings backed up: ${e.message}" }
            AppSettings()
        }
    }

    fun loadSettings(): AppSettings = mutableAppSettings.value

    fun saveSettings(s: AppSettings) {
        mutableAppSettings.value = s
        settings.putString(settingsKey, json.encodeToString(s))
    }

    /**
     * Perform field-level updates based on the latest values. If concurrency has advanced the state after persistence, the latest values are still written;
     * this stops an earlier call from being written to disk last and overwriting newer fields.
     */
    fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings {
        mutableAppSettings.update(transform)
        while (true) {
            val snapshot = mutableAppSettings.value
            settings.putString(settingsKey, json.encodeToString(snapshot))
            if (mutableAppSettings.value == snapshot) return snapshot
        }
    }

    fun loadAgentPreferences(hostId: String): AgentWorkspacePreferences {
        val key = "macodes.agent.preferences.$hostId"
        val raw = settings.getStringOrNull(key) ?: return AgentWorkspacePreferences()
        return runCatching { json.decodeFromString<AgentWorkspacePreferences>(raw) }.getOrDefault(AgentWorkspacePreferences())
    }

    fun saveAgentPreferences(
        hostId: String,
        preferences: AgentWorkspacePreferences,
    ) {
        settings.putString("macodes.agent.preferences.$hostId", json.encodeToString(preferences))
    }

    // ---------- Directory Bookmarks (SFTP File Manager, persisted per host) ----------

    fun loadFavorites(hostId: String): List<String> {
        val raw = settings.getStringOrNull("macodes.favorites.$hostId") ?: return emptyList()
        return try {
            json.decodeFromString<List<String>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveFavorites(
        hostId: String,
        paths: List<String>,
    ) {
        settings.putString("macodes.favorites.$hostId", json.encodeToString(paths))
    }

    // ---------- Recent Sessions (persistence of the connection-page list) ----------

    /** Recent terminal sessions: host + session type; Herdr no longer writes to the host configuration. */
    @Serializable
    data class RecentTerminalEntry(
        val hostId: String,
        val launchHerdr: Boolean = false,
        val createdAt: Long = 0L,
    )

    private val recentSessionsKey = "macodes.recent_sessions.v2"

    /** After a restart, the connection page is resumed with the status set to “Not Connected”; tapping it will reconnect using the original launch type. */
    fun loadRecentTerminalEntries(): List<RecentTerminalEntry> {
        val raw = settings.getStringOrNull(recentSessionsKey) ?: return emptyList()
        return try {
            json.decodeFromString<List<RecentTerminalEntry>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveRecentTerminalEntries(entries: List<RecentTerminalEntry>) {
        settings.putString(recentSessionsKey, json.encodeToString(entries))
    }

    /** Recent SFTP sessions: host ID + navigation path (paths are included starting with v2; the directory resumes at the previous one after the process restarts). */
    @Serializable
    data class RecentSftpEntry(
        val hostId: String,
        val path: String = "",
        val createdAt: Long = 0L,
    )

    private val recentSftpKey = "macodes.recent_sftp.v2"
    private val quickConnectHistoryKey = "macodes.quick_connect_history.v1"
    private val quickConnectHostKeysKey = "macodes.quick_connect_hostkeys.v1"

    fun listQuickConnectHistory(): List<QuickConnectHistoryEntry> {
        val raw = settings.getStringOrNull(quickConnectHistoryKey) ?: return emptyList()
        return try {
            json
                .decodeFromString<List<QuickConnectHistoryEntry>>(raw)
                .sortedByDescending { it.lastConnectedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun QuickConnectHistoryEntry.sameEndpointAs(other: QuickConnectHistoryEntry): Boolean =
        protocol == other.protocol &&
            host == other.host &&
            port == other.port &&
            moshUdpPort == other.moshUdpPort &&
            username == other.username &&
            openSftp == other.openSftp

    fun recordQuickConnectHistory(entry: QuickConnectHistoryEntry) {
        withRepoLock(lock) {
            val updated =
                listQuickConnectHistory()
                    .filterNot { it.sameEndpointAs(entry) }
                    .toMutableList()
            updated.add(0, entry)
            settings.putString(quickConnectHistoryKey, json.encodeToString(updated.take(20)))
        }
    }

    fun deleteQuickConnectHistory(entry: QuickConnectHistoryEntry) {
        withRepoLock(lock) {
            val updated = listQuickConnectHistory().filterNot { it.sameEndpointAs(entry) }
            settings.putString(quickConnectHistoryKey, json.encodeToString(updated))
        }
    }

    fun clearQuickConnectHistory() {
        withRepoLock(lock) {
            settings.remove(quickConnectHistoryKey)
        }
    }

    private fun quickHostKey(
        host: String,
        port: Int,
    ): String = "$host:$port"

    fun getQuickConnectFingerprint(
        host: String,
        port: Int,
    ): String? {
        val raw = settings.getStringOrNull(quickConnectHostKeysKey) ?: return null
        return try {
            json.decodeFromString<Map<String, String>>(raw)[quickHostKey(host, port)]
        } catch (_: Exception) {
            null
        }
    }

    fun recordQuickConnectFingerprint(
        host: String,
        port: Int,
        fingerprint: String,
    ) {
        withRepoLock(lock) {
            val raw = settings.getStringOrNull(quickConnectHostKeysKey)
            val map =
                if (raw == null) {
                    mutableMapOf()
                } else {
                    try {
                        json.decodeFromString<Map<String, String>>(raw).toMutableMap()
                    } catch (_: Exception) {
                        mutableMapOf()
                    }
                }
            val key = quickHostKey(host, port)
            map.remove(key)
            map[key] = fingerprint
            while (map.size > MAX_QUICK_CONNECT_HOST_KEYS) {
                map.remove(map.keys.first())
            }
            settings.putString(quickConnectHostKeysKey, json.encodeToString(map))
        }
    }

    private companion object {
        const val MAX_QUICK_CONNECT_HOST_KEYS = 100
    }

    // ---------- Keychain Credentials (Global Library) ----------

    fun listCredentials(): List<KeychainCredential> {
        val raw = settings.getStringOrNull(credentialsKey) ?: return emptyList()
        return try {
            json
                .decodeFromString<List<KeychainCredential>>(raw)
                .sortedByDescending { it.updatedAt }
        } catch (e: Exception) {
            backupCorrupt(credentialsKey, raw)
            emptyList()
        }
    }

    fun getCredential(id: String): KeychainCredential? = listCredentials().firstOrNull { it.id == id }

    fun upsertCredential(credential: KeychainCredential) {
        withRepoLock(lock) {
            val items = listCredentials().toMutableList()
            val idx = items.indexOfFirst { it.id == credential.id }
            if (idx >= 0) items[idx] = credential else items.add(0, credential)
            settings.putString(credentialsKey, json.encodeToString(items))
        }
    }

    fun deleteCredential(id: String) {
        withRepoLock(lock) {
            val remaining =
                listCredentials()
                    .filter { it.id != id }
                    .map { cred ->
                        if (cred.linkedPrivateKeyId == id) {
                            cred.copy(linkedPrivateKeyId = null, isActive = false)
                        } else {
                            cred
                        }
                    }
            settings.putString(
                credentialsKey,
                json.encodeToString(remaining),
            )
            // Detach credential references from saved hosts without deleting the hosts themselves.
            val before = listHosts()
            val updated = before.map { h -> if (h.credentialId == id) h.copy(credentialId = null) else h }
            if (updated != before) {
                saveHosts(updated)
            }
        }
    }

    // ---------- Known Hosts (Saved Hosts + Quick Connect Fingerprints) ----------

    data class KnownHostRecord(
        val id: String,
        val label: String,
        val endpoint: String,
        val fingerprint: String,
        val isQuickConnect: Boolean,
        val quickConnectKey: String? = null,
    )

    fun listKnownHosts(): List<KnownHostRecord> {
        val saved =
            listHosts().mapNotNull { host ->
                val fp = host.knownHostFingerprint?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                KnownHostRecord(
                    id = "host:${host.id}",
                    label = host.name.ifBlank { host.hostname },
                    endpoint = "${host.hostname}:${host.port}",
                    fingerprint = fp,
                    isQuickConnect = false,
                )
            }
        val qcRaw = settings.getStringOrNull(quickConnectHostKeysKey)
        val qcMap =
            if (qcRaw == null) {
                emptyMap()
            } else {
                try {
                    json.decodeFromString<Map<String, String>>(qcRaw)
                } catch (_: Exception) {
                    emptyMap()
                }
            }
        val quick =
            qcMap.entries.map { (endpoint, fp) ->
                KnownHostRecord(
                    id = "qc:$endpoint",
                    label = endpoint,
                    endpoint = endpoint,
                    fingerprint = fp,
                    isQuickConnect = true,
                    quickConnectKey = endpoint,
                )
            }
        return saved + quick
    }

    fun forgetKnownHost(record: KnownHostRecord) {
        withRepoLock(lock) {
            if (record.isQuickConnect) {
                val key = record.quickConnectKey ?: return@withRepoLock
                val raw = settings.getStringOrNull(quickConnectHostKeysKey) ?: return@withRepoLock
                val map =
                    try {
                        json.decodeFromString<Map<String, String>>(raw).toMutableMap()
                    } catch (_: Exception) {
                        return@withRepoLock
                    }
                if (map.remove(key) != null) {
                    settings.putString(quickConnectHostKeysKey, json.encodeToString(map))
                }
            } else {
                val hostId = record.id.removePrefix("host:")
                val hosts = listHosts().map { h -> if (h.id == hostId) h.copy(knownHostFingerprint = null) else h }
                saveHosts(hosts)
            }
        }
    }

    // ---------- Session Logs ----------

    fun listSessionLogs(
        query: SessionLogQuery = SessionLogQuery(),
        now: Long = currentTimeMillis(),
        resolveErrorText: (SessionLogErrorCode) -> String = { it.name },
    ): List<SessionLog> {
        val stored =
            withRepoLock(lock) {
                val rawLogs = loadRawSessionLogsLocked()
                val retained = applySessionLogRetention(rawLogs, now)
                if (retained.size != rawLogs.size) {
                    settings.putString(sessionLogsKey, json.encodeToString(retained))
                }
                retained
            }
        return filterAndSortSessionLogs(stored, query, resolveErrorText)
    }

    fun getSessionLog(
        id: String,
        now: Long = currentTimeMillis(),
    ): SessionLog? = listSessionLogs(now = now).firstOrNull { it.id == id }

    fun saveSessionLog(
        entry: SessionLog,
        now: Long = currentTimeMillis(),
    ) {
        withRepoLock(lock) {
            val updated = loadRawSessionLogsLocked().filterNot { it.id == entry.id }.toMutableList()
            updated.add(0, entry)
            val retained = applySessionLogRetention(updated, now)
            settings.putString(sessionLogsKey, json.encodeToString(retained))
        }
        mutableSessionLogsVersion.value++
    }

    fun clearSessionLogs() {
        withRepoLock(lock) {
            settings.remove(sessionLogsKey)
            settings.remove(connectionLogsKey)
        }
        mutableSessionLogsVersion.value++
    }

    fun deleteSessionLogs(ids: Set<String>) {
        if (ids.isEmpty()) return
        withRepoLock(lock) {
            val remaining = loadRawSessionLogsLocked().filterNot { it.id in ids }
            settings.putString(sessionLogsKey, json.encodeToString(remaining))
        }
        mutableSessionLogsVersion.value++
    }

    private fun loadRawSessionLogsLocked(): List<SessionLog> {
        val raw = settings.getStringOrNull(sessionLogsKey) ?: return emptyList()
        return try {
            json.decodeFromString<List<SessionLog>>(raw)
        } catch (e: Exception) {
            backupCorrupt(sessionLogsKey, raw)
            emptyList()
        }
    }

    fun loadRecentSftpEntries(): List<RecentSftpEntry> {
        val raw = settings.getStringOrNull(recentSftpKey) ?: return emptyList()
        return try {
            json.decodeFromString<List<RecentSftpEntry>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveRecentSftpEntries(entries: List<RecentSftpEntry>) {
        settings.putString(recentSftpKey, json.encodeToString(entries))
    }

    /** In case of parsing failure, back up the original content to a separate key (retain up to 3 copies) to prevent silent data loss. */
    private fun backupCorrupt(
        key: String,
        raw: String,
    ) {
        try {
            settings.putString("$key.corrupt.${currentTimeMillis()}", raw)
            // If there are more than 3 entries, delete the oldest one (the key contains a timestamp; lexicographical order corresponds to chronological order).
            val corruptKeys = settings.keys.filter { it.startsWith("$key.corrupt.") }.sorted()
            corruptKeys.dropLast(3).forEach { settings.remove(it) }
        } catch (_: Exception) {
        }
    }

    private fun currentTimeMillis(): Long =
        kotlinx.datetime.Clock.System
            .now()
            .toEpochMilliseconds()
}
