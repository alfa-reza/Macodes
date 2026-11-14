package dev.macodes.ssh

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.Timeout

/** sshj SFTP integration test: Connect to the local test sshd to verify listing directories, creating directories, and uploading files. */
class SftpIntegrationTest {
    /** Safety net: Any test case that hangs for 90 seconds must result in failure rather than cause the CI to hang indefinitely (previously hung due to sshj waiting indefinitely for a response). */
    @get:Rule
    @JvmField
    val timeout: Timeout = Timeout.seconds(90)

    private fun env(
        key: String,
        default: String,
    ): String = System.getenv(key) ?: default

    private fun sshdReachable(): Boolean =
        runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", env("Macodes_TEST_PORT", "22222").toInt()), 500) }
        }.isSuccess

    private fun newSession(): SftpSession {
        val pemFile = File(env("Macodes_TEST_KEY", "/tmp/macodes_test/client"))
        check(pemFile.exists()) { "no key at ${pemFile.absolutePath}" }
        return createSftpSession(
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

    @Test
    fun sftpListMkdirUpload() {
        if (!sshdReachable()) {
            println("SKIP: 测试 sshd 未启动")
            return
        }
        val session = newSession()
        try {
            val base = "/tmp/macodes_test"
            val root = session.list(base)
            assertTrue(root.isNotEmpty(), "应能列出 $base")
            println("root entries: ${root.size}, 示例: ${root.first().name} ${root.first().permissions}")

            val dir = "$base/sftp_test_${System.currentTimeMillis()}"
            session.mkdir(dir)
            val payload = "hello sftp".encodeToByteArray()
            var served = false
            session.upload("$dir/hello.txt", payload.size.toLong()) {
                // Must return null (EOF) after one return: nextChunk never returns null = infinite upload (which previously caused 660 million packets to hang)
                if (served) {
                    null
                } else {
                    served = true
                    payload
                }
            }

            val listed = session.list(dir)
            val file = listed.firstOrNull { it.name == "hello.txt" }
            assertTrue(file != null, "上传后应能列出 hello.txt")
            assertTrue(!file!!.isDirectory, "hello.txt 应为文件")
            assertEquals("hello sftp".length.toLong(), file.size, "文件大小")
            println("upload OK: ${file.name} ${file.permissions} size=${file.size}")
        } finally {
            session.close()
        }
    }

    @Test
    fun sftpBinaryRoundTrip() {
        if (!sshdReachable()) {
            println("SKIP: 测试 sshd 未启动")
            return
        }
        val session = newSession()
        try {
            val dir = "/tmp/macodes_test/sftp_bin_${System.currentTimeMillis()}"
            session.mkdir(dir)
            // Cover all byte values from 0x00 to 0xFF (non-UTF-8 text) to verify that upload/download bytes are lossless;
            // Chunk-based callback to mock a streaming source (8KB blocks × multiple blocks), verifying multi-block paths and ensuring no large chunks of memory are retained
            val data = ByteArray(256 * 257) { (it % 256).toByte() }
            val chunks =
                data
                    .toList()
                    .chunked(8 * 1024)
                    .map { it.toByteArray() }
                    .iterator()
            session.upload("$dir/bin.dat", data.size.toLong()) { if (chunks.hasNext()) chunks.next() else null }

            val out = ByteArrayOutputStream()
            session.download("$dir/bin.dat") { chunk -> out.write(chunk) }
            assertTrue(out.toByteArray().contentEquals(data), "二进制上传/下载往返应一致")
            println("binary round-trip OK: ${data.size} bytes")
        } finally {
            session.close()
        }
    }

    @Test
    fun randomReadUsesIndependentConnectionAndLongOffsets() {
        if (!sshdReachable()) return
        val path = "/tmp/macodes_test/视频 范围_${System.nanoTime()}.mp4"
        val start = 4L * 1024 * 1024 * 1024 + 17
        val tail = ByteArray(1024) { (it % 251).toByte() }
        val session = newSession()
        var reader: SftpReadHandle? = null
        try {
            // Sparse files actually only take up a few KB; verify that offsets greater than 4 GB do not suffer any truncation due to the `int` type.
            RandomAccessFile(path, "rw").use {
                it.write(byteArrayOf(1, 2, 3))
                it.seek(start)
                it.write(tail)
            }
            reader = session.openRead(path)
            assertEquals(start + tail.size, reader.size)
            assertContentEquals(byteArrayOf(1, 2, 3), reader.read(0, 3))
            assertContentEquals(tail, reader.read(start, 2048))
            assertTrue(session.list("/tmp/macodes_test").any { it.name == File(path).name })
            assertContentEquals(byteArrayOf(2, 3), reader.read(1, 2))
            session.close()
            assertContentEquals(tail.copyOfRange(1000, 1024), reader.read(start + 1000, 100))
            assertTrue(reader.read(reader.size, 64).isEmpty())
            assertTrue(reader.read(reader.size + 10, 64).isEmpty())
            assertFailsWith<IllegalArgumentException> { reader.read(-1, 1) }
            reader.close()
            assertFailsWith<IllegalStateException> { reader.read(0, 1) }
        } finally {
            reader?.close()
            session.close()
            File(path).delete()
        }
    }
}
