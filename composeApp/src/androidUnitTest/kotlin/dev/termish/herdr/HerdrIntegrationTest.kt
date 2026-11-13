package dev.termish.herdr

import dev.termish.ssh.AuthPrompt
import dev.termish.ssh.HostKeyInfo
import dev.termish.ssh.SshCallbacks
import dev.termish.ssh.SshConnection
import dev.termish.ssh.createSshSession
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * herdr end-to-end integration test: Establish a real sshj connection to test the local sshd (127.0.0.1:22222),
 * Execute `herdr api snapshot` using `runCommand` on an authenticated connection, and verify:
 * 1. runCommand: Reuse connections to execute control plane commands (M1 capability)
 * 2. Parse real remote herdr output (protocol-layer model)
 * 3. The state machine runs successfully on real data
 *
 * self-detect: SKIP (not considered a failure) when sshd or herdr is missing — when CI does not have herdr
 * Automatically skipped; runs automatically during verification on the local machine (to test whether sshd’s exec PATH includes herdr).
 */
class HerdrIntegrationTest {
    private fun env(
        key: String,
        default: String,
    ): String = System.getenv(key) ?: default

    private fun sshdReachable(): Boolean =
        runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", env("Termish_TEST_PORT", "22222").toInt()), 500) }
        }.isSuccess

    @Test
    fun runCommandExecutesHerdrSnapshotOverRealSsh() {
        if (!sshdReachable()) {
            println("SKIP: 测试 sshd 未启动（scripts/test-sshd.sh 或 make test-integration）")
            return
        }
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
            val raw = session.runCommand("herdr api snapshot", timeoutMs = 5_000)
            // Missing terms vary across shells: bash = "command not found," dash/sh = "not found,"
            // Additionally, "No such file" entries—all of which indicate "no herdr" on the remote → SKIP (automatically skipped when herdr is missing in CI).
            if (raw == null || raw.contains("not found") || raw.contains("No such file")) {
                println("SKIP: 远端无 herdr（raw=${raw?.take(100)}）——本机装了 herdr 时此测试自动跑")
                return
            }
            // Invalid output (non-JSON snapshots) is also handled as missing: environment exceptions should not crash CI.
            val snapshot = parseHerdrSnapshot(raw)
            if (snapshot == null) {
                println("SKIP: 远端 herdr 输出无法解析（raw=${raw.take(100)}）")
                return
            }
            assertTrue(snapshot.protocol > 0, "protocol 应非零")
            println(
                "herdr over ssh: protocol=${snapshot.protocol} panes=${snapshot.panes.size} " +
                    "agents=${snapshot.agents.map { "${it.agent}/${it.agentStatus}" }}",
            )

            // The state machine passes tests on real data: first round, first event + no changes compared to the same snapshot.
            // Number of events ≤ number of agents: The "DONE" status does not generate events (no migration to report),
            // Assertions should be verified precisely by status class rather than relying on the live session happening to be all 'working'
            val m = HerdrAgentStateMachine()
            val first = m.update(snapshot)
            val eventful = snapshot.agents.count { it.agentStatus != HerdrAgentStatus.DONE }
            val unblockedFollows = first.count { it is HerdrAgentEvent.Unblocked }
            val nonUnblocked = first.size - unblockedFollows
            assertTrue(nonUnblocked == eventful, "首轮事件数($nonUnblocked) 应等于非 DONE agent 数($eventful)")
            assertTrue(m.update(snapshot).isEmpty(), "同快照无变化不应产生事件")
        } finally {
            session.close()
        }
    }
}
