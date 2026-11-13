package dev.termish.ssh

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * sshj engine integration test: Connect to the local test sshd (127.0.0.1:22222) and run the command.
 *
 * Automatically skip when sshd is not running (start it with scripts/test-sshd.sh or ./gradlew startTestSshd).
 */
class SshjIntegrationTest {
    private fun env(
        key: String,
        default: String,
    ): String = System.getenv(key) ?: default

    private fun sshdReachable(): Boolean =
        runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", env("Termish_TEST_PORT", "22222").toInt()), 500) }
        }.isSuccess

    private fun skipUnlessSshd(): Boolean {
        if (sshdReachable()) return true
        println("SKIP: 测试 sshd 未启动（scripts/test-sshd.sh 或 make test-integration）")
        return false
    }

    private fun runSession(
        connection: SshConnection,
        passphrase: String? = null,
    ) {
        val output = StringBuilder()
        val closed = AtomicBoolean(false)
        val closeReason = AtomicReference<String?>(null)
        val infoRef = AtomicReference<SessionInfo?>(null)

        val session =
            createSshSession(
                connection,
                object : SshCallbacks {
                    override suspend fun onOutput(data: ByteArray) {
                        output.append(String(data, Charsets.UTF_8))
                    }

                    override suspend fun onStderr(data: ByteArray) {
                        output.append(String(data, Charsets.UTF_8))
                    }

                    override fun onExitStatus(status: Int) {
                        println("exit status: $status")
                    }

                    override fun onClosed(reason: String?) {
                        closed.set(true)
                        closeReason.set(reason)
                    }

                    override suspend fun onPrompt(prompt: AuthPrompt): List<String>? {
                        println("PROMPT: ${prompt.name} ${prompt.instruction}")
                        if (prompt.method == SshAuthMethod.PASSPHRASE && passphrase != null) {
                            return listOf(passphrase)
                        }
                        return null
                    }

                    override fun verifyHostKey(hostKey: HostKeyInfo): Boolean {
                        println("host key: ${hostKey.algorithm} ${hostKey.fingerprintSha256}")
                        return true
                    }
                },
            )

        try {
            val info = session.connectAndStart(columns = 100, rows = 40)
            infoRef.set(info)
        } catch (e: Exception) {
            e.printStackTrace()
            throw AssertionError("连接失败: ${e.message}", e)
        }

        assertNotNull(infoRef.get())
        assertContains(infoRef.get()!!.hostKey!!.fingerprintSha256, "SHA256:")
        assertTrue(session.checkAlive(2_500), "服务器应响应 SSH 全局请求健康探测")

        session.sendData("echo Termish_SSHJ_OK\nexit 0\n".encodeToByteArray())

        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            if (output.toString().contains("Termish_SSHJ_OK")) break
            Thread.sleep(100)
        }
        Thread.sleep(500)
        session.close()

        println("--- output ---")
        println(output)
        assertContains(output.toString(), "Termish_SSHJ_OK")
    }

    @Test
    fun publicKeyAuthRunCommand() {
        if (!skipUnlessSshd()) return
        val pemFile = File(env("Termish_TEST_KEY", "/tmp/termish_test/client"))
        if (!pemFile.exists()) {
            println("SKIP: no key at ${pemFile.absolutePath}")
            return
        }
        runSession(
            SshConnection(
                host = "127.0.0.1",
                port = env("Termish_TEST_PORT", "22222").toInt(),
                username = checkNotNull(System.getProperty("user.name")),
                privateKeyPem = pemFile.readText(),
            ),
        )
    }

    @Test
    fun encryptedPrivateKeyAuthWithPassphrase() {
        if (!skipUnlessSshd()) return
        val pemFile = File(env("Termish_TEST_ENC_KEY", "/tmp/termish_test/enc_client"))
        if (!pemFile.exists()) {
            println("SKIP: no encrypted key at ${pemFile.absolutePath}")
            return
        }
        runSession(
            SshConnection(
                host = "127.0.0.1",
                port = env("Termish_TEST_PORT", "22222").toInt(),
                username = checkNotNull(System.getProperty("user.name")),
                privateKeyPem = pemFile.readText(),
            ),
            passphrase = env("Termish_TEST_ENC_PASSPHRASE", "termish-test-pass"),
        )
    }

    /** Automatic system detection (Termius-style): After the connection, `probeSystem` should return and identify the remote system. */
    @Test
    fun probeSystemDetectsHostOs() {
        if (!skipUnlessSshd()) return
        val pemFile = File(env("Termish_TEST_KEY", "/tmp/termish_test/client"))
        if (!pemFile.exists()) {
            println("SKIP: no key at ${pemFile.absolutePath}")
            return
        }
        val session =
            createSshSession(
                SshConnection(
                    host = "127.0.0.1",
                    port = env("Termish_TEST_PORT", "22222").toInt(),
                    username = checkNotNull(System.getProperty("user.name")),
                    privateKeyPem = pemFile.readText(),
                ),
                object : SshCallbacks {
                    override suspend fun onOutput(data: ByteArray) {}

                    override suspend fun onStderr(data: ByteArray) {}

                    override fun onExitStatus(status: Int) {}

                    override fun onClosed(reason: String?) {}

                    override suspend fun onPrompt(prompt: AuthPrompt): List<String>? = null

                    override fun verifyHostKey(hostKey: HostKeyInfo): Boolean = true
                },
            )
        try {
            session.connectAndStart(columns = 100, rows = 40)
            val raw = session.probeSystem()
            assertNotNull(raw, "probeSystem 应返回探测输出")
            val detected = detectSystemFromOutput(raw!!)
            assertNotNull(detected, "应从探测输出识别出系统，raw=${raw.take(200)}")
            println("probe detected: $detected")
        } finally {
            session.close()
        }
    }

    /**
     * Control-plane commands (Herdr API, etc.): Reuse an authenticated connection to execute commands,
     * Do not re-authenticate, and [do not interrupt the interactive shell]—after runCommand finishes executing
     * sendData must still return a normal response (this is the fundamental difference between it and connectAndRun).
     */
    @Test
    fun runCommandReusesConnectionWithoutKillingShell() {
        if (!skipUnlessSshd()) return
        val pemFile = File(env("Termish_TEST_KEY", "/tmp/termish_test/client"))
        if (!pemFile.exists()) {
            println("SKIP: no key at ${pemFile.absolutePath}")
            return
        }
        val output = StringBuilder()
        val session =
            createSshSession(
                SshConnection(
                    host = "127.0.0.1",
                    port = env("Termish_TEST_PORT", "22222").toInt(),
                    username = checkNotNull(System.getProperty("user.name")),
                    privateKeyPem = pemFile.readText(),
                ),
                object : SshCallbacks {
                    override suspend fun onOutput(data: ByteArray) {
                        output.append(String(data, Charsets.UTF_8))
                    }

                    override suspend fun onStderr(data: ByteArray) {
                        output.append(String(data, Charsets.UTF_8))
                    }

                    override fun onExitStatus(status: Int) {}

                    override fun onClosed(reason: String?) {}

                    override suspend fun onPrompt(prompt: AuthPrompt): List<String>? = null

                    override fun verifyHostKey(hostKey: HostKeyInfo): Boolean = true
                },
            )
        try {
            session.connectAndStart(columns = 100, rows = 40)

            // When not connected (after `close`), `runCommand` should return `null` and not throw an exception.
            val closedSession =
                createSshSession(
                    SshConnection(host = "127.0.0.1", port = 22222, username = "x", privateKeyPem = null),
                    object : SshCallbacks {
                        override suspend fun onOutput(data: ByteArray) {}

                        override suspend fun onStderr(data: ByteArray) {}

                        override fun onExitStatus(status: Int) {}

                        override fun onClosed(reason: String?) {}

                        override suspend fun onPrompt(prompt: AuthPrompt): List<String>? = null

                        override fun verifyHostKey(hostKey: HostKeyInfo): Boolean = true
                    },
                )
            assertTrue(closedSession.runCommand("echo nope") == null, "未连接的会话 runCommand 应返回 null")
            closedSession.close()

            // Reuse an authenticated connection to execute control plane commands
            val r1 = session.runCommand("echo Termish_RUNCMD_OK")
            assertNotNull(r1, "runCommand 应返回命令输出")
            assertContains(r1!!, "Termish_RUNCMD_OK")
            println("runCommand #1: ${r1.trim()}")

            // Multi-line output + explicit timeout
            val r2 = session.runCommand("echo A; echo B", timeoutMs = 3_000)
            assertNotNull(r2, "多行命令应返回输出")
            assertContains(r2!!, "A")
            assertContains(r2, "B")

            // Key point: `runCommand` does not interrupt the interactive shell—`sendData` continues to display output in a normal manner afterward.
            session.sendData("echo Termish_SHELL_ALIVE\n".encodeToByteArray())
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                if (output.toString().contains("Termish_SHELL_ALIVE")) break
                Thread.sleep(100)
            }
            assertTrue(output.toString().contains("Termish_SHELL_ALIVE"), "runCommand 后交互 shell 必须仍然存活")
        } finally {
            session.close()
        }
    }
}
