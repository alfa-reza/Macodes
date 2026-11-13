package dev.termish.ui

import dev.termish.ssh.SftpEntry
import dev.termish.ssh.SftpSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** SFTP pure logic: path concatenation, search matching, directory name sanitization, recursive downloads. */
class SftpLogicTest {
    // ---------- joinPath ----------

    @Test
    fun joinPathAtRoot() {
        assertEquals("/etc", joinPath("/", "etc"))
        assertEquals("/home", joinPath("/", "home"))
    }

    @Test
    fun joinPathNested() {
        assertEquals("/home/user/file.txt", joinPath("/home/user", "file.txt"))
        assertEquals("/var/log", joinPath("/var", "log"))
    }

    // ---------- matchesQuery / globMatch ----------

    @Test
    fun emptyQueryMatchesEverything() {
        assertTrue(matchesQuery("anything.log", ""))
        assertTrue(matchesQuery("anything.log", "   "))
    }

    @Test
    fun containsMatchIsCaseInsensitive() {
        assertTrue(matchesQuery("nginx.conf", "NGINX"))
        assertTrue(matchesQuery("Nginx.Conf", "nginx"))
        assertFalse(matchesQuery("apache.conf", "nginx"))
    }

    @Test
    fun multipleKeywordsAreAnded() {
        assertTrue(matchesQuery("nginx config", "conf nginx"))
        assertFalse(matchesQuery("nginx config", "conf mysql"))
    }

    @Test
    fun globStarMatchesExtension() {
        assertTrue(matchesQuery("error.log", "*.log"))
        assertFalse(matchesQuery("error.txt", "*.log"))
    }

    @Test
    fun globQuestionMarkMatchesSingleChar() {
        assertTrue(matchesQuery("readme1", "readme?"))
        assertFalse(matchesQuery("readme12", "readme?"))
        assertTrue(matchesQuery("readme2.txt", "readme?.*"))
    }

    @Test
    fun globEscapesRegexMetaCharacters() {
        assertTrue(globMatch("a.b", "a.b"))
        assertFalse(globMatch("axb", "a.b"))
        assertTrue(globMatch("ERROR.LOG", "*.log"))
    }

    // ---------- sanitizeDirName ----------

    @Test
    fun sanitizeDirNameCleansPathAndFallsBack() {
        assertEquals("my_dir", sanitizeDirName("my dir"))
        assertEquals("a_b", sanitizeDirName("a/b"))
        assertEquals("download", sanitizeDirName(""))
        // Spaces will be replaced with underscores as "invalid characters" rather than being treated as empty (consistent with the implementation).
        assertEquals("___", sanitizeDirName("   "))
    }

    // ---------- downloadDir Recursion ----------

    private fun entry(
        name: String,
        dir: Boolean,
    ) = SftpEntry(name, dir, if (dir) "drwxr-xr-x" else "-rw-r--r--", 0, 0, name.startsWith("."))

    private class FakeFileSink : FileSink {
        val chunks = mutableListOf<ByteArray>()
        var closed = false

        override fun write(bytes: ByteArray) {
            chunks += bytes
        }

        override fun close() {
            closed = true
        }

        fun content(): String = chunks.joinToString("") { it.decodeToString() }
    }

    private class FakeDirSink : DirectorySink {
        val files = mutableMapOf<String, FakeFileSink>()

        override fun openFile(relativePath: String): FileSink = FakeFileSink().also { files[relativePath] = it }

        override fun close() {}
    }

    private class FakeSftp(
        val tree: Map<String, List<SftpEntry>>,
        var failPath: String? = null,
    ) : SftpSession {
        val downloaded = mutableListOf<String>()

        override fun list(path: String): List<SftpEntry> = tree[path] ?: emptyList()

        override fun mkdir(path: String) {}

        override fun delete(path: String) {}

        override fun rename(
            oldPath: String,
            newPath: String,
        ) {}

        override fun home(): String = "/"

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
            downloaded += remotePath
            if (remotePath == failPath) throw IllegalStateException("download failed: $remotePath")
            onChunk(remotePath.encodeToByteArray())
            onProgress(1, 1)
        }

        override fun close() {}
    }

    @Test
    fun downloadDirRecursesWithRelativePaths() {
        val sftp =
            FakeSftp(
                mapOf(
                    "/root" to listOf(entry("a.txt", false), entry("sub", true), entry(".hidden", false)),
                    "/root/sub" to listOf(entry("b.log", false)),
                ),
            )
        val sink = FakeDirSink()

        downloadDir(sftp, "/root", sink)

        // Directory downloads do not follow the UI’s `showHidden` filter: hidden files are downloaded as well; only `.` and `..` are skipped.
        assertEquals(setOf("a.txt", "sub/b.log", ".hidden"), sink.files.keys)
        assertEquals("/root/a.txt", sink.files["a.txt"]!!.content())
        assertEquals("/root/sub/b.log", sink.files["sub/b.log"]!!.content())
        assertEquals("/root/.hidden", sink.files[".hidden"]!!.content())
        assertTrue(sink.files.values.all { it.closed })
    }

    @Test
    fun downloadDirSkipsDotAndDotDot() {
        val sftp =
            FakeSftp(
                mapOf(
                    "/root" to listOf(entry(".", true), entry("..", true), entry("f", false)),
                ),
            )
        val sink = FakeDirSink()

        downloadDir(sftp, "/root", sink)

        assertEquals(setOf("f"), sink.files.keys)
    }

    @Test
    fun downloadDirPropagatesErrorAndClosesFile() {
        val sftp =
            FakeSftp(
                mapOf(
                    "/root" to listOf(entry("a.txt", false), entry("sub", true)),
                    "/root/sub" to listOf(entry("b.log", false)),
                ),
                failPath = "/root/a.txt",
            )
        val sink = FakeDirSink()

        assertFailsWith<IllegalStateException> { downloadDir(sftp, "/root", sink) }

        // On failure the files have been closed; exceptions propagate up the call stack, and subsequent directories will no longer be downloaded.
        assertTrue(sink.files["a.txt"]!!.closed)
        assertFalse("sub/b.log" in sink.files)
    }

    // ---------- searchRecursive Recursion ----------

    @Test
    fun searchRecursiveFindsMatchesAcrossDirectories() =
        runBlocking {
            val sftp =
                FakeSftp(
                    mapOf(
                        "/root" to listOf(entry("app-release.apk", false), entry("sub", true)),
                        "/root/sub" to listOf(entry("app-debug.apk", false), entry("readme.txt", false)),
                    ),
                )
            val hits = searchRecursive(sftp, "/root", "apk")
            assertEquals(setOf("app-release.apk", "sub/app-debug.apk"), hits.map { it.relPath }.toSet())
            assertEquals(2, hits.size)
        }

    @Test
    fun searchRecursiveTraversesHiddenDirs() =
        runBlocking {
            val sftp =
                FakeSftp(
                    mapOf(
                        "/root" to listOf(entry(".hidden", true), entry("note.txt", false)),
                        "/root/.hidden" to listOf(entry("secret.apk", false)),
                    ),
                )
            // Recursive search ignores the UI’s `showHidden`: hidden directories are also traversed (consistent with the semantics of recursive directory downloads).
            val hits = searchRecursive(sftp, "/root", "apk")
            assertEquals(listOf("secret.apk"), hits.map { it.name })
        }

    @Test
    fun searchRecursiveRespectsMaxResults() =
        runBlocking {
            val sftp =
                FakeSftp(
                    mapOf(
                        "/root" to listOf(entry("a.apk", false), entry("b.apk", false), entry("c.apk", false)),
                    ),
                )
            val hits = searchRecursive(sftp, "/root", "apk", maxResults = 2)
            assertEquals(2, hits.size)
        }

    @Test
    fun searchRecursiveToleratesUnlistableDir() =
        runBlocking {
            // FakeSftp returns an empty list for unknown paths (mocking a directory with insufficient permissions): does not throw exceptions or interrupt traversal
            val sftp =
                FakeSftp(
                    mapOf(
                        "/root" to listOf(entry("ok.apk", false), entry("broken", true)),
                    ),
                )
            val hits = searchRecursive(sftp, "/root", "apk")
            assertEquals(listOf("ok.apk"), hits.map { it.name })
        }

    // ---------- formatSize ----------

    @Test
    fun formatSizeBytesAndZero() {
        assertEquals("0 B", formatSize(0))
        assertEquals("0 B", formatSize(-1))
        assertEquals("512 B", formatSize(512))
        assertEquals("1023 B", formatSize(1023))
    }

    @Test
    fun formatSizeScalesUnits() {
        assertEquals("1.0 KB", formatSize(1024))
        assertEquals("1.5 KB", formatSize(1536))
        assertEquals("2.3 MB", formatSize(2_412_544))
        assertEquals("1.0 GB", formatSize(1024L * 1024 * 1024))
    }

    // ---------- readSftpPreview ----------

    /** Preview-only `fake`: `download` outputs a byte stream in blocks of [chunkSize] (mocking actual looped reading); throws a transfer exception when `fail=true`. */
    private class PreviewSftp(
        private val bytes: ByteArray,
        private val fail: Boolean = false,
        private val chunkSize: Int = 64 * 1024,
    ) : SftpSession {
        override fun list(path: String): List<SftpEntry> = emptyList()

        override fun mkdir(path: String) {}

        override fun delete(path: String) {}

        override fun rename(
            oldPath: String,
            newPath: String,
        ) {}

        override fun home(): String = "/"

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
            if (fail) throw IllegalStateException("download failed: $remotePath")
            // Mock the semantics of loop-based reading and `finally-close` as in the actual implementation:
            // When `onChunk` throws an exception, the exception propagates up the call stack, and subsequent blocks are no longer dispatched.
            var off = 0
            while (off < bytes.size) {
                val n = minOf(chunkSize, bytes.size - off)
                onChunk(bytes.copyOfRange(off, off + n))
                off += n
            }
            onProgress(bytes.size.toLong(), bytes.size.toLong())
        }

        override fun close() {}
    }

    @Test
    fun previewDecodesUtf8Text() =
        runBlocking {
            val bytes = "你好，Termish\nline2".encodeToByteArray()
            val r = readSftpPreview(PreviewSftp(bytes), "/root/README.md")
            assertEquals("你好，Termish\nline2", r.text)
            assertFalse(r.truncated)
        }

    @Test
    fun previewTruncatesAtLimit() =
        runBlocking {
            // Subsequent blocks exceeding maxBytes trigger an interrupt: only the first maxBytes bytes are retained, and the rest is marked as subject to truncation.
            val bytes = "abcdefghij".encodeToByteArray()
            val r = readSftpPreview(PreviewSftp(bytes, chunkSize = 3), "/root/a.txt", maxBytes = 4)
            assertEquals("abcd", r.text)
            assertTrue(r.truncated)
        }

    @Test
    fun previewRejectsBinaryWithNulByte() =
        runBlocking {
            val bytes = byteArrayOf(1, 2, 0, 3, 4)
            assertFailsWith<SftpPreviewBinaryException> {
                readSftpPreview(PreviewSftp(bytes), "/root/a.bin")
            }
            Unit
        }

    @Test
    fun previewPropagatesDownloadError() =
        runBlocking {
            assertFailsWith<IllegalStateException> {
                readSftpPreview(PreviewSftp(ByteArray(0), fail = true), "/root/a.txt")
            }
            Unit
        }

    // ---------- File Type Recognition / Image Preview Routing ----------

    @Test
    fun extensionOfLowercases() {
        assertEquals("png", extensionOf("PHOTO.PNG"))
        assertEquals("md", extensionOf("README.md"))
        assertEquals("", extensionOf("Makefile"))
        assertEquals("gz", extensionOf("a.tar.gz"))
    }

    @Test
    fun isImageNameMatchesPreviewableFormats() {
        assertTrue(isImageName("a.png"))
        assertTrue(isImageName("b.JPG"))
        assertTrue(isImageName("c.webp"))
        assertTrue(isImageName("图标 空格.SVG"))
        assertFalse(isImageName("a.md"))
        assertFalse(isImageName("Makefile"))
        assertFalse(isImageName("a.tar.gz"))
    }

    @Test
    fun fileKindClassifiesByExtension() {
        assertEquals(SftpFileKind.IMAGE, fileKindOf("screenshot.png"))
        assertEquals(SftpFileKind.IMAGE, fileKindOf("diagram.svg"))
        assertEquals(SftpFileKind.VIDEO, fileKindOf("clip.mp4"))
        assertEquals(SftpFileKind.AUDIO, fileKindOf("song.mp3"))
        assertEquals(SftpFileKind.ARCHIVE, fileKindOf("backup.tar.gz"))
        assertEquals(SftpFileKind.CODE, fileKindOf("Main.kt"))
        assertEquals(SftpFileKind.MD, fileKindOf("notes.md"))
        assertEquals(SftpFileKind.KEY, fileKindOf("id_ed25519.pem"))
        assertEquals(SftpFileKind.CONFIG, fileKindOf("docker-compose.yml"))
        assertEquals(SftpFileKind.TORRENT, fileKindOf("ubuntu.torrent"))
        assertEquals(SftpFileKind.APK, fileKindOf("app.apk"))
        assertEquals(SftpFileKind.PDF, fileKindOf("manual.pdf"))
        assertEquals(SftpFileKind.OTHER, fileKindOf("blob.bin"))
        assertEquals(SftpFileKind.OTHER, fileKindOf("Makefile"))
    }

    @Test
    fun readPreviewBytesExactLimitNoThrow() =
        runBlocking {
            val bytes = "abcde".encodeToByteArray()
            val r = readSftpPreviewBytes(PreviewSftp(bytes, chunkSize = 3), "/root/a.png", maxBytes = 5)
            assertEquals("abcde", r.decodeToString())
        }

    @Test
    fun readPreviewBytesThrowsWhenExceedingLimit() =
        runBlocking {
            val bytes = "abcdefghij".encodeToByteArray()
            assertFailsWith<SftpPreviewTooLargeException> {
                readSftpPreviewBytes(PreviewSftp(bytes, chunkSize = 3), "/root/a.png", maxBytes = 5)
            }
            Unit
        }
}
