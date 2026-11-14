package dev.macodes.data

import com.russhwolf.settings.Settings
import dev.macodes.util.TermLog
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
    private val hostsKey = "macodes.hosts.v1"
    private val settingsKey = "macodes.settings.v1"
    private val tagGroupsKey = "macodes.tag_groups.v1"
    private val snippetsKey = "macodes.snippets.v1"
    private val mutableAppSettings = MutableStateFlow(readSettings())

    /**
     * A single source of truth for settings within the process. The Settings page, Agent page, and connection layer all read from here to avoid each having their own
     * After maintaining the entire [AppSettings] snapshot for an extended period, the fields will overwrite each other.
     */
    val appSettings: StateFlow<AppSettings> = mutableAppSettings.asStateFlow()

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
        val hosts = listHosts().toMutableList()
        val idx = hosts.indexOfFirst { it.id == host.id }
        if (idx >= 0) hosts[idx] = host else hosts.add(host)
        saveHosts(hosts)
    }

    fun deleteHost(id: String) {
        saveHosts(listHosts().filter { it.id != id })
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
        val hosts = listHosts().map { h -> if (h.id == id) transform(h) else h }
        saveHosts(hosts)
    }

    private fun saveHosts(hosts: List<Host>) {
        settings.putString(hostsKey, json.encodeToString(hosts))
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
        val groups = listTagGroups().toMutableList()
        val idx = groups.indexOfFirst { it.id == group.id }
        if (idx >= 0) groups[idx] = group else groups.add(group)
        settings.putString(tagGroupsKey, json.encodeToString(groups))
    }

    /** Delete tag group: Cascading cleanup of all references to this tag across all snippets (without deleting the snippets themselves). */
    fun deleteTagGroup(id: String) {
        settings.putString(tagGroupsKey, json.encodeToString(listTagGroups().filter { it.id != id }))
        // Cascade cleanup of snippet references (write back only when references are actually cleaned up to avoid unnecessary writes)
        val before = listSnippets()
        val cleaned = before.map { s -> if (id in s.tagIds) s.copy(tagIds = s.tagIds - id) else s }
        if (cleaned != before) {
            settings.putString(snippetsKey, json.encodeToString(cleaned))
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
        val snippets = listSnippets().toMutableList()
        val idx = snippets.indexOfFirst { it.id == snippet.id }
        if (idx >= 0) snippets[idx] = snippet else snippets.add(snippet)
        settings.putString(snippetsKey, json.encodeToString(snippets))
    }

    fun deleteSnippet(id: String) {
        settings.putString(snippetsKey, json.encodeToString(listSnippets().filter { it.id != id }))
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
