package dev.macodes.ui

import com.russhwolf.settings.MapSettings
import dev.macodes.MacodesApplication
import dev.macodes.data.ConnectionMode
import dev.macodes.data.Host
import dev.macodes.data.HostAuthMethod
import dev.macodes.data.HostRepository
import dev.macodes.ssh.SftpEntry
import dev.macodes.ssh.SftpSession
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SessionManager: Add and delete sessions; enable session persistence and resume recent sessions; clean up associated data when deleting a host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MacodesApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerTest {
    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setupMain() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDownMain() {
        Dispatchers.resetMain()
    }

    private fun repo() = HostRepository(MapSettings())

    private fun host(id: String) = Host(id = id, name = id, hostname = "$id.example.com", username = "root")

    @Test
    fun openAddsSessionAndPersistsRecent() {
        val r = repo()
        val m = SessionManager(r)

        m.open(host("a"), autoReconnect = true)

        assertEquals(1, m.sessions.size)
        val recent = r.loadRecentTerminalEntries().single()
        assertEquals("a", recent.hostId)
        assertTrue(recent.createdAt > 0L)
        m.sessions.forEach { it.destroy() }
    }

    @Test
    fun restoreRecentRebuildsDisconnectedSessions() {
        val r = repo()
        r.upsertHost(host("a"))
        val m1 = SessionManager(r)
        m1.open(host("a"), autoReconnect = true, launchMode = TerminalLaunchMode.HERDR)
        val createdAt = m1.sessions.single().createdAt
        m1.sessions.forEach { it.destroy() }

        val m2 = SessionManager(r)
        m2.restoreRecent(r.listHosts(), autoReconnect = false)

        assertEquals(1, m2.sessions.size)
        assertEquals(ConnStatus.IDLE, m2.sessions.single().status)
        assertEquals(TerminalLaunchMode.HERDR, m2.sessions.single().launchMode)
        assertEquals(createdAt, m2.sessions.single().createdAt)
        assertEquals(
            "a",
            m2.sessions
                .single()
                .host.id,
        )
        m2.sessions.forEach { it.destroy() }
    }

    @Test
    fun removeDestroysController() {
        val r = repo()
        val m = SessionManager(r)
        val c = m.open(host("a"), autoReconnect = true)
        assertEquals(1, m.sessions.size)

        m.remove(c)

        assertTrue(m.sessions.isEmpty())
        assertTrue(r.loadRecentTerminalEntries().isEmpty())
    }

    @Test
    fun closeForHostDestroysAllSessions() {
        val r = repo()
        val m = SessionManager(r)
        m.open(host("a"), autoReconnect = true)
        m.open(host("a"), autoReconnect = true)
        assertEquals(2, m.sessions.size)

        m.closeForHost("a")

        assertTrue(m.sessions.isEmpty())
        assertTrue(r.loadRecentTerminalEntries().isEmpty())
    }

    @Test
    fun closeAllForHostDisconnectsSftpButKeepsEntry() {
        // Semantic change: "Close" card = "Disconnect and retain" (consistent with the terminal); entries are no longer directly deleted;
        // Entering the tab can reconnect and resume the original browsing path.
        val r = repo()
        val m = SessionManager(r)
        val h = host("a")
        var closed = false
        val fake =
            object : SftpSession {
                override fun list(path: String) = emptyList<SftpEntry>()

                override fun mkdir(path: String) {}

                override fun delete(path: String) {}

                override fun rename(
                    oldPath: String,
                    newPath: String,
                ) {}

                override fun home() = "/home/root"

                override fun upload(
                    remotePath: String,
                    totalSize: Long,
                    onProgress: (sent: Long, total: Long) -> Unit,
                    nextChunk: () -> ByteArray?,
                ) {
                }

                override fun download(
                    remotePath: String,
                    onProgress: (loaded: Long, total: Long) -> Unit,
                    onChunk: (ByteArray) -> Unit,
                ) {
                }

                override fun close() {
                    closed = true
                }
            }
        m.addSftp(h, fake, Any())
        assertEquals(1, m.sftpSessions.size)

        m.closeAllForHost(h.id)

        assertTrue(closed, "底层连接应被关闭")
        assertEquals(1, m.sftpSessions.size, "条目应保留（断开可重连）")
        assertNull(m.sftpSessions.first().session, "session 应置空（断开态）")
        assertTrue(
            m.sftpSessions
                .first()
                .uiState.disconnected,
            "应标记断开态",
        )
        assertTrue(
            m.sftpSessions
                .first()
                .uiState.autoReconnectAttempted,
            "主动断开不自动重连",
        )
    }

    @Test
    fun signatureForChangesWhenConfigChanges() {
        val m = SessionManager(repo())
        val h = host("a")
        val base = m.signatureFor(h)

        // Any change to connection parameters → signature change (old sessions should be marked as expired)
        assertNotEquals(base, m.signatureFor(h.copy(hostname = "other.example.com")))
        assertNotEquals(base, m.signatureFor(h.copy(username = "other")))
        assertNotEquals(base, m.signatureFor(h.copy(port = 2222)))
        assertNotEquals(base, m.signatureFor(h.copy(authMethod = HostAuthMethod.PRIVATE_KEY)))
        assertNotEquals(base, m.signatureFor(h.copy(startupCommand = "tmux new -A")))
        assertNotEquals(base, m.signatureFor(h.copy(connectionMode = ConnectionMode.MOSH)))
    }

    @Test
    fun signatureForIsDeterministic() {
        val m = SessionManager(repo())
        val h = host("a")
        assertEquals(m.signatureFor(h), m.signatureFor(h))
    }

    @Test
    fun restoreRecentRestoresSftpPath() {
        val r = repo()
        val h = host("sftp-host")
        r.upsertHost(h)
        // Persistence of SFTP entries with paths (browsing location from before the app was killed)
        r.saveRecentSftpEntries(listOf(HostRepository.RecentSftpEntry(h.id, "/var/www", createdAt = 1234L)))

        val m = SessionManager(r)
        m.restoreRecent(listOf(h), autoReconnect = true)

        assertEquals(1, m.sftpSessions.size)
        assertEquals(h.id, m.sftpSessions[0].host.id)
        assertEquals(null, m.sftpSessions[0].session) // Not-connected entries
        assertEquals("/var/www", m.sftpSessions[0].uiState.path) // Path Recovery
        assertEquals(1234L, m.sftpSessions[0].createdAt)
    }

    @Test
    fun restoreRecentPersistsGeneratedTimeForLegacyEntries() {
        val r = repo()
        val h = host("legacy-host")
        r.upsertHost(h)
        r.saveRecentTerminalEntries(listOf(HostRepository.RecentTerminalEntry(h.id)))
        r.saveRecentSftpEntries(listOf(HostRepository.RecentSftpEntry(h.id)))

        val m = SessionManager(r)
        m.restoreRecent(listOf(h), autoReconnect = true)

        val terminalCreatedAt = m.sessions.single().createdAt
        val sftpCreatedAt = m.sftpSessions.single().createdAt
        assertTrue(terminalCreatedAt > 0L)
        assertTrue(sftpCreatedAt > 0L)
        assertEquals(terminalCreatedAt, r.loadRecentTerminalEntries().single().createdAt)
        assertEquals(sftpCreatedAt, r.loadRecentSftpEntries().single().createdAt)
        m.sessions.forEach { it.destroy() }
    }

    @Test
    fun addSftpReplacesExistingEntryForSameHost() {
        val r = repo()
        val h = host("sftp-host")
        val m = SessionManager(r)
        // First there is a restored entry (session=null), then connecting to the same host → replace rather than duplicate.
        m.sftpSessions.add(SftpSessionEntry(h, null, createdAt = 1234L))

        val fake =
            object : SftpSession {
                override fun list(path: String) = emptyList<SftpEntry>()

                override fun mkdir(path: String) {}

                override fun delete(path: String) {}

                override fun rename(
                    oldPath: String,
                    newPath: String,
                ) {}

                override fun home() = "/home/root"

                override fun upload(
                    remotePath: String,
                    totalSize: Long,
                    onProgress: (sent: Long, total: Long) -> Unit,
                    nextChunk: () -> ByteArray?,
                ) {
                }

                override fun download(
                    remotePath: String,
                    onProgress: (loaded: Long, total: Long) -> Unit,
                    onChunk: (ByteArray) -> Unit,
                ) {
                }

                override fun close() {}
            }
        val entry = m.addSftp(h, fake, Any())

        assertEquals(1, m.sftpSessions.size)
        assertEquals(fake, m.sftpSessions[0].session)
        assertEquals(fake, entry.session)
        assertEquals(1234L, entry.createdAt)
    }
}
