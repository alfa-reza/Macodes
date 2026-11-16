package dev.macodes.data

import com.russhwolf.settings.MapSettings
import dev.macodes.ssh.SshErrorCode
import dev.macodes.ssh.SshException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SessionLogTest {
    private val json = Json { encodeDefaults = false }

    private fun host(
        id: String = "host-1",
        name: String = "Prod API",
        hostname: String = "10.0.1.20",
        port: Int = 2222,
        username: String = "ubuntu",
    ) = Host(
        id = id,
        name = name,
        hostname = hostname,
        port = port,
        username = username,
    )

    @Test
    fun sshCompletedLifecycleRecordsEventsAndDuration() {
        var now = 1_760_000_000_000L
        val saved = mutableListOf<SessionLog>()
        val rec = SessionLogRecorder(host(), ConnectionLogProtocol.SSH, { saved += it }, clock = { now })

        rec.record(SessionLogEventType.CONNECTION_STARTED)
        now += 1_000L
        rec.markHostVerification(true)
        rec.record(SessionLogEventType.CONNECTION_ESTABLISHED)
        now += 1_000L
        rec.markAuthSucceeded("key")
        rec.markSessionOpened()
        now += 18L * 60_000L + 12_000L
        rec.completeNormal("user")

        // Repeated terminal calls must be idempotent
        rec.interrupt(reason = "late callback")
        rec.fail(reason = "late failure")

        assertEquals(1, saved.size)
        val log = saved.single()
        assertEquals(SessionLogStatus.COMPLETED, log.status)
        assertEquals(18L * 60_000L + 12_000L, log.durationMs)
        assertNull(log.errorCode)
        assertEquals("Prod API", log.hostLabel)
        assertEquals("host-1", log.hostId)
        assertTrue(log.events.all { it.sessionId == log.id })
        assertEquals(
            listOf(
                SessionLogEventType.CONNECTION_STARTED,
                SessionLogEventType.HOST_VERIFICATION_SUCCEEDED,
                SessionLogEventType.CONNECTION_ESTABLISHED,
                SessionLogEventType.AUTHENTICATION_SUCCEEDED,
                SessionLogEventType.SESSION_OPENED,
                SessionLogEventType.SESSION_CLOSED,
            ),
            log.events.map { it.type },
        )
    }

    @Test
    fun authSuccessBeforeShellOpenFailureStaysFailedWithNullDuration() {
        var now = 1_760_000_000_000L
        val saved = mutableListOf<SessionLog>()
        val rec = SessionLogRecorder(host(), ConnectionLogProtocol.SSH, { saved += it }, clock = { now })

        rec.record(SessionLogEventType.CONNECTION_STARTED)
        now += 500L
        rec.markHostVerification(true)
        rec.markAuthSucceeded("password")
        now += 500L
        rec.fail(SshException("PTY allocation denied", code = null))

        assertEquals(1, saved.size)
        val log = saved.single()
        assertEquals(SessionLogStatus.FAILED, log.status)
        assertNull(log.durationMs)
        assertEquals(SessionLogErrorCode.TRANSPORT_DISCONNECTED, log.errorCode)
        assertEquals(SessionLogEventType.CONNECTION_FAILED, log.events.last().type)
    }

    @Test
    fun moshUdpTimeoutDistinguishesSshBootstrapAndUdpFailure() {
        var now = 1_760_000_000_000L
        val saved = mutableListOf<SessionLog>()
        val rec = SessionLogRecorder(host(), ConnectionLogProtocol.MOSH, { saved += it }, clock = { now })

        rec.record(SessionLogEventType.SSH_BOOTSTRAP_STARTED)
        now += 1_000L
        rec.markAuthSucceeded("key")
        now += 1_000L
        rec.record(SessionLogEventType.SSH_BOOTSTRAP_SUCCEEDED)
        now += 10_000L
        rec.fail(
            explicitCode = SessionLogErrorCode.UDP_TIMED_OUT,
            terminalEvent = SessionLogEventType.UDP_CONNECTION_TIMED_OUT,
        )

        val log = saved.single()
        assertEquals(ConnectionLogProtocol.MOSH, log.protocol)
        assertEquals(SessionLogStatus.FAILED, log.status)
        assertEquals(SessionLogErrorCode.UDP_TIMED_OUT, log.errorCode)
        assertNull(log.durationMs)
        assertEquals(
            listOf(
                SessionLogEventType.SSH_BOOTSTRAP_STARTED,
                SessionLogEventType.AUTHENTICATION_SUCCEEDED,
                SessionLogEventType.SSH_BOOTSTRAP_SUCCEEDED,
                SessionLogEventType.UDP_CONNECTION_TIMED_OUT,
            ),
            log.events.map { it.type },
        )
    }

    @Test
    fun reconnectRetriesDoNotDuplicateEventsOrSessionEntries() {
        var now = 1_760_000_000_000L
        val saved = mutableListOf<SessionLog>()
        val rec = SessionLogRecorder(host(), ConnectionLogProtocol.SSH, { saved += it }, clock = { now })

        rec.record(SessionLogEventType.CONNECTION_STARTED)
        rec.markSessionOpened()
        now += 5_000L
        rec.beginReconnectAttempt()
        rec.record(SessionLogEventType.CONNECTION_STARTED)
        rec.record(SessionLogEventType.CONNECTION_STARTED)
        now += 3_000L
        rec.interrupt(SshException("Connection timed out", code = null))

        assertEquals(1, saved.size)
        val log = saved.single()
        assertEquals(SessionLogStatus.INTERRUPTED, log.status)
        assertNotNull(log.durationMs)
        assertEquals(8_000L, log.durationMs)
        assertEquals(1, log.events.count { it.type == SessionLogEventType.CONNECTION_STARTED })
        assertEquals(SessionLogEventType.CONNECTION_INTERRUPTED, log.events.last().type)
    }

    @Test
    fun quickConnectUnnamedHostFallsBackToUsernameAtHostnameAndNullHostId() {
        val qcHost =
            host(
                id = quickConnectHostId(ConnectionMode.SSH, "192.168.1.50", 2222),
                name = "192.168.1.50",
                hostname = "192.168.1.50",
                port = 2222,
                username = "alice",
            )
        val saved = mutableListOf<SessionLog>()
        val rec = SessionLogRecorder(qcHost, ConnectionLogProtocol.SSH, { saved += it })
        rec.record(SessionLogEventType.CONNECTION_STARTED)
        rec.fail(SshException("bad password", code = SshErrorCode.AUTH_FAILED))

        val log = saved.single()
        assertNull(log.hostId)
        assertEquals("alice@192.168.1.50", log.hostLabel)
    }

    @Test
    fun owaspSanitizationNeverPersistsSecretsKeysTokensOrRawExceptions() {
        val saved = mutableListOf<SessionLog>()
        val rec = SessionLogRecorder(host(), ConnectionLogProtocol.SFTP, { saved += it })
        rec.record(SessionLogEventType.CONNECTION_STARTED, detailCode = "password=super-secret-123")
        rec.fail(
            error =
                RuntimeException(
                    "-----BEGIN OPENSSH PRIVATE KEY----- b3BlbnNzaC1rZXktdjE= Bearer tok_12345 " +
                        "MOSH CONNECT 60001 AAAAAAAAAAAAAAAAAAAAAA /etc/shadow",
                ),
            reason = "Authorization: Bearer secret_token_xyz",
        )

        val serialized = json.encodeToString(saved.single())
        assertFalse(serialized.contains("super-secret-123"))
        assertFalse(serialized.contains("BEGIN OPENSSH PRIVATE KEY"))
        assertFalse(serialized.contains("Bearer"))
        assertFalse(serialized.contains("MOSH CONNECT"))
        assertFalse(serialized.contains("/etc/shadow"))
        assertFalse(serialized.contains("tok_12345"))
    }

    @Test
    fun repositorySearchFilterAndSortQueriesWorkCombined() {
        val repo = HostRepository(MapSettings())
        val now = 1_760_000_000_000L
        repo.saveSessionLog(
            SessionLog(
                id = "s1",
                hostLabel = "Alpha",
                username = "ops",
                hostname = "10.0.0.1",
                port = 22,
                protocol = ConnectionLogProtocol.SSH,
                startedAt = now - 30_000L,
                endedAt = now - 20_000L,
                durationMs = 10_000L,
                status = SessionLogStatus.COMPLETED,
            ),
            now = now,
        )
        repo.saveSessionLog(
            SessionLog(
                id = "s2",
                hostLabel = "Beta Mosh",
                username = "dev",
                hostname = "10.0.0.2",
                port = 2222,
                protocol = ConnectionLogProtocol.MOSH,
                startedAt = now - 15_000L,
                endedAt = now - 10_000L,
                durationMs = null,
                status = SessionLogStatus.FAILED,
                errorCode = SessionLogErrorCode.UDP_TIMED_OUT,
            ),
            now = now,
        )
        repo.saveSessionLog(
            SessionLog(
                id = "s3",
                hostLabel = "Gamma SFTP",
                username = "sftp",
                hostname = "10.0.0.3",
                port = 22,
                protocol = ConnectionLogProtocol.SFTP,
                startedAt = now - 5_000L,
                endedAt = now - 1_000L,
                durationMs = 4_000L,
                status = SessionLogStatus.INTERRUPTED,
                errorCode = SessionLogErrorCode.TRANSPORT_DISCONNECTED,
            ),
            now = now,
        )

        val failedMosh =
            repo.listSessionLogs(
                SessionLogQuery(
                    search = "beta",
                    status = SessionLogStatus.FAILED,
                    protocol = ConnectionLogProtocol.MOSH,
                ),
                now = now,
            )
        assertEquals(listOf("s2"), failedMosh.map { it.id })

        val oldestFirst =
            repo.listSessionLogs(
                SessionLogQuery(sortOrder = SessionLogSortOrder.OLDEST_FIRST),
                now = now,
            )
        assertEquals(listOf("s1", "s2", "s3"), oldestFirst.map { it.id })
    }

    @Test
    fun defaultEpochClockSurvivesRepositoryRetention() {
        val repo = HostRepository(MapSettings())
        val rec = SessionLogRecorder(host(), ConnectionLogProtocol.SSH, { repo.saveSessionLog(it) })
        rec.record(SessionLogEventType.CONNECTION_STARTED)
        rec.markSessionOpened()
        rec.completeNormal("user")

        val logs = repo.listSessionLogs()
        assertEquals(1, logs.size)
        assertTrue(logs.single().startedAt > 1_700_000_000_000L)
    }
}
