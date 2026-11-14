package dev.macodes.herdr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HerdrProbe detection: After a match for the $HOME prefix, it must be parsed to an absolute path and returned.
 *
 * Root cause (physical-device pitfall 1): the probe command runs via the remote shell, which expands $HOME, so literal candidates match;
 * However, the single quotes in the downstream `mosh` command `-- '<BIN>'` prevent expansion, and the `mosh-server` process is directly
 * execvp (not via the shell), the literal `$HOME/...` errors with
 * `execvp: $HOME/...: No such file or directory`.
 *
 * Root Cause (Physical Device Pitfall 2): Use `--version` instead of `api snapshot` for detection—snapshot requires
 * daemon is running (tested on Linux: returns exit 1 + error JSON when `server_not_running`),
 * A host that has just been installed or has never been launched may be incorrectly identified as “not installed” (resulting in a failure even after installation).
 */
class HerdrProbeTest {
    private val versionOutput = "herdr 0.8.0"

    @Test
    fun snapPackagedHerdrIsSkipped() {
        // Ubuntu snap build (PATH hit /snap/bin/herdr): sandbox restrictions, skip this candidate,
        // Continue searching for the official script version in $HOME/.local/bin
        val commands = mutableListOf<String>()
        val result =
            HerdrProbe.probe { cmd ->
                commands += cmd
                when (cmd) {
                    "command -v herdr" -> "/snap/bin/herdr"
                    "\$HOME/.local/bin/herdr --version" -> versionOutput
                    "echo \$HOME" -> "/home/user"
                    else -> null
                }
            }!!
        assertEquals("/home/user/.local/bin/herdr", result.bin)
        assertEquals(
            listOf("command -v herdr", "\$HOME/.local/bin/herdr --version", "echo \$HOME"),
            commands,
        )
    }

    @Test
    fun homeCandidateResolvedToAbsolutePath() {
        // exec PATH missing ~/.local/bin (plain `herdr` fails) → $HOME candidate hits → resolve to absolute path
        val commands = mutableListOf<String>()
        val result =
            HerdrProbe.probe { cmd ->
                commands += cmd
                when (cmd) {
                    "command -v herdr" -> null
                    "herdr --version" -> null
                    "\$HOME/.local/bin/herdr --version" -> versionOutput
                    "echo \$HOME" -> "/root"
                    else -> null
                }
            }!!
        assertEquals("/root/.local/bin/herdr", result.bin)
        // Resolution happens after the hit, with only one extra `echo` (candidate order unchanged); PATH candidates
        // First, use `command -v` to check for a snap (returns `null` if there is no match in the PATH or if it is not a snap)
        assertEquals(
            listOf(
                "command -v herdr",
                "herdr --version",
                "\$HOME/.local/bin/herdr --version",
                "echo \$HOME",
            ),
            commands,
        )
    }

    @Test
    fun homeCandidateResolvedAfterBareCandidateMissesOtherAbsolutes() {
        // Full candidate order: herdr → $HOME → /usr/local/bin (no match) → /opt/homebrew hit,
        // Absolute-path candidates need no resolution (no `echo` should be issued)
        val commands = mutableListOf<String>()
        val result =
            HerdrProbe.probe { cmd ->
                commands += cmd
                if (cmd == "/opt/homebrew/bin/herdr --version") versionOutput else null
            }!!
        assertEquals("/opt/homebrew/bin/herdr", result.bin)
        // One extra `command -v` before PATH candidates (snap check)
        assertEquals(
            listOf("command -v herdr") + HerdrApi.BIN_CANDIDATES.map { "$it --version" },
            commands,
        )
    }

    @Test
    fun bareCandidateHitNeedsNoResolution() {
        // Common scenarios where PATH is directly applicable: Back as-is without additional checks.
        val commands = mutableListOf<String>()
        val result =
            HerdrProbe.probe { cmd ->
                commands += cmd
                when (cmd) {
                    "command -v herdr" -> "/usr/local/bin/herdr"
                    "herdr --version" -> versionOutput
                    else -> null
                }
            }!!
        assertEquals("herdr", result.bin)
        assertEquals(listOf("command -v herdr", "herdr --version"), commands)
    }

    @Test
    fun daemonNotRunningIsStillInstalled() {
        // Regression (tested on Linux): The API snapshot sends an error JSON and exits with status 1 when the daemon is not running.
        // The check has been changed to use --version—the "installed but not launched" condition must be met; it must not be mistakenly identified as not installed.
        val snapshotServerError = """{"id":"x","error":{"code":"server_not_running","message":"no herdr server is running"}}"""
        val result =
            HerdrProbe.probe { cmd ->
                when (cmd) {
                    "command -v herdr" -> "/usr/local/bin/herdr"
                    "herdr --version" -> versionOutput
                    else -> snapshotServerError
                }
            }!!
        assertEquals("herdr", result.bin)
    }

    @Test
    fun homeEchoFailureFallsBackToRawCandidate() {
        // echo $HOME failure (exception): Return the original value—do the best you can without terminating the connection
        val result =
            HerdrProbe.probe { cmd ->
                when (cmd) {
                    "\$HOME/.local/bin/herdr --version" -> versionOutput
                    else -> null
                }
            }!!
        assertEquals("\$HOME/.local/bin/herdr", result.bin)
    }

    @Test
    fun allCandidatesFailReturnsNull() {
        assertNull(HerdrProbe.probe { null })
    }

    @Test
    fun versionOutputValidation() {
        assertTrue(HerdrProbe.isVersionOutput("herdr 0.8.0"))
        assertTrue(HerdrProbe.isVersionOutput("\nherdr 0.8.0\n"))
        assertTrue(HerdrProbe.isVersionOutput("herdr 1.2.3-beta+abc"))
        // Ensure that homonyms or similar terms do not trigger false positives in the output.
        assertFalse(HerdrProbe.isVersionOutput("usage: herdr [options]"))
        assertFalse(HerdrProbe.isVersionOutput("herdr"))
        assertFalse(HerdrProbe.isVersionOutput(""))
        // The error JSON for "snapshot" is not version output
        assertFalse(HerdrProbe.isVersionOutput("""{"id":"x","error":{"code":"server_not_running"}}"""))
    }
}
