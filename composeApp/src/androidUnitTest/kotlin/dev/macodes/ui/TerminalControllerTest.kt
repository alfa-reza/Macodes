package dev.macodes.ui

import com.russhwolf.settings.MapSettings
import dev.macodes.MacodesApplication
import dev.macodes.data.ConnectionMode
import dev.macodes.data.Host
import dev.macodes.data.HostRepository
import dev.macodes.ssh.CommandOutput
import dev.macodes.ssh.CommandResult
import dev.macodes.ssh.HostKeyInfo
import dev.macodes.ssh.SessionInfo
import dev.macodes.ssh.SshCallbacks
import dev.macodes.ssh.SshExecChannel
import dev.macodes.ssh.SshSession
import dev.macodes.util.NetworkChangeKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TerminalController State Machine and Output Pipeline (fake SshSession + Controllable Main Dispatcher):
 * Connection state transitions, TOFU fingerprint saving, launch commands, reconnection after unexpected disconnections, close/destroy semantics,
 * CancellationException does not falsely report a failure; the output is consumed by the main thread and written to the buffer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MacodesApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalControllerTest {
    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = StandardTestDispatcher(scheduler)

    @BeforeTest
    fun setupMain() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDownMain() {
        Dispatchers.resetMain()
    }

    private fun repo() = HostRepository(MapSettings())

    private fun host(startup: String = "") = Host(id = "h1", name = "dev", hostname = "example.com", username = "root", startupCommand = startup)

    /** Mosh + Herdr session (the mosh bootstrap runs herdr directly). */
    private fun herdrMoshHost() = host().copy(connectionMode = ConnectionMode.MOSH)

    // herdr --version output (used to detect if "installed"; snapshot is used to check if the daemon is running)
    private val versionOutput = "herdr 0.8.0"

    @Test
    fun sharedHostKeyWaiterIsReleasedWhenOwnerRejectsOnDispose() {
        val answer = CompletableDeferred<Boolean>()
        val started = CountDownLatch(1)
        var accepted = true
        val waiter =
            Thread {
                started.countDown()
                accepted = awaitHostKeyPromptAnswer(answer)
            }

        waiter.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        answer.complete(false)
        waiter.join(1_000)

        assertFalse(waiter.isAlive)
        assertFalse(accepted)
    }

    private class FakeExec(
        val onWrite: (ByteArray) -> Unit = {},
        /** true = read Immediately returns null (mocks that the script/program has reached EOF); by default, returns an empty block to keep the channel active. */
        val eof: Boolean = false,
    ) : SshExecChannel {
        var closed = false

        override fun read(): ByteArray? = if (closed || eof) null else ByteArray(0)

        override fun write(data: ByteArray) = onWrite(data)

        override fun close() {
            closed = true
        }
    }

    private class FakeSsh(
        var connectError: Throwable? = null,
        var commandHandler: (String) -> String? = { null },
        var execFactory: ((String) -> SshExecChannel?)? = null,
        var healthy: Boolean = true,
    ) : SshSession {
        lateinit var callbacks: SshCallbacks
        val sent = mutableListOf<ByteArray>()
        val execCommands = mutableListOf<String>()
        var closed = false
        var resized = 0
        val healthChecks = AtomicInteger()

        override fun connectAndStart(
            columns: Int,
            rows: Int,
        ): SessionInfo {
            connectError?.let { throw it }
            return SessionInfo(
                serverVersion = "SSH-2.0-fake",
                hostKey = HostKeyInfo("ssh-ed25519", "SHA256:test", "md5"),
                kexAlgorithm = "curve25519-sha256",
            )
        }

        override fun resize(
            columns: Int,
            rows: Int,
            widthPx: Int,
            heightPx: Int,
        ) {
            resized++
        }

        override fun sendData(data: ByteArray) {
            sent += data
        }

        override fun connectAndRun(
            command: String,
            timeoutMs: Long,
        ): CommandResult = throw UnsupportedOperationException()

        override fun runCommandDetailed(
            command: String,
            timeoutMs: Long,
        ): CommandOutput? = commandHandler(command)?.let { CommandOutput(it, "", 0) }

        override fun startExec(
            command: String,
            columns: Int,
            rows: Int,
        ): SshExecChannel? {
            execCommands += command
            return execFactory?.invoke(command)
        }

        override fun close() {
            closed = true
        }

        override fun isActive(): Boolean = !closed

        override fun checkAlive(timeoutMillis: Long): Boolean {
            healthChecks.incrementAndGet()
            return !closed && healthy
        }
    }

    private fun controller(
        fake: FakeSsh,
        repo: HostRepository = repo(),
        autoReconnect: Boolean = true,
        startup: String = "",
    ): Triple<TerminalController, FakeSsh, HostRepository> {
        val r = repo
        val c =
            TerminalController(host(startup), "pw", null, r, autoReconnect) { _, cb ->
                fake.callbacks = cb
                fake
            }
        return Triple(c, fake, r)
    }

    private fun herdrController(
        fake: FakeSsh,
        repo: HostRepository = repo(),
    ): Pair<TerminalController, FakeSsh> {
        val c =
            TerminalController(host(), "pw", null, repo, false, launchMode = TerminalLaunchMode.HERDR) { _, cb ->
                fake.callbacks = cb
                fake
            }
        return c to fake
    }

    private fun herdrMoshController(
        fake: FakeSsh,
        repo: HostRepository = repo(),
        autoReconnect: Boolean = false,
    ): Pair<TerminalController, FakeSsh> {
        val c =
            TerminalController(
                herdrMoshHost(),
                "pw",
                null,
                repo,
                autoReconnect,
                launchMode = TerminalLaunchMode.HERDR,
            ) { _, cb ->
                fake.callbacks = cb
                fake
            }
        return c to fake
    }

    private fun moshController(
        fake: FakeSsh,
        repo: HostRepository = repo(),
    ): Pair<TerminalController, FakeSsh> {
        val c =
            TerminalController(host().copy(connectionMode = ConnectionMode.MOSH), "pw", null, repo, false) { _, cb ->
                fake.callbacks = cb
                fake
            }
        return c to fake
    }

    private fun awaitStatus(
        c: TerminalController,
        expected: ConnStatus,
    ) = runBlocking {
        withTimeout(5_000) {
            while (c.status != expected) {
                delay(10)
                // Main thread consumer: the read loop in `fake exec` rapidly produces empty chunks, filling the 256-capacity
                // output queue; unless it is flushed, the read loop gets stuck on backpressure and later states are never reached.
                scheduler.advanceUntilIdle()
            }
        }
    }

    /** Wait for the shell to send content containing the target command (with a timeout).
     *  finishConnected: set CONNECTED first, then sendData (the UI displays the connection status first, then injects the launch command,
     *  (Intentionally in this order): after `awaitStatus` returns the injection may not have taken effect yet; asserting on `f.sent` right away is racy because the injection may not have landed (flaky on slow CI), so poll and wait for it to arrive.
     *  Race window—CI is bound to be flaky on slow machines (this is exactly what happens when running the v1.1.7 tag). Wait for the injection to arrive. */
    private fun awaitSent(
        f: FakeSsh,
        expected: String,
    ) = runBlocking {
        withTimeout(5_000) {
            while (f.sent.none { it.decodeToString() == expected }) {
                delay(10)
                scheduler.advanceUntilIdle()
            }
        }
    }

    /** Wait for the shell injection command to launch herdr (latency + screen-clearing prefix; with timeout). */
    private fun awaitHerdrLaunch(
        f: FakeSsh,
        bin: String,
    ) = runBlocking {
        withTimeout(5_000) {
            while (f.sent.none {
                    it.decodeToString().contains("printf") && it.decodeToString().contains(bin)
                }
            ) {
                delay(10)
                scheduler.advanceUntilIdle()
            }
        }
    }

    private fun herdrLaunchIn(
        f: FakeSsh,
        bin: String,
    ): String? =
        f.sent
            .firstOrNull { it.decodeToString().contains("printf") && it.decodeToString().contains(bin) }
            ?.decodeToString()

    private fun TerminalController.bufferText(): String =
        buildString {
            val b = buffer
            for (r in 0 until b.rows) {
                val line = b.absLine(b.totalLines() - b.rows + r)
                for (col in 0 until b.cols) append(line.cells[col].codePoint.toChar())
                append('\n')
            }
        }

    @Test
    fun connectSuccessSetsConnectedAndRecordsFingerprint() {
        val (c, fake, r) = controller(FakeSsh())
        r.upsertHost(host())
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        assertEquals("SHA256:test", r.getHost("h1")!!.knownHostFingerprint)
        assertTrue(!fake.closed)
        c.destroy()
    }

    @Test
    fun systemProbeDoesNotEraseFingerprint() {
        // Regression: after a successful connection, the system probe upserts the whole row using the creation-time snapshot of `c.host`, which
        // touchConnected/TOFU: Recently recorded fingerprints are erased → every time there is a reconnect, new session, or SFTP
        // A confirmation dialog pops up repeatedly (the "system" variable on a new host is always empty, so the check will always trigger).
        val (c, _, r) =
            controller(
                FakeSsh(commandHandler = { cmd ->
                    if (cmd.contains("os-release")) "ID=ubuntu" else null
                }),
            )
        r.upsertHost(host())
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        // After detection is complete (system writes to disk), make an assertion that the fingerprint remains: both coexist
        runBlocking {
            withTimeout(5_000) { while (r.getHost("h1")?.system.isNullOrBlank()) delay(10) }
        }
        assertEquals("ubuntu", r.getHost("h1")!!.system)
        assertEquals("SHA256:test", r.getHost("h1")!!.knownHostFingerprint, "系统探测不应抹掉已记录指纹")
        c.destroy()
    }

    @Test
    fun connectSendsStartupCommand() {
        val (c, fake, _) = controller(FakeSsh(), startup = "tmux new -A -s main")
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)
        // When CONNECTED is set, the launch command is issued later in the same coroutine: poll and wait to avoid race conditions
        runBlocking {
            withTimeout(5_000) {
                while (fake.sent.none { it.decodeToString().trim() == "tmux new -A -s main" }) delay(10)
            }
        }

        assertTrue(fake.sent.any { it.decodeToString().trim() == "tmux new -A -s main" })
        c.destroy()
    }

    @Test
    fun connectFailureSetsError() {
        val (c, _, _) = controller(FakeSsh(connectError = IllegalStateException("boom")))
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.ERROR)

        assertTrue(c.errorMessage!!.contains("boom"))
        c.destroy()
    }

    @Test
    fun cancellationIsNotReportedAsError() {
        val (c, _, _) = controller(FakeSsh(connectError = CancellationException("cancelled")))
        c.connect(80, 24)
        runBlocking { delay(200) } // Let the connection coroutine on IO run to completion

        assertNotEquals(ConnStatus.ERROR, c.status)
        c.destroy()
    }

    @Test
    fun outputReachesEmulatorViaMainConsumer() {
        val (c, fake, _) = controller(FakeSsh())
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        runBlocking { fake.callbacks.onOutput("hello\n".encodeToByteArray()) }
        scheduler.advanceUntilIdle()

        val text = c.bufferText()
        assertTrue(text.startsWith("hello"), "buffer 应包含输出，实际: $text")
        c.destroy()
    }

    @Test
    fun specialKeyDispatchReadsRemoteCursorModeWithoutRecreatingCallback() {
        val (c, fake, _) = controller(FakeSsh())
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)
        val onKey = c::sendSpecialKey
        val keys =
            listOf(
                SpecialKey.UP to 'A',
                SpecialKey.DOWN to 'B',
                SpecialKey.RIGHT to 'C',
                SpecialKey.LEFT to 'D',
                SpecialKey.HOME to 'H',
                SpecialKey.END to 'F',
            )

        try {
            for ((mode, prefix) in listOf(false to 0x5b, true to 0x4f, false to 0x5b)) {
                // Split DECSET/DECRST across output chunks as a real SSH stream can do.
                runBlocking {
                    fake.callbacks.onOutput("\u001b[?1".encodeToByteArray())
                    fake.callbacks.onOutput((if (mode) "h" else "l").encodeToByteArray())
                }
                scheduler.advanceUntilIdle()
                assertEquals(mode, c.buffer.applicationCursorKeys)
                for ((key, final) in keys) {
                    onKey(key)
                    assertContentEquals(byteArrayOf(0x1b, prefix.toByte(), final.code.toByte()), fake.sent.last(), key.name)
                }
            }
        } finally {
            c.destroy()
        }
    }

    @Test
    fun existingSessionTabsKeepIndependentCursorModesAcrossBackgroundResume() {
        val r = repo()
        val (first, firstSsh, _) = controller(FakeSsh(), repo = r)
        val (second, secondSsh, _) = controller(FakeSsh(), repo = r)
        val manager = SessionManager(r)
        manager.sessions.addAll(listOf(first, second))
        first.connect(80, 24)
        second.connect(80, 24)
        awaitStatus(first, ConnStatus.CONNECTED)
        awaitStatus(second, ConnStatus.CONNECTED)
        val firstBuffer = first.buffer
        val firstTab = SessionTab.Terminal(first)
        val secondTab = SessionTab.Terminal(second)

        try {
            runBlocking { firstSsh.callbacks.onOutput("\u001b[?1h".encodeToByteArray()) }
            scheduler.advanceUntilIdle()
            secondTab.controller.sendSpecialKey(SpecialKey.UP)
            assertContentEquals(byteArrayOf(0x1b, 0x5b, 0x41), secondSsh.sent.last())
            firstTab.controller.sendSpecialKey(SpecialKey.UP)
            assertContentEquals(byteArrayOf(0x1b, 0x4f, 0x41), firstSsh.sent.last())

            manager.noteBackgrounded()
            // Output still updates each session's emulator while its UI is absent.
            runBlocking { secondSsh.callbacks.onOutput("\u001b[?1h".encodeToByteArray()) }
            scheduler.advanceUntilIdle()
            manager.reconnectDroppedSessions()

            val resumed = SessionTab.Terminal(manager.sessions.first { it.sessionId == firstTab.id })
            assertTrue(resumed.controller === first)
            assertTrue(resumed.controller.buffer === firstBuffer)
            resumed.controller.sendSpecialKey(SpecialKey.DOWN)
            assertContentEquals(byteArrayOf(0x1b, 0x4f, 0x42), firstSsh.sent.last())

            runBlocking { firstSsh.callbacks.onOutput("\u001b[?1l".encodeToByteArray()) }
            scheduler.advanceUntilIdle()
            resumed.controller.sendSpecialKey(SpecialKey.UP)
            assertContentEquals(byteArrayOf(0x1b, 0x5b, 0x41), firstSsh.sent.last())
            secondTab.controller.sendSpecialKey(SpecialKey.UP)
            assertContentEquals(byteArrayOf(0x1b, 0x4f, 0x41), secondSsh.sent.last())
        } finally {
            manager.sessions.forEach { it.destroy() }
        }
    }

    @Test
    fun unexpectedCloseInitiatesReconnect() {
        val (c, fake, _) = controller(FakeSsh())
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        fake.callbacks.onClosed(null)

        assertEquals(ConnStatus.CONNECTING, c.status)
        assertEquals(1, c.reconnectCount)
        c.destroy()
    }

    @Test
    fun healthyForegroundVerificationKeepsCurrentSession() {
        val (c, fake, _) = controller(FakeSsh())
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)
        runBlocking { fake.callbacks.onOutput("\u001b[?1h".encodeToByteArray()) }
        scheduler.advanceUntilIdle()

        c.verifySshConnection(SessionReconnectCause.FOREGROUND_RESUME)
        runBlocking {
            withTimeout(5_000) {
                while (fake.healthChecks.get() == 0) delay(10)
            }
        }

        assertEquals(ConnStatus.CONNECTED, c.status)
        assertTrue(c.session === fake)
        assertFalse(fake.closed)
        assertEquals(0, c.reconnectCount)
        assertTrue(c.buffer.applicationCursorKeys)
        c.sendSpecialKey(SpecialKey.UP)
        assertContentEquals(byteArrayOf(0x1b, 0x4f, 0x41), fake.sent.last())
        c.destroy()
    }

    @Test
    fun failedForegroundVerificationRebuildsExactlyOnce() {
        val first = FakeSsh(healthy = false)
        val second = FakeSsh()
        val factoryCalls = AtomicInteger()
        val c =
            TerminalController(host(), "pw", null, repo(), true) { _, callbacks ->
                when (factoryCalls.getAndIncrement()) {
                    0 -> first.also { it.callbacks = callbacks }
                    else -> second.also { it.callbacks = callbacks }
                }
            }
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        c.verifySshConnection(SessionReconnectCause.FOREGROUND_RESUME)
        runBlocking {
            withTimeout(5_000) {
                while (factoryCalls.get() < 2 || c.status != ConnStatus.CONNECTED) delay(10)
            }
        }

        assertEquals(1, first.healthChecks.get())
        assertTrue(first.closed)
        assertTrue(c.session === second)
        assertEquals(2, factoryCalls.get())
        assertEquals(0, c.reconnectCount)
        assertNull(c.reconnectCause)
        c.destroy()
    }

    @Test
    fun networkChangeVerifiesHealthySshWithoutDisconnecting() {
        val (c, fake, _) = controller(FakeSsh())
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)
        c.networkImmuneUntilMs = 0L

        c.onNetworkChanged(NetworkChangeKind.DEFAULT_NETWORK_CHANGED)
        runBlocking {
            withTimeout(5_000) {
                while (fake.healthChecks.get() == 0) delay(10)
            }
        }

        assertEquals(ConnStatus.CONNECTED, c.status)
        assertFalse(fake.closed)
        c.destroy()
    }

    @Test
    fun failedReconnectContinuesUntilConfiguredLimit() {
        val fake = FakeSsh()
        val (c, _, _) = controller(fake)
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        fake.connectError = IllegalStateException("offline")
        fake.callbacks.onClosed("lost")

        runBlocking {
            withTimeout(15_000) {
                while (c.status != ConnStatus.CLOSED) delay(10)
            }
        }

        assertEquals(3, c.reconnectCount)
        assertEquals("offline", c.errorMessage)
        c.destroy()
    }

    @Test
    fun noAutoReconnectLeavesClosed() {
        val (c, fake, _) = controller(FakeSsh(), autoReconnect = false)
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        fake.callbacks.onClosed("bye")

        assertEquals(ConnStatus.CLOSED, c.status)
        assertEquals("bye", c.errorMessage)
        c.destroy()
    }

    @Test
    fun closeStopsReconnect() {
        val (c, fake, _) = controller(FakeSsh())
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        c.close()
        assertEquals(ConnStatus.CLOSED, c.status)

        fake.callbacks.onClosed(null)
        assertEquals(ConnStatus.CLOSED, c.status)
    }

    @Test
    fun staleSessionFactoryCannotOverwriteReconnectAfterClose() {
        val factoryEntered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val factoryCalls = AtomicInteger()
        val first = FakeSsh()
        val second = FakeSsh()
        val c =
            TerminalController(host(startup = "echo ready"), "pw", null, repo(), false) { _, callbacks ->
                when (factoryCalls.getAndIncrement()) {
                    0 -> {
                        factoryEntered.countDown()
                        releaseFactory.await(5, TimeUnit.SECONDS)
                        first.callbacks = callbacks
                        first
                    }
                    else -> {
                        second.callbacks = callbacks
                        second
                    }
                }
            }

        c.connect(80, 24)
        assertTrue(factoryEntered.await(5, TimeUnit.SECONDS))
        c.close()
        c.reconnect()
        awaitStatus(c, ConnStatus.CONNECTED)

        releaseFactory.countDown()
        runBlocking {
            withTimeout(5_000) {
                while (!first.closed) delay(10)
            }
        }

        assertTrue(c.session === second)
        assertTrue(first.sent.isEmpty(), "旧连接不应在新会话建立后发送启动命令")
        c.destroy()
    }

    @Test
    fun destroyDropsFurtherOutput() {
        val (c, fake, _) = controller(FakeSsh())
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)
        val frameBefore = c.frame

        c.destroy()
        runBlocking { fake.callbacks.onOutput("late".encodeToByteArray()) }
        scheduler.advanceUntilIdle()

        assertEquals(frameBefore, c.frame)
    }

    // ---------- Herdr Session Launch Types ----------

    @Test
    fun herdrProbeFailureGuidesInstall() {
        // All candidates: runCommand returns null → No herdr on the remote: Keep the SSH connection open and enter “Pending Installation”
        // (Banner-guided installation), no errors, no fallback, no reconnection (SSH + Herdr sessions)
        val (c, f) = herdrController(FakeSsh())
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        assertTrue(c.herdrNeedsInstall, "应进入引导安装状态")
        assertTrue(!f.closed, "SSH 引导通道应保留（安装脚本在它上面执行）")
        c.destroy()
    }

    @Test
    fun installHerdrReProbesAndInjectsCommand() {
        // First scan shows no herdr → boot installation; success on rescan after the install script runs → direct SSH mode
        // Perform injection of the `herdr` command into the shell (non-exec channel: `herdr` is a shell process that returns to the shell upon exit)
        var installed = false
        val fake =
            FakeSsh(
                commandHandler = { cmd ->
                    when {
                        cmd.contains("--version") -> if (installed) versionOutput else null
                        else -> null
                    }
                },
                execFactory = { cmd ->
                    // The installation script is executed via `startExec` in a streaming manner (without passing through `commandHandler`): This mocks success.
                    if (cmd.contains("install.sh")) installed = true
                    FakeExec(eof = true)
                },
            )
        val (c, f) = herdrController(fake)
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)
        assertTrue(c.herdrNeedsInstall)

        c.installHerdr()
        // After success in installation: Exit the installation state and perform herdr injection via the shell (latency + screen-clearing prefix,
        // (To prevent the Ubuntu MOTD from being pushed to the top of the screen by delayed output)
        runBlocking { withTimeout(5_000) { while (!(!c.herdrInstalling && !c.herdrNeedsInstall)) delay(10) } }

        assertTrue(!c.herdrNeedsInstall, "安装成功应退出待安装状态")
        assertTrue(!c.herdrInstalling)
        assertTrue(f.execCommands.any { it.contains("install.sh") }, "应执行安装脚本")
        // Inject a 1.5-second latency: wait via polling (the scheduler advances virtual time), assertion of the screen-clearing prefix + binary path
        runBlocking {
            withTimeout(5_000) {
                while (f.sent.none {
                        it.decodeToString().contains(
                            "printf",
                        ) &&
                            it.decodeToString().contains("herdr")
                    }
                ) {
                    delay(10)
                    scheduler.advanceUntilIdle()
                }
            }
        }
        val injected =
            f.sent
                .first { it.decodeToString().contains("printf") && it.decodeToString().contains("herdr") }
                .decodeToString()
        assertTrue(injected.contains("\\0033[2J"), "注入应先清屏（含 scrollback），实际: $injected")
        assertTrue(injected.endsWith("'herdr'\n") || injected.endsWith("herdr\n"), "注入应以 herdr 启动命令结尾，实际: $injected")
        c.destroy()
    }

    @Test
    fun parseInstallPathExtractsFromLog() {
        // install.sh prints "installed herdr to /path/herdr" (including color-coded prefixes and multi-line logs)
        assertEquals(
            "/home/user/.local/bin/herdr",
            parseInstallPath("  > installed herdr to /home/user/.local/bin/herdr\n"),
        )
        assertEquals("/opt/herdr", parseInstallPath("downloading...\ninstalled herdr to /opt/herdr\n  > ready"))
        assertNull(parseInstallPath("download failed"))
        assertNull(parseInstallPath(""))
    }

    @Test
    fun shSingleQuoteEscapesForSudoPipe() {
        // Regular passwords: Enclose them in single quotes as-is
        assertEquals("'abc'", shSingleQuote("abc"))
        // For single quotes: split and escape `'` → `'\''`; restore the original password after shell concatenation.
        assertEquals("'a'\\''b'", shSingleQuote("a'b"))
        assertEquals("'a'\\''b'\\''c'", shSingleQuote("a'b'c"))
        // Contains shell-sensitive characters: All characters within single quotes are safe and do not require additional escaping.
        assertEquals("'a\$b c\\d\"e'", shSingleQuote("a\$b c\\d\"e"))
        // Empty password
        assertEquals("''", shSingleQuote(""))
    }

    @Test
    fun herdrLaunchDoesNotDependOnClearCommandSuccess() {
        val command = herdrShellLaunchCommand("/home/user/.local/bin/herdr")

        assertTrue(command.contains("printf"))
        assertTrue(command.contains("2>/dev/null;"), "清屏失败应静默并继续启动 Herdr: $command")
        assertFalse(command.contains("&&"), "Herdr 启动不得依赖 printf 成功: $command")
        assertTrue(command.endsWith("'/home/user/.local/bin/herdr'\n"))
    }

    @Test
    fun herdrUnexpectedCloseReconnectsAndLaunchesHerdrAgain() {
        val first = FakeSsh(commandHandler = { cmd -> if (cmd.contains("--version")) versionOutput else null })
        val second = FakeSsh(commandHandler = { cmd -> if (cmd.contains("--version")) versionOutput else null })
        val factoryCalls = AtomicInteger()
        val c =
            TerminalController(
                host = host(),
                password = "pw",
                privateKeyPem = null,
                repository = repo(),
                autoReconnect = true,
                launchMode = TerminalLaunchMode.HERDR,
            ) { _, callbacks ->
                val session = if (factoryCalls.getAndIncrement() == 0) first else second
                session.callbacks = callbacks
                session
            }

        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)
        awaitHerdrLaunch(first, "herdr")

        first.callbacks.onClosed("lost")
        assertEquals(ConnStatus.CONNECTING, c.status)
        awaitHerdrLaunch(second, "herdr")

        assertEquals(ConnStatus.CONNECTED, c.status)
        assertTrue(herdrLaunchIn(second, "herdr")?.contains("2>/dev/null;") == true)
        c.destroy()
    }

    @Test
    fun herdrMoshMissingShowsInstallGuide() {
        // Mosh + Herdr Session: Herdr is running but mosh-server is not installed → Mosh installation card (non-silent)
        // Fallback—roaming capability requires installing mosh); the status must be set to CONNECTED (regression: previously
        // (Status stuck at CONNECTING, banner permanently displays “In the process of establishing a connection…”)
        val fake =
            FakeSsh(
                commandHandler = { cmd ->
                    when {
                        cmd.contains("--version") -> versionOutput
                        else -> "mosh: command not found"
                    }
                },
                execFactory = { FakeExec() },
            )
        val (c, f) = herdrMoshController(fake)
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        assertTrue(c.moshNeedsInstall, "应进入 mosh 引导安装状态")
        assertTrue(!f.closed, "SSH 引导通道应保留（安装在其上执行）")
        c.destroy()
    }

    @Test
    fun herdrDegradedInjectsShellCommand() {
        // Degraded entries (moshDegradedToSsh) + Herdr session: go straight to the SSH shell and inject
        // herdr command (herdr is a shell process; upon exit, it returns to the shell)
        val fake =
            FakeSsh(
                commandHandler = { cmd ->
                    if (cmd.contains("--version")) versionOutput else null
                },
                execFactory = { null },
            )
        val (c, f) = herdrMoshController(fake)
        c.moshDegradedToSsh = true
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)
        awaitHerdrLaunch(f, "herdr")

        val injected = herdrLaunchIn(f, "herdr")
        assertTrue(injected != null, "应经 shell 注入 herdr 命令")
        assertTrue(injected!!.contains("\\0033[2J"), "注入应先清屏")
        c.destroy()
    }

    @Test
    fun herdrProbeUsesFullPathCandidates() {
        // Bare herdr not in PATH, full path matched: the probe records the candidate order, and the matched path carries through the injection command
        val probed = mutableListOf<String>()
        val fake =
            FakeSsh(
                commandHandler = { cmd ->
                    if (cmd.contains("--version")) {
                        probed += cmd
                        if (cmd.startsWith("/usr/local/bin/herdr")) versionOutput else null
                    } else {
                        null
                    }
                },
                execFactory = { FakeExec() },
            )
        val (c, f) = herdrController(fake)
        c.moshDegradedToSsh = true // Fallback entry: Go straight to the SSH shell (skip the mosh boot)
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        // Probe in candidate order (HerdrProbe fixes the candidates as the bare command): herdr →
        // \$HOME/.local/bin/herdr → /usr/local/bin/herdr (match); injection as the matching path
        assertEquals(3, probed.size)
        assertTrue(probed[0].startsWith("herdr "))
        assertTrue(probed[2].startsWith("/usr/local/bin/herdr "))
        // The matched path runs through the injection (not a bare herdr); the injection carries a 1.5s delay, so poll and wait.
        awaitHerdrLaunch(f, "/usr/local/bin/herdr")
        assertTrue(
            herdrLaunchIn(f, "/usr/local/bin/herdr") != null,
            "应注入命中的全路径，实际: ${f.sent.map { it.decodeToString() }}",
        )
        c.destroy()
    }

    @Test
    fun herdrProbeResolvesHomeCandidateToAbsolutePath() {
        // Physical device pitfall (remote Linux server): sshd non-interactive exec PATH does not include ~/.local/bin →
        // Raw herdr probe failed; $HOME candidate matched (expanded via remote shell); but downstream
        // mosh boot `-- '<BIN>'` (single quotes are not expanded); mosh-server processes use `execvp` directly
        // Not via the shell, the literal $HOME reports
        // `execvp: $HOME/.local/bin/herdr: No such file or directory`.
        // Assertion: once matched, it is resolved to an absolute path (echo $HOME); the injection command gets the absolute path
        val commands = mutableListOf<String>()
        val fake =
            FakeSsh(
                commandHandler = { cmd ->
                    commands += cmd
                    when (cmd) {
                        "herdr --version" -> null
                        "\$HOME/.local/bin/herdr --version" -> versionOutput
                        "echo \$HOME" -> "/root"
                        else -> null
                    }
                },
                execFactory = { FakeExec() },
            )
        val (c, f) = herdrController(fake)
        c.moshDegradedToSsh = true // Fallback entry: Go straight to the SSH shell (skip the mosh boot)
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        assertTrue(commands.contains("echo \$HOME"), "命中 \$HOME 候选后应解析绝对路径")
        awaitHerdrLaunch(f, "/root/.local/bin/herdr")
        val injected = herdrLaunchIn(f, "/root/.local/bin/herdr")
        assertTrue(
            injected != null,
            "应注入解析后的绝对路径，实际: ${f.sent.map { it.decodeToString() }}",
        )
        c.destroy()
    }

    @Test
    fun herdrMoshBootstrapUsesResolvedAbsolutePath() {
        // The `mosh` command must be followed by a parsed absolute path: `mosh-server` directly processes parameters following `--`
        // execvp (not via the shell); a literal `$HOME` will inevitably fail (see above). Main is changed to Unconfined:
        // After initial parsing, doConnectMosh prepares the theme using withContext(Main) and tests the default
        // The StandardTestDispatcher does not run on its own; it will leave the connection coroutine hanging there.
        val bootstraps = mutableListOf<String>()
        val fake =
            FakeSsh(
                commandHandler = { cmd ->
                    when {
                        cmd.contains("mosh-server new") -> {
                            bootstraps += cmd
                            // The key must follow the 22-character specification in Base64: the client construction will validate it,
                            // An invalid key throws an exception and branches to the ERROR branch, so this assertion is never reached.
                            "MOSH CONNECT 60000 AAAAAAAAAAAAAAAAAAAAAA"
                        }
                        // exec PATH without ~/.local/bin: plain herdr fails, $HOME candidate matches
                        cmd == "herdr --version" -> null
                        cmd == "\$HOME/.local/bin/herdr --version" -> versionOutput
                        cmd == "echo \$HOME" -> "/root"
                        else -> null
                    }
                },
                execFactory = { FakeExec() },
            )
        Dispatchers.setMain(Dispatchers.Unconfined)
        val (c, f) = herdrMoshController(fake)
        c.connect(80, 24)
        // An assertion is triggered as soon as the command is issued (without waiting for confirmation of the first UDP packet: that is the 5-second timeout path, which is unrelated to this assertion).
        runBlocking { withTimeout(5_000) { while (bootstraps.isEmpty()) delay(10) } }
        assertTrue(
            bootstraps[0].contains("-- ${shSingleQuote("/root/.local/bin/herdr")}"),
            "mosh 引导必须是解析后的绝对路径（非字面 \$HOME）: ${bootstraps[0]}",
        )
        assertTrue(
            bootstraps[0].contains("mosh-server new -s"),
            "mosh 引导必须绑定入站 SSH 使用的本地地址: ${bootstraps[0]}",
        )
        c.destroy()
    }

    @Test
    fun moshUdpTimeoutCleansOnlyItsDetachedServer() {
        val commands = mutableListOf<String>()
        val fake =
            FakeSsh(
                commandHandler = { cmd ->
                    commands += cmd
                    when {
                        cmd.contains("mosh-server new") ->
                            "MOSH CONNECT 60998 AAAAAAAAAAAAAAAAAAAAAA\n" +
                                "[mosh-server detached, pid = 4242]"
                        cmd.contains("MOSH_PID=4242") -> "MOSH_CLEANUP_OK"
                        else -> null
                    }
                },
                execFactory = { FakeExec() },
            )
        Dispatchers.setMain(Dispatchers.Unconfined)
        val r = repo()
        val host =
            host().copy(
                hostname = "203.0.113.1",
                connectionMode = ConnectionMode.MOSH,
            )
        val c =
            TerminalController(host, "pw", null, r, false) { _, cb ->
                fake.callbacks = cb
                fake
            }

        c.connect(80, 24)
        runBlocking {
            withTimeout(10_000) {
                while (!c.moshDegradedToSsh) delay(10)
            }
        }

        val cleanup = commands.single { it.contains("MOSH_PID=4242") }
        assertTrue(cleanup.contains("ps -p \"\$MOSH_PID\" -o comm="), cleanup)
        assertTrue(cleanup.contains("kill -USR1 \"\$MOSH_PID\""), cleanup)
        assertTrue(!cleanup.contains("pkill"), cleanup)
        c.destroy()
    }

    @Test
    fun herdrInstallMoshReGuidesWithHerdrArg() {
        // Mosh + Herdr session + mosh not installed → guided installation; after installation completes, the re-bootstrap must include
        // -- 'herdr' (mosh runs herdr directly in a session). This mocks a scenario where the PATH has not been refreshed after installation, yet
        // not found → Return to the boot card, but the boot command must include the `herdr` parameter
        var installed = false
        val bootstraps = mutableListOf<String>()
        val fake =
            FakeSsh(
                commandHandler = { cmd ->
                    when {
                        cmd.contains("mosh-server new") -> {
                            bootstraps += cmd
                            "mosh: command not found"
                        }
                        cmd.contains("command -v mosh-server") -> if (installed) "/usr/bin/mosh-server" else null
                        cmd.contains("os-release") -> "ID=ubuntu"
                        cmd.contains("id -u") -> "0"
                        cmd.contains("--version") -> versionOutput
                        else -> null
                    }
                },
                execFactory = { cmd ->
                    if (cmd.contains("apt-get install -y mosh")) installed = true
                    FakeExec(eof = true)
                },
            )
        val (c, f) = herdrMoshController(fake)
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)
        assertTrue(c.moshNeedsInstall)

        c.installMosh()
        // Final state: installation complete + re-bootstrap still reports not found → return to the guide card (already carries the herdr parameter)
        runBlocking {
            withTimeout(5_000) { while (!(!c.moshInstalling && c.moshNeedsInstall && bootstraps.size >= 2)) delay(10) }
        }

        assertEquals(2, bootstraps.size, "安装后应重新引导，实际: $bootstraps")
        assertTrue(bootstraps[1].contains("mosh-server new"), "引导命令: ${bootstraps[1]}")
        assertTrue(bootstraps[1].contains("-- ${shSingleQuote("herdr")}"), "Herdr 会话引导应带 -- herdr: ${bootstraps[1]}")
        c.destroy()
    }

    @Test
    fun herdrDegradeButtonInjectsHerdrAndSticksForReconnect() {
        // Mosh + Herdr session + mosh not installed → Card “Fallback SSH” = injection of the herdr command;
        // After the user makes an explicit selection for the "sticky" flag, the broken connection is reconnected and proceeds directly to the SSH shell (still undergoing herdr injection),
        // No longer goes through the mosh bootstrap
        var bootstrapCount = 0
        val fake =
            FakeSsh(
                commandHandler = { cmd ->
                    when {
                        cmd.contains("mosh-server new") -> {
                            bootstrapCount++
                            "mosh: command not found"
                        }
                        cmd.contains("--version") -> versionOutput
                        else -> null
                    }
                },
                execFactory = { FakeExec() },
            )
        val (c, f) = herdrMoshController(fake, autoReconnect = true)
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)
        assertTrue(c.moshNeedsInstall)

        // User clicks “Fallback SSH”: performs herdr injection (detected raw command)
        c.degradeMoshToSsh()
        awaitHerdrLaunch(f, "herdr")
        assertTrue(herdrLaunchIn(f, "herdr") != null, "降级应注入 herdr 命令，实际: ${f.sent.map { it.decodeToString() }}")
        assertTrue(c.moshDegradedToSsh, "降级应置 sticky 标记")

        // Broken link → Automatic reconnection → Direct SSH shell injection into herdr (no mosh boot)
        fake.closed = true
        fake.callbacks.onClosed("lost")
        awaitStatus(c, ConnStatus.CONNECTING)
        awaitStatus(c, ConnStatus.CONNECTED)
        // Second injection with a 1.5-second latency: Poll until the count reaches 2, then make an assertion
        runBlocking {
            withTimeout(5_000) {
                while (f.sent.count {
                        it.decodeToString().contains(
                            "printf",
                        ) &&
                            it.decodeToString().contains("herdr")
                    } <
                    2
                ) {
                    delay(10)
                    scheduler.advanceUntilIdle()
                }
            }
        }

        assertEquals(1, bootstrapCount, "重连不应再次引导 mosh-server（首次引导除外）")
        assertEquals(
            2,
            f.sent.count { it.decodeToString().contains("printf") && it.decodeToString().contains("herdr") },
            "重连后应再次注入 herdr（共 2 次）",
        )
        c.destroy()
    }

    @Test
    fun moshDegradedReconnectUsesPlainShell() {
        // The MOSH mode also follows the "sticky" fallback: reconnections no longer launch mosh-server, but instead open an SSH shell directly.
        var bootstrapCount = 0
        val fake =
            FakeSsh(
                commandHandler = { cmd ->
                    if (cmd.contains("mosh-server new")) {
                        bootstrapCount++
                        "MOSH CONNECT 60000 key"
                    } else {
                        null
                    }
                },
            )
        val (c, _) = moshController(fake)
        c.moshDegradedToSsh = true
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        assertEquals(0, bootstrapCount, "降级过的会话不应再引导 mosh-server")
        assertTrue(c.moshSession == null, "不应创建 mosh 会话")
        c.destroy()
    }

    // ---------- Mosh Install Guide ----------

    @Test
    fun moshMissingGuidesInstall() {
        // Mosh mode: mosh-server missing (not found) → Maintain SSH connection and enter “Pending Installation”
        // (Guide card: Install or downgrade SSH), do not fallback directly
        val fake =
            FakeSsh(
                commandHandler = { cmd ->
                    when {
                        cmd.contains("mosh-server new") -> "mosh: command not found"
                        cmd.contains("os-release") -> "ID=ubuntu"
                        else -> null
                    }
                },
            )
        val (c, f) = moshController(fake)
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)

        assertTrue(c.moshNeedsInstall, "应进入引导安装状态")
        assertTrue(!f.closed, "SSH 引导通道应保留（安装/降级都靠它）")
        c.destroy()
    }

    @Test
    fun installMoshRunsPackageManagerAndReGuides() {
        // Password-less sudo: Install the command (sudo -n apt-get…) → Verify with command -v →
        // Re-bootstrap still reports missing (PATH not refreshed after mock installation) → return to the guide card (can retry/fallback)
        var installed = false
        val fake =
            FakeSsh(
                commandHandler = { cmd ->
                    when {
                        // The `bootstrap` command string also includes `os-release`: the `mosh-server` branch must be listed first
                        cmd.contains("mosh-server new") -> "mosh: command not found"
                        cmd.contains("command -v mosh-server") ->
                            if (installed) "/usr/bin/mosh-server" else null
                        cmd.contains("os-release") -> "ID=ubuntu"
                        cmd.contains("id -u") -> "1000"
                        cmd.contains("command -v sudo") -> "/usr/bin/sudo"
                        cmd.contains("sudo -n true") -> "SUDO_OK"
                        else -> null
                    }
                },
                execFactory = { cmd ->
                    if (cmd.contains("apt-get install -y mosh")) installed = true
                    FakeExec(eof = true)
                },
            )
        val (c, f) = moshController(fake)
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)
        assertTrue(c.moshNeedsInstall)

        c.installMosh()
        // Wait for the combined end state rather than just !moshInstalling: after installation completes it will re-bootstrap (only revert in case of failure,
        // moshNeedsInstall), waiting on the single flag will assert before the re-bootstrap completes (root cause of flakiness)
        runBlocking { withTimeout(5_000) { while (!(!c.moshInstalling && c.moshNeedsInstall)) delay(10) } }

        assertTrue(installed, "应执行包管理器安装命令")
        assertTrue(f.execCommands.any { it.contains("sudo -n apt-get") }, "非 root 免密 sudo 前缀")
        assertTrue(c.moshNeedsInstall, "重引导仍缺 → 回到引导卡片（可重试/降级）")
        c.destroy()
    }

    @Test
    fun installMoshWithSudoPasswordFeedsStdin() {
        // sudo password required: on the first tap with no password → the card prompts for input; after entering the password, the install command runs
        // `sudo -S` and the password is written via `exec stdin` (not included in the command string)
        var passwordSeen = ""
        val fake =
            FakeSsh(
                commandHandler = { cmd ->
                    when {
                        cmd.contains("mosh-server new") -> "mosh: command not found"
                        cmd.contains("command -v mosh-server") -> "/usr/bin/mosh-server"
                        cmd.contains("os-release") -> "ID=debian"
                        cmd.contains("id -u") -> "1000"
                        cmd.contains("command -v sudo") -> "/usr/bin/sudo"
                        cmd.contains("sudo -n true") -> "sudo: a password is required"
                        else -> null
                    }
                },
                execFactory = { cmd ->
                    if (cmd.contains("sudo -S")) {
                        FakeExec(onWrite = { passwordSeen = it.decodeToString() }, eof = true)
                    } else {
                        FakeExec(eof = true)
                    }
                },
            )
        val (c, f) = moshController(fake)
        c.connect(80, 24)
        awaitStatus(c, ConnStatus.CONNECTED)
        assertTrue(c.moshNeedsInstall)

        // First tap (no password): detection finds a sudo password is required → the card prompts for input (final state =
        // !moshInstalling && moshNeedsSudoPassword)
        c.installMosh()
        runBlocking { withTimeout(5_000) { while (!(!c.moshInstalling && c.moshNeedsSudoPassword)) delay(10) } }
        assertTrue(c.moshNeedsSudoPassword, "应提示输入 sudo 密码")
        assertTrue(!f.execCommands.any { it.contains("sudo -S") }, "无密码时不应执行安装")

        // Retry after inputting the password: sudo -S accepts the password via exec stdin (terminal state = completion of the entire installation process)
        c.installMosh("p@ss'w0rd")
        runBlocking { withTimeout(5_000) { while (!(!c.moshInstalling && !c.moshNeedsSudoPassword)) delay(10) } }

        assertTrue(f.execCommands.any { it.contains("sudo -S -p '' sh -c") }, "应走 sudo -S 非交互")
        assertEquals("p@ss'w0rd\n", passwordSeen, "密码经 stdin 送达，不进命令字符串")
        assertTrue(f.execCommands.none { it.contains("p@ss") }, "命令串不含密码明文")
        assertTrue(!c.moshNeedsSudoPassword, "安装成功应退出密码输入态")
        c.destroy()
    }
}
