package dev.termish.data

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HostRepository: CRUD, backup of corrupted data, recent sessions, persistence settings. */
class HostRepositoryTest {
    private fun repo() = HostRepository(MapSettings())

    private fun host(
        id: String,
        name: String = "host-$id",
    ) = Host(id = id, name = name, hostname = "$id.example.com", username = "root")

    @Test
    fun freshInstallNotificationsRequireOptIn() {
        assertFalse(repo().loadSettings().notificationEnabled)
    }

    @Test
    fun hostsCrud() {
        val r = repo()
        assertTrue(r.listHosts().isEmpty())

        r.upsertHost(host("a"))
        r.upsertHost(host("b"))
        assertEquals(listOf("a", "b"), r.listHosts().map { it.id })

        // Overwrite updates with the same ID
        r.upsertHost(host("a", name = "renamed"))
        assertEquals("renamed", r.getHost("a")!!.name)
        assertEquals(2, r.listHosts().size)

        r.deleteHost("a")
        assertNull(r.getHost("a"))
        assertEquals(listOf("b"), r.listHosts().map { it.id })
    }

    @Test
    fun touchConnectedRecordsTimeAndFingerprint() {
        val r = repo()
        r.upsertHost(host("a"))
        val before = r.getHost("a")!!.lastConnectedAt

        r.touchConnected("a", "SHA256:f")
        val after = r.getHost("a")!!
        assertTrue(after.lastConnectedAt >= before)
        assertEquals("SHA256:f", after.knownHostFingerprint)

        // A null fingerprint does not overwrite existing values
        r.touchConnected("a", null)
        assertEquals("SHA256:f", r.getHost("a")!!.knownHostFingerprint)
    }

    @Test
    fun corruptHostsDataBacksUpInsteadOfOverwriting() {
        val settings = MapSettings()
        settings.putString("termish.hosts.v1", "{not-json")
        val r = HostRepository(settings)

        assertTrue(r.listHosts().isEmpty())
        val corruptKeys = settings.keys.filter { it.startsWith("termish.hosts.v1.corrupt.") }
        assertTrue(corruptKeys.isNotEmpty())
        assertTrue(corruptKeys.map { settings.getStringOrNull(it) }.contains("{not-json"))

        // Subsequent writes must not lose the original corrupted string (add a backup each time there is a failure to decode, up to a maximum of 3 backups).
        r.upsertHost(host("a"))
        assertEquals(1, r.listHosts().size)
        val values =
            settings.keys
                .filter { it.startsWith("termish.hosts.v1.corrupt.") }
                .map { settings.getStringOrNull(it) }
        assertTrue(values.contains("{not-json"))
    }

    @Test
    fun corruptSettingsFallBackToDefaults() {
        val settings = MapSettings()
        settings.putString("termish.settings.v1", "{broken")
        val r = HostRepository(settings)

        assertEquals(AppSettings(), r.loadSettings())
        assertTrue(settings.keys.any { it.startsWith("termish.settings.v1.corrupt.") })
    }

    @Test
    fun settingsRoundtrip() {
        val r = repo()
        assertEquals(AppSettings(), r.loadSettings())

        r.saveSettings(
            AppSettings(
                theme = ThemeMode.LIGHT,
                terminalFontSize = 16,
                autoReconnect = false,
            ),
        )
        val loaded = r.loadSettings()
        assertEquals(ThemeMode.LIGHT, loaded.theme)
        assertEquals(16, loaded.terminalFontSize)
        assertTrue(!loaded.autoReconnect)
    }

    @Test
    fun fieldUpdatesUseLatestSettingsWithoutOverwritingOtherFeatures() {
        val r = repo()
        val provider = AgentProvider(id = "provider-1", name = "test")

        r.updateSettings { it.copy(agentProviders = listOf(provider)) }
        // When mocking the settings page and subsequently modifying the theme: the changes must be based on the repository’s latest values, not a snapshot taken before the Agent page was opened.
        r.updateSettings { it.copy(theme = ThemeMode.LIGHT) }

        assertEquals(ThemeMode.LIGHT, r.loadSettings().theme)
        assertEquals(listOf(provider), r.loadSettings().agentProviders)
        assertEquals(r.loadSettings(), r.appSettings.value)
    }

    @Test
    fun recentSessionsRoundtrip() {
        val r = repo()
        assertTrue(r.loadRecentTerminalEntries().isEmpty())

        val entries =
            listOf(
                HostRepository.RecentTerminalEntry("a"),
                HostRepository.RecentTerminalEntry("b", launchHerdr = true),
            )
        r.saveRecentTerminalEntries(entries)
        assertEquals(entries, r.loadRecentTerminalEntries())

        r.saveRecentTerminalEntries(emptyList())
        assertTrue(r.loadRecentTerminalEntries().isEmpty())
    }

    @Test
    fun recentSftpRoundtripWithPath() {
        val r = repo()
        assertTrue(r.loadRecentSftpEntries().isEmpty())

        // Persistence of the path with each entry: Resume the last-viewed directory after killing and restarting the app.
        r.saveRecentSftpEntries(
            listOf(
                HostRepository.RecentSftpEntry("host-a", "/var/www"),
                HostRepository.RecentSftpEntry("host-b"), // The default path is empty.
            ),
        )
        val loaded = r.loadRecentSftpEntries()
        assertEquals(2, loaded.size)
        assertEquals("host-a", loaded[0].hostId)
        assertEquals("/var/www", loaded[0].path)
        assertEquals("", loaded[1].path)

        r.saveRecentSftpEntries(emptyList())
        assertTrue(r.loadRecentSftpEntries().isEmpty())
    }

// ---------- Command Snippets + Tag Groups ----------

    private fun snippet(
        id: String,
        name: String = "snip-$id",
        tagIds: List<String> = emptyList(),
    ) = Snippet(id = id, name = name, content = "echo $id", tagIds = tagIds, updatedAt = 1L)

    @Test
    fun tagGroupsCrud() {
        val r = repo()
        assertTrue(r.listTagGroups().isEmpty())

        r.upsertTagGroup(TagGroup("t1", "docker"))
        r.upsertTagGroup(TagGroup("t2", "git"))
        assertEquals(listOf("t1", "t2"), r.listTagGroups().map { it.id })

        // Overwrite with the same name (rename)
        r.upsertTagGroup(TagGroup("t1", "containers"))
        assertEquals("containers", r.listTagGroups().first { it.id == "t1" }.name)
        assertEquals(2, r.listTagGroups().size)

        r.deleteTagGroup("t2")
        assertEquals(listOf("t1"), r.listTagGroups().map { it.id })
    }

    @Test
    fun snippetsCrud() {
        val r = repo()
        assertTrue(r.listSnippets().isEmpty())

        r.upsertSnippet(snippet("a"))
        r.upsertSnippet(snippet("b"))
        assertEquals(listOf("a", "b"), r.listSnippets().map { it.id })

        r.upsertSnippet(snippet("a", name = "renamed"))
        assertEquals("renamed", r.getSnippet("a")!!.name)
        assertEquals(2, r.listSnippets().size)

        r.deleteSnippet("a")
        assertNull(r.getSnippet("a"))
        assertEquals(listOf("b"), r.listSnippets().map { it.id })
    }

    @Test
    fun deleteTagGroupCleansSnippetReferences() {
        val r = repo()
        r.upsertTagGroup(TagGroup("t1", "docker"))
        r.upsertSnippet(snippet("a", tagIds = listOf("t1")))
        r.upsertSnippet(snippet("b", tagIds = listOf("t1", "t2"))) // Should t2 be left as is even if it doesn’t exist? No—only clear t1.
        r.upsertSnippet(snippet("c")) // Entries without tags are not affected.

        r.deleteTagGroup("t1")

        assertEquals(emptyList<String>(), r.getSnippet("a")!!.tagIds, "引用 t1 的片段应被清理")
        assertEquals(listOf("t2"), r.getSnippet("b")!!.tagIds, "其他标签引用保留")
        assertEquals(emptyList<String>(), r.getSnippet("c")!!.tagIds, "无标签片段不受影响")
        // The snippet itself is not deleted.
        assertEquals(listOf("a", "b", "c"), r.listSnippets().map { it.id })
    }

    @Test
    fun deleteTagGroupWithoutReferencesSkipsSnippetWrite() {
        // Deleting unreferenced tags should not rewrite snippet storage (to avoid unnecessary writes).
        val r = repo()
        r.upsertTagGroup(TagGroup("t1", "docker"))
        r.upsertSnippet(snippet("a"))

        r.deleteTagGroup("t1")
        assertEquals(emptyList<String>(), r.getSnippet("a")!!.tagIds)
        assertEquals(1, r.listSnippets().size)
    }

    @Test
    fun corruptSnippetsBacksUp() {
        val settings = MapSettings()
        settings.putString("termish.snippets.v1", "{not-json")
        val r = HostRepository(settings)

        assertTrue(r.listSnippets().isEmpty())
        assertTrue(settings.keys.any { it.startsWith("termish.snippets.v1.corrupt.") }, "损坏数据应备份")
    }
}
