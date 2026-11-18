package dev.macodes.ssh

import dev.macodes.data.HostInventory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VpsInventoryTest {
    @Test
    fun parsesLinuxProcAndDfOutput() {
        val raw =
            """
            ARCH=x86_64
            CPU=4
            MEM_KB=8192000
            DISK_KB=52428800
            """.trimIndent()
        val parsed = parseHostInventoryOutput(raw, detectedAt = 1_700_000_000_000L)!!
        assertEquals("x86_64", parsed.architecture)
        assertEquals(4, parsed.cpuCores)
        assertEquals(8000L, parsed.memoryTotalMb)
        assertEquals(51200L, parsed.diskTotalMb)
        assertEquals(1_700_000_000_000L, parsed.detectedAt)
    }

    @Test
    fun parsesMacOsSysctlBytes() {
        val raw =
            """
            ARCH=arm64
            CPU=8
            MEM_BYTES=17179869184
            DISK_KB=262144000
            """.trimIndent()
        val parsed = parseHostInventoryOutput(raw, detectedAt = 123L)!!
        assertEquals("arm64", parsed.architecture)
        assertEquals(8, parsed.cpuCores)
        assertEquals(16384L, parsed.memoryTotalMb)
        assertEquals(256000L, parsed.diskTotalMb)
        assertEquals("8 vCPU · 16 GB RAM", formatHostCardSpecs(parsed))
    }

    @Test
    fun handlesGarbageOrEmptyOutputSafely() {
        assertNull(parseHostInventoryOutput("", detectedAt = 1L))
        assertNull(parseHostInventoryOutput("permission denied\nCPU=invalid\nMEM_KB=-5", detectedAt = 1L))
    }

    @Test
    fun formatsPartialSpecsCleanly() {
        assertEquals("2 vCPU", formatHostCardSpecs(HostInventory(cpuCores = 2)))
        assertEquals("512 MB RAM", formatHostCardSpecs(HostInventory(memoryTotalMb = 512L)))
        assertNull(formatHostCardSpecs(HostInventory(architecture = "aarch64")))
        assertNull(formatHostCardSpecs(null))
    }
}
