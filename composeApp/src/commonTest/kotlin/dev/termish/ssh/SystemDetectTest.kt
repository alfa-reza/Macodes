package dev.termish.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Automatic output detection and parsing (Termius-style system recognition). */
class SystemDetectTest {
    @Test
    fun parsesOsReleaseId() {
        assertEquals(
            "ubuntu",
            detectSystemFromOutput(
                """
                NAME="Ubuntu"
                ID=ubuntu
                VERSION_ID="24.04"
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun parsesQuotedOsReleaseId() {
        assertEquals(
            "debian",
            detectSystemFromOutput(
                """
                PRETTY_NAME="Debian GNU/Linux 12 (bookworm)"
                ID="debian"
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun prefersOsReleaseOverUname() {
        assertEquals(
            "centos",
            detectSystemFromOutput(
                """
                ID="centos"
                Linux
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun fallsBackToUnameDarwin() {
        assertEquals("macos", detectSystemFromOutput("Darwin"))
    }

    @Test
    fun fallsBackToUnameLinux() {
        // Minimal systems without /etc/os-release (Alpine containers, BusyBox, etc.)
        assertEquals("linux", detectSystemFromOutput("Linux"))
    }

    @Test
    fun unknownOutputReturnsNull() {
        assertNull(detectSystemFromOutput(""))
        assertNull(detectSystemFromOutput("some weird banner\n"))
    }
}
