package dev.termish.herdr

import dev.termish.ssh.CommandOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * HerdrApi command construction and error parsing tests.
 *
 * Error patterns are based on actual testing with herdr 0.8.0: a non-zero exit code (exit 1) upon failure, and the error JSON is located in
 * stderr (e.g., `herdr agent prompt wW:pxxx hi` → exit 1 +
 * `{"error":{"code":"agent_not_found",...},"id":"cli:agent:prompt"}`).
 * Reading only from stdout will cause failures to be mistakenly reported as successes—[HerdrApi.parseCommandError] must be checked
 * stderr + exitCode.
 */
class HerdrApiTest {
    // ---- parseCommandError Golden Cases (Real CLI Output Samples) ----

    @Test
    fun successOutputIsNull() {
        val out =
            CommandOutput(
                stdout = """{"id":"cli:agent:prompt","result":{"agent":{"agent":"pi","agent_status":"done","pane_id":"wW:p2"}}}""",
                stderr = "",
                exitCode = 0,
            )
        assertNull(HerdrApi.parseCommandError(out))
    }

    @Test
    fun agentNotFoundErrorOnStderr() {
        // Test results: herdr agent prompt "invalid pane" → exit 1 + stderr error JSON
        val out =
            CommandOutput(
                stdout = "",
                stderr = """{"error":{"code":"agent_not_found","message":"agent target wW:pxxx not found"},"id":"cli:agent:prompt"}""",
                exitCode = 1,
            )
        val err = HerdrApi.parseCommandError(out)
        assertEquals("agent_not_found", err?.code)
        assertEquals("agent target wW:pxxx not found", err?.message)
    }

    @Test
    fun paneNotFoundErrorOnStderr() {
        // Test results: herdr pane read invalid pane → exit 1 + stderr error JSON
        val out =
            CommandOutput(
                stdout = "",
                stderr = """{"error":{"code":"pane_not_found","message":"pane wW:pxxx not found"},"id":"cli:pane:read"}""",
                exitCode = 1,
            )
        val err = HerdrApi.parseCommandError(out)
        assertEquals("pane_not_found", err?.code)
        assertEquals("pane wW:pxxx not found", err?.message)
    }

    @Test
    fun errorJsonOnStdoutIsAlsoRecognized() {
        // Compatibility: Also recognizes error JSON when it appears in stdout (some CLI versions/commands).
        val out =
            CommandOutput(
                stdout = """{"error":{"code":"not_found","message":"pane not found"},"id":"req_1"}""",
                stderr = "",
                exitCode = 1,
            )
        val err = HerdrApi.parseCommandError(out)
        assertEquals("not_found", err?.code)
    }

    @Test
    fun nonZeroExitWithoutJsonYieldsExitCodeError() {
        // herdr not installed / command not found: stderr shows "command not found" from the shell; no JSON
        val out = CommandOutput(stdout = "", stderr = "herdr: command not found", exitCode = 127)
        val err = HerdrApi.parseCommandError(out)
        assertEquals("exit_127", err?.code)
    }

    @Test
    fun emptyOutputZeroExitIsSuccess() {
        assertNull(HerdrApi.parseCommandError(CommandOutput("", "", 0)))
    }

    @Test
    fun nullOutputIsFailureNotSuccess() {
        // Connection failure/timeout (runCommandDetailed returns null): Never handle this as "committed"
        val err = HerdrApi.parseCommandError(null)
        assertEquals("command_failed", err?.code)
    }

    // ---- Command Construction ----

    @Test
    fun promptCommandQuotesText() {
        assertEquals("herdr agent prompt wW:p2 'hello world'", HerdrApi.agentPromptCmd("wW:p2", "hello world"))
    }

    @Test
    fun promptCommandEscapesSingleQuote() {
        // POSIX single-quote escape: ' → '\'' to prevent text injection (exec via /bin/sh)
        assertEquals(
            "herdr agent prompt wW:p2 'it'\\''s ok'",
            HerdrApi.agentPromptCmd("wW:p2", "it's ok"),
        )
    }

    @Test
    fun shQuoteRoundTrip() {
        val samples = listOf("", "plain", "with space", "it's", "a'b'c", "中文", "a\nb")
        for (s in samples) {
            assertEquals(s, shUnquote(HerdrApi.shQuote(s)), "round-trip failed for: $s")
        }
    }

    @Test
    fun paneReadCommandShape() {
        assertEquals(
            "herdr pane read w1:p1 --source recent --lines 30",
            HerdrApi.paneReadCmd("w1:p1"),
        )
    }

    /** Minimal sh single-quote restoration (for testing; does not handle nesting) — verifies that no info is lost during escaping. */
    private fun shUnquote(q: String): String {
        require(q.startsWith("'") && q.endsWith("'"))
        val inner = q.removeSurrounding("'")
        return inner.replace("'\\''", "'")
    }
}
