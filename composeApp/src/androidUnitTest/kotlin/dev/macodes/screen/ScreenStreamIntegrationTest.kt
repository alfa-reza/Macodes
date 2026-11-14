package dev.macodes.screen

import dev.macodes.ssh.AuthPrompt
import dev.macodes.ssh.HostKeyInfo
import dev.macodes.ssh.SshCallbacks
import dev.macodes.ssh.SshConnection
import dev.macodes.ssh.createSshSession
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue

/**
 * Screen streaming integration test: Local sshd (127.0.0.1:22222) + local machine ffmpeg screen capture,
 * Use the same `exec raw` channel as ScreenSession. Regression-test two real bugs:
 * 1. stderr blocking causes the main loop to hang (black screen) — after separating stderr into a separate coroutine, stdout must be continuously consumed
 * 2. When ffmpeg on the remote server exits, it reports “Video stream disconnects” — Channel should not return EOF within 5 seconds
 *
 * If any dependency (sshd/private key/ffmpeg) is missing from the environment, the test will automatically SKIP (in the same mode as transport layer integration tests).
 */
class ScreenStreamIntegrationTest {
    private fun env(
        key: String,
        default: String,
    ): String = System.getenv(key) ?: default

    private fun sshdReachable(): Boolean =
        runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", env("Macodes_TEST_PORT", "22222").toInt()), 500) }
        }.isSuccess

    private fun ffmpegAvailable(): Boolean =
        runCatching {
            val p = ProcessBuilder("sh", "-c", "command -v ffmpeg").redirectErrorStream(true).start()
            val out =
                p.inputStream
                    .readBytes()
                    .decodeToString()
                    .trim()
            p.waitFor()
            out.isNotEmpty()
        }.getOrDefault(false)

    private fun skipUnlessReady(): Boolean {
        if (!skipUnlessSshReady()) return false
        if (!ffmpegAvailable()) {
            println("SKIP: 本机无 ffmpeg（远端抓屏脚本需要）")
            return false
        }
        return true
    }

    private fun skipUnlessSshReady(): Boolean {
        if (!sshdReachable()) {
            println("SKIP: 测试 sshd 未启动（scripts/test-sshd.sh 或 make test-integration）")
            return false
        }
        val pemFile = File(env("Macodes_TEST_KEY", "/tmp/macodes_test/client"))
        if (!pemFile.exists()) {
            println("SKIP: no key at ${pemFile.absolutePath}")
            return false
        }
        return true
    }

    @Test
    fun `raw exec channel streams h264 without stderr blocking`() {
        if (!skipUnlessReady()) return
        val pemFile = File(env("Macodes_TEST_KEY", "/tmp/macodes_test/client"))
        val session =
            createSshSession(
                SshConnection(
                    host = "127.0.0.1",
                    port = env("Macodes_TEST_PORT", "22222").toInt(),
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
            assertNotNull(session.connectAuthOnly(), "connectAuthOnly 应成功")
            val ch = session.startExecRaw(ScreenSession.LAVFI_SCRIPT)
            assertNotNull(ch, "startExecRaw 应返回通道")

            // stderr independent coroutine (same mode as ScreenSession): consumes but does not perform blocking on stdout
            val stderrBuf = StringBuilder()
            val stderrJob =
                CoroutineScope(Dispatchers.IO).launch {
                    while (true) {
                        val err = ch.readErr() ?: break
                        stderrBuf.append(err.decodeToString())
                    }
                }

            // Main loop: Blocking read from stdout + NAL count; runs for 5 seconds.
            // Use the lavfi testsrc source (does not depend on screen recording permissions—avfoundation under an sshd session)
            // (Screen capture may fail/hang due to TCC permissions; this is an environment issue, not a link issue)
            val parser = H264Stream.AnnexBParser()
            var nalCount = 0
            var sawSps = false
            var sawIdr = false
            var sawAud = false
            var eofEarly = false
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val data = ch.read()
                if (data == null) {
                    eofEarly = true
                    break
                }
                var nal = parser.push(data)
                while (nal != null) {
                    nalCount++
                    if (nal.type == 7) sawSps = true
                    if (nal.type == 5) sawIdr = true
                    // AUD (type 9): Frame alignment marker—relay splits frames based on AUD; if a segment is lost, only one frame is lost
                    if (nal.type == 9) sawAud = true
                    nal = parser.drain()
                }
            }
            stderrJob.cancel()

            val errText = stderrBuf.toString()
            println(
                "--- screen stream: nals=$nalCount sps=$sawSps idr=$sawIdr aud=$sawAud eofEarly=$eofEarly stderr=${errText.take(
                    300,
                )}",
            )
            assertFalse(eofEarly, "5 秒内通道不应 EOF（远端 ffmpeg 正常推流），stderr: $errText")
            assertTrue(nalCount > 10, "5 秒内应收到大量 NAL，实际 $nalCount（疑似 stdout 被 stderr 阻塞读卡死）")
            assertTrue(sawSps && sawIdr, "应收到 SPS + IDR 关键帧（sps=$sawSps idr=$sawIdr）")
            assertTrue(sawAud, "流应含 AUD（帧对齐语义；LAVFI_SCRIPT 已加 aud=1）")
            assertFalse(errText.contains("FFMPEG_MISSING"), "远端不应报 ffmpeg 缺失")
        } finally {
            session.close()
        }
    }

    @Test
    fun `ssh direct tcpip channel is bidirectional`() {
        if (!skipUnlessSshReady()) return
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val serverJob =
            thread(name = "direct-tcpip-test-server") {
                server.accept().use { client ->
                    val request = client.getInputStream().readNBytes(4)
                    if (request.contentEquals("ping".encodeToByteArray())) {
                        client.getOutputStream().write("pong".encodeToByteArray())
                        client.getOutputStream().flush()
                    }
                }
            }
        val pemFile = File(env("Macodes_TEST_KEY", "/tmp/macodes_test/client"))
        val session = createTestSession(pemFile)
        try {
            assertNotNull(session.connectAuthOnly(), "connectAuthOnly 应成功")
            val channel = session.openDirectTcpip("127.0.0.1", server.localPort)
            assertNotNull(channel, "direct-tcpip 通道应建立")
            channel.write("ping".encodeToByteArray())
            assertContentEquals("pong".encodeToByteArray(), channel.read(), "通道应可双向收发")
            channel.close()
        } finally {
            session.close()
            server.close()
            serverJob.join(2_000)
        }
    }

    /**
     * One-click installation on phone: Actually run INSTALL_SCRIPT (same as the guide card) →
     * Service is set up as a listener → READ_STREAM_SCRIPT continuously reads frames from the stream.
     * Works only on macOS remote systems (due to the LaunchAgent GUI domain mechanism); the service automatically uninstalls upon completion.
     */
    @Test
    fun `install script sets up gui session service and serves h264 stream`() {
        assumeTrue(
            "SKIP: 安装/卸载真实桌面服务需显式设置 MACODES_TEST_INSTALL_SCREEN=1",
            System.getenv("MACODES_TEST_INSTALL_SCREEN") == "1",
        )
        if (!skipUnlessReady()) return
        if (!System.getProperty("os.name").orEmpty().contains("Mac")) {
            println("SKIP: 推流服务安装脚本仅支持 macOS 远端")
            return
        }
        val pemFile = File(env("Macodes_TEST_KEY", "/tmp/macodes_test/client"))
        val session =
            createSshSession(
                SshConnection(
                    host = "127.0.0.1",
                    port = env("Macodes_TEST_PORT", "22222").toInt(),
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
            assertNotNull(session.connectAuthOnly())
            // 1. Run the install script: It should output MACODES_SCREEN_OK
            val installOut = StringBuilder()
            val installCh = session.startExecRaw(ScreenSession.INSTALL_SCRIPT)
            assertNotNull(installCh, "安装通道应建立")
            while (true) {
                val d = installCh.read() ?: break
                installOut.append(d.decodeToString())
            }
            installCh.close()
            println("--- install output: ${installOut.toString().lines().lastOrNull()}")
            assertTrue(
                installOut.toString().contains("MACODES_SCREEN_OK"),
                "安装脚本应成功：${installOut.toString().take(300)}",
            )
            // relay warm-up + initial connection takes a few seconds; wait 2 seconds before reading the stream
            Thread.sleep(2_000)

            // 2. The control plane only returns the remote loopback TCP port; the video plane uses SSH direct-tcpip
            // Obtain H.264 Annex-B with THS1 status and length-based frames, consistent with the actual phone network link.
            val preflight = session.runCommandDetailed(ScreenSession.READ_STREAM_SCRIPT, 15_000)
            assertNotNull(preflight, "控制面探测应成功")
            assumeFalse(
                "SKIP: 本机桌面锁屏或休眠，无法验证真实抓屏",
                preflight.stderr.lineSequence().any { it == "SCREEN_ASLEEP" || it == "SCREEN_LOCKED" },
            )
            assertFalse(preflight.stderr.contains("SCREEN_"), "控制面不应报错：${preflight.stderr}")
            val tcpPort =
                preflight.stdout
                    .lineSequence()
                    .firstOrNull { it.startsWith("SCREEN_TCP_PORT:") }
                    ?.substringAfter(":")
                    ?.trim()
                    ?.toIntOrNull()
            // stdout also carries a bearer token that should remain confidential even during its short lifecycle, along with failure info
            // Do not include the entire probe output in local or CI test logs.
            assertNotNull(tcpPort, "应返回 TCP 视频端口")
            val authToken = ScreenSession.parseAuthToken(preflight.stdout)
            assertNotNull(authToken, "应通过已认证 SSH 返回画面 token")
            val streamCh = session.openDirectTcpip("127.0.0.1", tcpPort)
            assertNotNull(streamCh, "SSH direct-tcpip 视频通道应建立")
            streamCh.write(frameScreenTcpAuth(authToken))
            var bytes = 0
            var frames = 0
            val parser =
                ScreenTcpFrameParser({}, { frame ->
                    bytes += frame.size
                    frames++
                })
            val deadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < deadline) {
                val data = streamCh.read() ?: break
                parser.push(data)
                if (frames >= 10 && bytes > 50_000) break
            }
            streamCh.close()
            println("--- direct-tcpip stream: frames=$frames bytes=$bytes")
            assertTrue(frames >= 10, "推流服务应持续产生完整帧，实际 $frames 帧")
            assertTrue(bytes > 50_000, "推流服务应持续出流，实际 $bytes 字节")
        } finally {
            // Wrap-up: Uninstall the service (leave no residual files after testing)
            runCatching {
                session.runCommand(
                    "launchctl bootout gui/\$(id -u) \$HOME/Library/LaunchAgents/dev.macodes.screen.plist",
                    10_000,
                )
            }
            session.close()
        }
    }

    private fun createTestSession(pemFile: File) =
        createSshSession(
            SshConnection(
                host = "127.0.0.1",
                port = env("Macodes_TEST_PORT", "22222").toInt(),
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
}

class SystemSshdStreamTest {
    @Test
    fun `sshj reads stream via system sshd port 22`() {
        if (!System.getProperty("os.name").orEmpty().contains("Mac")) {
            println("SKIP: 仅 macOS")
            return
        }
        val pemFile = File("/tmp/macodes_test/client_600")
        if (!pemFile.exists()) {
            println("SKIP: no key at ${pemFile.absolutePath}")
            return
        }
        val session =
            createSshSession(
                SshConnection(
                    host = "127.0.0.1",
                    port = 22,
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
            val conn = session.connectAuthOnly()
            assertNotNull(conn, "系统 sshd 连接失败")
            // LAVFI self-contained streaming (does not depend on a relay service: the service will be uninstalled after installation and testing are complete)
            val ch = session.startExecRaw(ScreenSession.LAVFI_SCRIPT)
            assertNotNull(ch, "exec 通道建立失败")
            val parser = H264Stream.AnnexBParser()
            var nalCount = 0
            var eof = false
            val deadline = System.currentTimeMillis() + 4_000
            while (System.currentTimeMillis() < deadline) {
                val data = ch.read()
                if (data == null) {
                    eof = true
                    break
                }
                var nal = parser.push(data)
                while (nal != null) {
                    nalCount++
                    nal = parser.drain()
                }
            }
            println("--- system sshd stream: nals=$nalCount eof=$eof")
            assertTrue(nalCount > 10, "系统 sshd 下应读到流，实际 nals=$nalCount eof=$eof")
        } finally {
            session.close()
        }
    }
}
