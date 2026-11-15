package dev.macodes.data

import com.russhwolf.settings.MapSettings
import dev.macodes.ssh.SshErrorCode
import dev.macodes.term.TerminalBuffer
import dev.macodes.term.TerminalEmulator
import dev.macodes.ui.QuickConnectFailureKind
import dev.macodes.ui.QuickConnectSuggestion
import dev.macodes.ui.advanceQuickConnectWizard
import dev.macodes.ui.appStringsFor
import dev.macodes.ui.buildQuickConnectFailureSummary
import dev.macodes.ui.buildQuickConnectSuggestions
import dev.macodes.ui.createWizardState
import dev.macodes.ui.extractTerminalPreviewText
import dev.macodes.ui.formatQuickConnectConnectionError
import dev.macodes.ui.formatQuickConnectHistoryMeta
import dev.macodes.ui.formatQuickConnectHistorySubtitle
import dev.macodes.ui.formatQuickConnectParseError
import dev.macodes.ui.formatSessionCreatedAt
import dev.macodes.ui.retreatQuickConnectWizard
import dev.macodes.ui.splitSessionTimestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

class QuickConnectTest {
    private val strings = appStringsFor("en").quickConnect

    private fun parseSuccess(input: String): QuickConnectTarget {
        val result = QuickConnectParser.parse(input)
        return assertIs<QuickConnectParseResult.Success>(result).target
    }

    @Test
    fun parsesAllSpecExamplesAndComputesExpectedWizardSteps() {
        val bareIp = parseSuccess("192.168.0.1")
        assertNull(bareIp.protocol)
        assertNull(bareIp.username)
        assertEquals(22, bareIp.sshPort)
        assertEquals("192.168.0.1", bareIp.host)
        assertEquals(
            listOf(
                QuickConnectStep.PROTOCOL,
                QuickConnectStep.USERNAME,
                QuickConnectStep.PASSWORD,
            ),
            QuickConnectParser.stepsFor(bareIp),
        )

        val userAtIp = parseSuccess("root@192.168.0.1")
        assertNull(userAtIp.protocol)
        assertEquals("root", userAtIp.username)
        assertEquals("192.168.0.1", userAtIp.host)
        assertEquals(
            listOf(QuickConnectStep.PROTOCOL, QuickConnectStep.PASSWORD),
            QuickConnectParser.stepsFor(userAtIp),
        )

        val sshHost = parseSuccess("ssh 192.168.0.1")
        assertEquals(ConnectionMode.SSH, sshHost.protocol)
        assertNull(sshHost.username)
        assertEquals(
            listOf(QuickConnectStep.USERNAME, QuickConnectStep.PASSWORD),
            QuickConnectParser.stepsFor(sshHost),
        )

        val sshUserHost = parseSuccess("ssh root@192.168.0.1")
        assertEquals(ConnectionMode.SSH, sshUserHost.protocol)
        assertEquals("root", sshUserHost.username)
        assertEquals(22, sshUserHost.sshPort)
        assertEquals(listOf(QuickConnectStep.PASSWORD), QuickConnectParser.stepsFor(sshUserHost))

        val sshPortBefore = parseSuccess("ssh -p 2222 root@192.168.0.1")
        assertEquals(ConnectionMode.SSH, sshPortBefore.protocol)
        assertEquals("root", sshPortBefore.username)
        assertEquals(2222, sshPortBefore.sshPort)
        assertEquals(listOf(QuickConnectStep.PASSWORD), QuickConnectParser.stepsFor(sshPortBefore))

        val sshPortAfter = parseSuccess("ssh root@192.168.0.1 -p 2222")
        assertEquals(ConnectionMode.SSH, sshPortAfter.protocol)
        assertEquals("root", sshPortAfter.username)
        assertEquals(2222, sshPortAfter.sshPort)
        assertEquals(listOf(QuickConnectStep.PASSWORD), QuickConnectParser.stepsFor(sshPortAfter))

        val sshLoginFlag = parseSuccess("ssh -l root 192.168.0.1")
        assertEquals(ConnectionMode.SSH, sshLoginFlag.protocol)
        assertEquals("root", sshLoginFlag.username)
        assertEquals("192.168.0.1", sshLoginFlag.host)
        assertEquals(listOf(QuickConnectStep.PASSWORD), QuickConnectParser.stepsFor(sshLoginFlag))

        val sshUri = parseSuccess("ssh://root@192.168.0.1:2222")
        assertEquals(ConnectionMode.SSH, sshUri.protocol)
        assertEquals("root", sshUri.username)
        assertEquals("192.168.0.1", sshUri.host)
        assertEquals(2222, sshUri.sshPort)
        assertEquals(listOf(QuickConnectStep.PASSWORD), QuickConnectParser.stepsFor(sshUri))

        val moshUserHost = parseSuccess("mosh root@192.168.0.1")
        assertEquals(ConnectionMode.MOSH, moshUserHost.protocol)
        assertEquals("root", moshUserHost.username)
        assertEquals(22, moshUserHost.sshPort)
        assertEquals(0, moshUserHost.moshUdpPort)
        assertEquals(listOf(QuickConnectStep.PASSWORD), QuickConnectParser.stepsFor(moshUserHost))
    }

    @Test
    fun distinguishesMoshUdpPortFromSshBootstrapPortAndSupportsIpv6() {
        val moshWithUdpAndSsh = parseSuccess("mosh -p 60005 --ssh=\"ssh -p 2222\" root@example.com")
        assertEquals(ConnectionMode.MOSH, moshWithUdpAndSsh.protocol)
        assertEquals(60005, moshWithUdpAndSsh.moshUdpPort)
        assertEquals(2222, moshWithUdpAndSsh.sshPort)
        assertEquals("root", moshWithUdpAndSsh.username)
        assertEquals("example.com", moshWithUdpAndSsh.host)

        val ipv6Uri = parseSuccess("ssh://admin@[::1]:2200")
        assertEquals("::1", ipv6Uri.host)
        assertEquals(2200, ipv6Uri.sshPort)
        assertEquals("admin", ipv6Uri.username)

        val moshServerCmd = parseSuccess("mosh --server=/opt/homebrew/bin/mosh-server --port=60002 --ssh=\"ssh -p 2222\" root@example.com")
        assertEquals(ConnectionMode.MOSH, moshServerCmd.protocol)
        assertEquals("/opt/homebrew/bin/mosh-server", moshServerCmd.moshServerCommand)
        assertEquals(60002, moshServerCmd.moshUdpPort)
        assertEquals(2222, moshServerCmd.sshPort)

        val moshFromHistory =
            QuickConnectParser.fromHistory(
                QuickConnectHistoryEntry(
                    protocol = ConnectionMode.MOSH,
                    host = "example.com",
                    port = 2222,
                    moshUdpPort = 60002,
                    moshServerCommand = "/opt/homebrew/bin/mosh-server",
                    username = "root",
                    lastConnectedAt = 1L,
                    system = "ubuntu",
                ),
            )
        assertEquals("/opt/homebrew/bin/mosh-server", moshFromHistory.moshServerCommand)
        assertEquals(60002, moshFromHistory.moshUdpPort)
    }

    @Test
    fun rejectsInvalidPortsFlagsArgumentsAndShellMetacharacters() {
        assertEquals(
            QuickConnectParseResult.Error(QuickConnectParseError.EmptyInput),
            QuickConnectParser.parse("   "),
        )
        assertEquals(
            QuickConnectParseResult.Error(QuickConnectParseError.ShellMetacharacters),
            QuickConnectParser.parse("ssh root@1.2.3.4; rm -rf /"),
        )
        assertEquals(
            QuickConnectParseResult.Error(QuickConnectParseError.ShellMetacharacters),
            QuickConnectParser.parse("ssh root@1.2.3.4 $(whoami)"),
        )
        assertEquals(
            QuickConnectParseResult.Error(QuickConnectParseError.UnsupportedFlag("-i")),
            QuickConnectParser.parse("ssh -i ~/.ssh/id_ed25519 root@1.2.3.4"),
        )
        assertEquals(
            QuickConnectParseResult.Error(QuickConnectParseError.MissingFlagValue("-p")),
            QuickConnectParser.parse("ssh -p"),
        )
        assertEquals(
            QuickConnectParseResult.Error(QuickConnectParseError.InvalidPort("99999")),
            QuickConnectParser.parse("ssh -p 99999 root@1.2.3.4"),
        )
        assertEquals(
            QuickConnectParseResult.Error(QuickConnectParseError.UnexpectedArguments),
            QuickConnectParser.parse("ssh root@1.2.3.4 extra"),
        )
        // Reject arbitrary bare non-IP strings, underscores, malformed numeric octets, and multi-colon garbage
        for (badHost in listOf("asdf", "abc_123", "foo:bar:baz", "999.999.999.999", "1.2.3", "user@badhost")) {
            assertEquals(
                QuickConnectParseResult.Error(QuickConnectParseError.InvalidHost),
                QuickConnectParser.parse(badHost),
                "Expected InvalidHost for '$badHost'",
            )
        }
        // Accept localhost, mixed-case URI/command, and multi-label domain names
        assertEquals("localhost", parseSuccess("root@localhost").host)
        val upper = parseSuccess("SSH://Admin@Sub.Example.COM:2222")
        assertEquals(ConnectionMode.SSH, upper.protocol)
        assertEquals("Admin", upper.username)
        assertEquals("Sub.Example.COM", upper.host)
        assertEquals(2222, upper.sshPort)
        assertTrue(formatQuickConnectParseError(QuickConnectParseError.ShellMetacharacters, strings).isNotBlank())
    }

    @Test
    fun wizardTransitionsValidateInputsAndSupportBackAndRetry() {
        val target = parseSuccess("192.168.0.1")
        val state = createWizardState(target)
        assertEquals(QuickConnectStep.PROTOCOL, state.currentStep)

        // Invalid edited port keeps user on Protocol step with clear validation error
        val (badPort, connectBadPort) = advanceQuickConnectWizard(state.copy(portInput = "0"), strings)
        assertFalse(connectBadPort)
        assertEquals(QuickConnectStep.PROTOCOL, badPort.currentStep)
        assertEquals(strings.errorInvalidPort("0"), badPort.errorMessage)

        // Valid protocol + port -> Username
        val (s2, connect2) =
            advanceQuickConnectWizard(badPort.copy(protocol = ConnectionMode.SSH, portInput = "2222"), strings)
        assertFalse(connect2)
        assertEquals(QuickConnectStep.USERNAME, s2.currentStep)
        assertNull(s2.errorMessage)

        // Blank username keeps user on Username step
        val (badUser, connectBadUser) = advanceQuickConnectWizard(s2.copy(usernameInput = "  "), strings)
        assertFalse(connectBadUser)
        assertEquals(QuickConnectStep.USERNAME, badUser.currentStep)
        assertEquals(strings.errorMissingUsername, badUser.errorMessage)

        // Valid username -> Password
        val (s3, connect3) = advanceQuickConnectWizard(badUser.copy(usernameInput = "alice"), strings)
        assertFalse(connect3)
        assertEquals(QuickConnectStep.PASSWORD, s3.currentStep)

        // Back button returns cleanly without losing entered data
        val backToUser = retreatQuickConnectWizard(s3)
        assertEquals(QuickConnectStep.USERNAME, backToUser.currentStep)
        assertEquals("alice", backToUser.usernameInput)

        // Blank password blocks connect; valid password signals ready-to-connect
        val (badPass, connectBadPass) = advanceQuickConnectWizard(s3.copy(passwordInput = ""), strings)
        assertFalse(connectBadPass)
        assertEquals(strings.errorMissingPassword, badPass.errorMessage)

        val (ready, shouldConnect) = advanceQuickConnectWizard(badPass.copy(passwordInput = "secret"), strings)
        assertTrue(shouldConnect)
        assertNull(ready.errorMessage)
    }

    @Test
    fun errorFormatterMapsAuthTimeoutRefusedPasswordUnsupportedAndMoshErrors() {
        assertEquals(
            strings.errorPasswordNotSupported,
            formatQuickConnectConnectionError(
                rawMessage = "anything",
                protocol = ConnectionMode.SSH,
                s = strings,
                errorCode = SshErrorCode.PASSWORD_AUTH_UNSUPPORTED,
            ),
        )
        assertEquals(
            strings.errorAuthFailed,
            formatQuickConnectConnectionError(
                rawMessage = "failed",
                protocol = ConnectionMode.SSH,
                s = strings,
                errorCode = SshErrorCode.AUTH_FAILED,
            ),
        )
        assertEquals(
            strings.errorPasswordNotSupported,
            formatQuickConnectConnectionError(
                "Password authentication is not supported by this server",
                ConnectionMode.SSH,
                strings,
            ),
        )
        assertEquals(
            strings.errorAuthFailed,
            formatQuickConnectConnectionError("Authentication failed", ConnectionMode.SSH, strings),
        )
        assertEquals(
            strings.errorTimeout,
            formatQuickConnectConnectionError("Connect timed out", ConnectionMode.SSH, strings),
        )
        assertEquals(
            strings.errorRefused,
            formatQuickConnectConnectionError("Connection refused", ConnectionMode.SSH, strings),
        )
        assertEquals(
            strings.errorUnreachable,
            formatQuickConnectConnectionError("No route to host", ConnectionMode.SSH, strings),
        )
        assertEquals(
            strings.errorMoshBootstrap("mosh-server: command not found"),
            formatQuickConnectConnectionError("mosh-server: command not found", ConnectionMode.MOSH, strings),
        )
    }

    @Test
    fun quickConnectHistoryAndHostKeysStaySeparateFromSavedHostsWithoutStoringPasswords() {
        val repo = HostRepository(settings = MapSettings())
        assertTrue(repo.listHosts().isEmpty())
        assertTrue(repo.listQuickConnectHistory().isEmpty())

        val first =
            QuickConnectHistoryEntry(
                protocol = ConnectionMode.SSH,
                host = "10.0.0.5",
                port = 22,
                username = "ubuntu",
                lastConnectedAt = 1_000L,
            )
        val second =
            QuickConnectHistoryEntry(
                protocol = ConnectionMode.MOSH,
                host = "10.0.0.8",
                port = 2222,
                moshUdpPort = 60001,
                username = "root",
                lastConnectedAt = 2_000L,
            )
        repo.recordQuickConnectHistory(first)
        repo.recordQuickConnectHistory(second)
        // Re-recording first promotes it to most-recent and deduplicates
        repo.recordQuickConnectHistory(first.copy(lastConnectedAt = 3_000L))

        val history = repo.listQuickConnectHistory()
        assertEquals(2, history.size)
        assertEquals("10.0.0.5", history[0].host)
        assertEquals(3_000L, history[0].lastConnectedAt)
        assertEquals("10.0.0.8", history[1].host)

        // Clicking a history entry produces a target needing only the Password step
        val historyTarget = QuickConnectParser.fromHistory(history[1])
        assertEquals(listOf(QuickConnectStep.PASSWORD), QuickConnectParser.stepsFor(historyTarget))

        // Quick Connect never writes into Saved Hosts and stores TOFU fingerprints separately
        assertTrue(repo.listHosts().isEmpty())
        assertTrue(quickConnectHostId(ConnectionMode.SSH, "10.0.0.5", 22).startsWith("quickconnect:"))
        repo.recordQuickConnectFingerprint("10.0.0.5", 22, "SHA256:abc123")
        assertEquals("SHA256:abc123", repo.getQuickConnectFingerprint("10.0.0.5", 22))

        repo.deleteQuickConnectHistory(second)
        assertEquals(1, repo.listQuickConnectHistory().size)
        repo.clearQuickConnectHistory()
        assertTrue(repo.listQuickConnectHistory().isEmpty())
    }

    @Test
    fun classifiesConnectionFailureScenariosWithHints() {
        val s = appStringsFor("en").quickConnect
        assertEquals(
            QuickConnectFailureKind.REFUSED,
            buildQuickConnectFailureSummary("java.net.ConnectException: Connection refused", ConnectionMode.SSH, s).kind,
        )
        assertEquals(
            QuickConnectFailureKind.TIMEOUT,
            buildQuickConnectFailureSummary("SocketTimeoutException: connect timed out", ConnectionMode.SSH, s).kind,
        )
        assertEquals(
            QuickConnectFailureKind.UNREACHABLE,
            buildQuickConnectFailureSummary("UnknownHostException: bad.invalid", ConnectionMode.SSH, s).kind,
        )
        assertEquals(
            QuickConnectFailureKind.CONNECTION_RESET,
            buildQuickConnectFailureSummary("Connection reset by peer", ConnectionMode.SSH, s).kind,
        )
        assertEquals(
            QuickConnectFailureKind.NEGOTIATION_FAILED,
            buildQuickConnectFailureSummary("Unable to negotiate: no matching key exchange method", ConnectionMode.SSH, s).kind,
        )
        assertEquals(
            QuickConnectFailureKind.MOSH_UDP,
            buildQuickConnectFailureSummary("Mosh UDP handshake timed out", ConnectionMode.MOSH, s).kind,
        )
    }

    @Test
    fun extractsLastFiveNonBlankLinesForSessionPreview() {
        val buffer = TerminalBuffer(80, 12)
        val emulator = TerminalEmulator(buffer)
        emulator.write("line 1\r\nline 2\r\nline 3\r\nline 4\r\nline 5\r\nline 6\r\n\r\n".encodeToByteArray())
        assertEquals("line 2\nline 3\nline 4\nline 5\nline 6", extractTerminalPreviewText(buffer))
    }

    @Test
    fun parsesSftpCommandAndUri() {
        val cmd = assertIs<QuickConnectParseResult.Success>(QuickConnectParser.parse("sftp -P 2222 deploy@10.0.0.5")).target
        assertTrue(cmd.openSftp)
        assertEquals(ConnectionMode.SSH, cmd.protocol)
        assertEquals(2222, cmd.sshPort)
        assertEquals("deploy", cmd.username)
        assertEquals(listOf(QuickConnectStep.PASSWORD), QuickConnectParser.stepsFor(cmd))

        val uri = assertIs<QuickConnectParseResult.Success>(QuickConnectParser.parse("sftp://root@10.0.0.5:2200")).target
        assertTrue(uri.openSftp)
        assertEquals(ConnectionMode.SSH, uri.protocol)
        assertEquals(2200, uri.sshPort)
        assertEquals("root", uri.username)
    }

    @Test
    fun formatsQuickConnectHistorySubtitlesCleanly() {
        val ts = 1_731_580_000_000L
        val sshDefault =
            QuickConnectHistoryEntry(
                protocol = ConnectionMode.SSH,
                host = "192.168.0.10",
                port = 22,
                username = "ubuntu",
                lastConnectedAt = ts,
            )
        val sshDefaultSub = formatQuickConnectHistorySubtitle(sshDefault, strings)
        assertFalse(":22" in sshDefaultSub)
        assertFalse("Port" in sshDefaultSub)

        val sshCustom = sshDefault.copy(port = 2222)
        assertTrue(formatQuickConnectHistorySubtitle(sshCustom, strings).startsWith("Port 2222 · "))

        val moshDefault =
            QuickConnectHistoryEntry(
                protocol = ConnectionMode.MOSH,
                host = "10.0.0.25",
                port = 22,
                username = "deploy",
                lastConnectedAt = ts,
            )
        assertTrue(formatQuickConnectHistorySubtitle(moshDefault, strings).startsWith("UDP auto · "))

        val moshCustom = moshDefault.copy(port = 2222, moshUdpPort = 60001)
        assertTrue(formatQuickConnectHistorySubtitle(moshCustom, strings).startsWith("SSH 2222 · UDP 60001 · "))
    }

    @Test
    fun buildsDeduplicatedAutocompleteSuggestions() {
        val saved =
            listOf(
                Host(id = "h1", name = "Prod Web", hostname = "192.168.0.10", port = 22, username = "ubuntu"),
                Host(id = "h2", name = "Staging", hostname = "192.168.0.11", port = 22, username = "ubuntu"),
            )
        val hist =
            listOf(
                QuickConnectHistoryEntry(ConnectionMode.SSH, "192.168.0.10", 22, username = "ubuntu", lastConnectedAt = 1L),
                QuickConnectHistoryEntry(ConnectionMode.MOSH, "192.168.0.12", 22, username = "deploy", lastConnectedAt = 2L),
                QuickConnectHistoryEntry(ConnectionMode.SSH, "192.168.0.13", 2222, username = "root", lastConnectedAt = 3L),
            )
        assertTrue(buildQuickConnectSuggestions("", saved, hist).isEmpty())

        val matches = buildQuickConnectSuggestions("ssh 192.168.0", saved, hist)
        assertEquals(3, matches.size)
        assertIs<QuickConnectSuggestion.SavedHost>(matches[0])
        assertIs<QuickConnectSuggestion.SavedHost>(matches[1])
        assertIs<QuickConnectSuggestion.History>(matches[2])
    }

    @Test
    fun keepsSshAndSftpQuickConnectHistoryEntriesDistinct() {
        val repo = HostRepository(settings = MapSettings())
        val sshEntry = QuickConnectHistoryEntry(ConnectionMode.SSH, "10.0.0.5", 22, username = "ubuntu", lastConnectedAt = 100L)
        val sftpEntry = sshEntry.copy(lastConnectedAt = 200L, openSftp = true)
        repo.recordQuickConnectHistory(sshEntry)
        repo.recordQuickConnectHistory(sftpEntry)

        val stored = repo.listQuickConnectHistory()
        assertEquals(2, stored.size)
        assertTrue(stored.any { !it.openSftp })
        assertTrue(stored.any { it.openSftp })

        repo.deleteQuickConnectHistory(sftpEntry)
        val remaining = repo.listQuickConnectHistory()
        assertEquals(1, remaining.size)
        assertFalse(remaining[0].openSftp)
    }

    @Test
    fun formatsHistoryRowMetaAndAdaptiveTimestamps() {
        val en = appStringsFor("en")
        val zh = appStringsFor("zh")
        val ssh = QuickConnectHistoryEntry(ConnectionMode.SSH, "192.168.0.10", 22, username = "ubuntu", lastConnectedAt = 0L)
        val mosh =
            QuickConnectHistoryEntry(
                ConnectionMode.MOSH,
                "10.0.0.25",
                2222,
                moshUdpPort = 60001,
                username = "deploy",
                lastConnectedAt = 0L,
            )
        val sftp =
            QuickConnectHistoryEntry(
                ConnectionMode.SSH,
                "files.prod.internal",
                22,
                username = "backup",
                lastConnectedAt = 0L,
                openSftp = true,
            )

        assertEquals("SSH · Port 22", formatQuickConnectHistoryMeta(ssh, en.quickConnect))
        assertEquals("MOSH · Port 2222", formatQuickConnectHistoryMeta(mosh, en.quickConnect))
        assertEquals("SFTP · Port 22", formatQuickConnectHistoryMeta(sftp, en.quickConnect))

        val tz = TimeZone.UTC
        val now = Instant.parse("2026-11-15T18:00:00Z")
        val todayMs = Instant.parse("2026-11-15T16:20:00Z").toEpochMilliseconds()
        val yesterdayMs = Instant.parse("2026-11-14T10:26:00Z").toEpochMilliseconds()
        val sameYearMs = Instant.parse("2026-11-12T08:15:00Z").toEpochMilliseconds()
        val prevYearMs = Instant.parse("2025-11-14T21:30:00Z").toEpochMilliseconds()

        assertEquals("Today · 16:20", formatSessionCreatedAt(todayMs, en.workspace, tz, now))
        assertEquals("Yesterday · 10:26", formatSessionCreatedAt(yesterdayMs, en.workspace, tz, now))
        assertEquals("12 Nov · 08:15", formatSessionCreatedAt(sameYearMs, en.workspace, tz, now))
        assertEquals("14 Nov 2025 · 21:30", formatSessionCreatedAt(prevYearMs, en.workspace, tz, now))

        val split = splitSessionTimestamp(sameYearMs, en.workspace, tz, now)
        assertEquals("12 Nov", split.dateLabel)
        assertEquals("08:15", split.timeLabel)

        assertEquals("今天 · 16:20", formatSessionCreatedAt(todayMs, zh.workspace, tz, now))
        assertEquals("昨天 · 10:26", formatSessionCreatedAt(yesterdayMs, zh.workspace, tz, now))
        assertEquals("11月12日 · 08:15", formatSessionCreatedAt(sameYearMs, zh.workspace, tz, now))
        assertEquals("2025年11月14日 · 21:30", formatSessionCreatedAt(prevYearMs, zh.workspace, tz, now))
        assertEquals("--:--", formatSessionCreatedAt(0L, en.workspace, tz, now))
    }
}
