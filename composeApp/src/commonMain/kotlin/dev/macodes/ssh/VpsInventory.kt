package dev.macodes.ssh

import dev.macodes.data.Host
import dev.macodes.data.HostInventory
import dev.macodes.data.HostRepository
import dev.macodes.data.resolveSshAuth
import dev.macodes.util.ioDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/**
 * Read-only, rootless command that detects CPU logical cores, total RAM, root disk capacity,
 * and CPU architecture across Linux, BusyBox/Alpine, macOS, and BSD hosts.
 */
const val INVENTORY_PROBE_COMMAND =
    "printf 'ARCH=%s\\n' \"${'$'}(uname -m 2>/dev/null)\"; " +
        "if command -v nproc >/dev/null 2>&1; then printf 'CPU=%s\\n' \"${'$'}(nproc 2>/dev/null)\"; " +
        "elif [ -r /proc/cpuinfo ]; then printf 'CPU=%s\\n' \"${'$'}(grep -c '^processor' /proc/cpuinfo 2>/dev/null)\"; " +
        "elif command -v sysctl >/dev/null 2>&1; then printf 'CPU=%s\\n' \"${'$'}(sysctl -n hw.ncpu 2>/dev/null)\"; fi; " +
        "if [ -r /proc/meminfo ]; then awk '/^MemTotal:/{printf \"MEM_KB=%s\\n\",${'$'}2;exit}' /proc/meminfo 2>/dev/null; " +
        "elif command -v sysctl >/dev/null 2>&1; then printf 'MEM_BYTES=%s\\n' \"${'$'}(sysctl -n hw.memsize 2>/dev/null || sysctl -n hw.physmem 2>/dev/null)\"; fi; " +
        "(df -Pk / 2>/dev/null || df -k / 2>/dev/null) | awk 'NF>=2 && ${'$'}2 ~ /^[0-9]+${'$'}/ {printf \"DISK_KB=%s\\n\",${'$'}2; exit}'"

fun parseHostInventoryOutput(
    output: String,
    detectedAt: Long,
): HostInventory? {
    var arch = ""
    var cpu: Int? = null
    var memMb: Long? = null
    var diskMb: Long? = null

    for (rawLine in output.lineSequence()) {
        val line = rawLine.trim()
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        val key = line.substring(0, eq).trim()
        val value = line.substring(eq + 1).trim()
        if (value.isEmpty()) continue
        when (key) {
            "ARCH" -> arch = value.take(64)
            "CPU" -> cpu = value.toIntOrNull()?.takeIf { it > 0 }
            "MEM_KB" -> memMb = value.toLongOrNull()?.takeIf { it > 0 }?.let { (it / 1024L).coerceAtLeast(1L) }
            "MEM_BYTES" ->
                if (memMb == null) {
                    memMb = value.toLongOrNull()?.takeIf { it > 0 }?.let { (it / (1024L * 1024L)).coerceAtLeast(1L) }
                }
            "DISK_KB" -> diskMb = value.toLongOrNull()?.takeIf { it > 0 }?.let { (it / 1024L).coerceAtLeast(1L) }
        }
    }

    if (cpu == null && memMb == null && diskMb == null && arch.isEmpty()) return null
    return HostInventory(
        cpuCores = cpu,
        memoryTotalMb = memMb,
        diskTotalMb = diskMb,
        architecture = arch,
        detectedAt = detectedAt,
    )
}

fun formatRamMb(mb: Long): String {
    if (mb < 1024L) return "$mb MB"
    val tenths = (mb * 10L + 512L) / 1024L
    return if (tenths % 10L == 0L) "${tenths / 10L} GB" else "${tenths / 10L}.${tenths % 10L} GB"
}

fun formatDiskMb(mb: Long): String {
    if (mb < 1024L) return "$mb MB"
    val gb = (mb + 512L) / 1024L
    if (gb < 1024L) return "$gb GB"
    val tbTenths = (gb * 10L + 512L) / 1024L
    return if (tbTenths % 10L == 0L) "${tbTenths / 10L} TB" else "${tbTenths / 10L}.${tbTenths % 10L} TB"
}

fun formatHostCardSpecs(inventory: HostInventory?): String? {
    if (inventory == null) return null
    val parts = ArrayList<String>(2)
    inventory.cpuCores?.let { parts.add("$it vCPU") }
    inventory.memoryTotalMb?.let { parts.add("${formatRamMb(it)} RAM") }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

suspend fun refreshHostInventory(
    host: Host,
    repository: HostRepository,
    activeSession: SshSession? = null,
): Result<HostInventory> =
    withContext(ioDispatcher()) {
        runCatching {
            val raw =
                if (activeSession != null && activeSession.isActive()) {
                    activeSession.runCommand(INVENTORY_PROBE_COMMAND, timeoutMs = 5_000L)
                        ?: error("Unable to run inventory probe on active SSH session")
                } else {
                    val settings = repository.loadSettings()
                    val auth = resolveSshAuth(host, repository)
                    val conn =
                        SshConnection(
                            host = host.hostname,
                            port = host.port,
                            username = host.username,
                            password = auth.password,
                            privateKeyPem = auth.privateKeyPem,
                            privateKeyPassphrase = auth.privateKeyPassphrase,
                            certificateText = auth.certificateText,
                            connectTimeoutMillis = 8_000L,
                            keepAliveSeconds = 0,
                            terminalType = settings.terminalType,
                        )
                    val callbacks =
                        object : SshCallbacks {
                            override suspend fun onOutput(data: ByteArray) {}

                            override suspend fun onStderr(data: ByteArray) {}

                            override fun onExitStatus(status: Int) {}

                            override fun onClosed(reason: String?) {}

                            override suspend fun onPrompt(prompt: AuthPrompt): List<String>? = null

                            override fun verifyHostKey(hostKey: HostKeyInfo): Boolean {
                                val saved = host.knownHostFingerprint
                                if (saved != null) return saved == hostKey.fingerprintSha256
                                return !settings.verifyHostKeyOnFirstUse
                            }
                        }
                    createSshSession(conn, callbacks).connectAndRun(INVENTORY_PROBE_COMMAND, timeoutMs = 8_000L).output
                }
            val now = Clock.System.now().toEpochMilliseconds()
            val parsed =
                parseHostInventoryOutput(raw, now)
                    ?: error("No system specifications detected")
            repository.patchHost(host.id) { it.copy(inventory = parsed) }
            parsed
        }
    }
