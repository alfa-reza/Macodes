package dev.macodes.herdr

import dev.macodes.ssh.CommandOutput
import kotlinx.serialization.json.Json

/**
 * herdr CLI command construction and output parsing (V1 control plane).
 *
 * All commands are executed via `SshSession.runCommand` over an authenticated connection (reuse the connection without interrupting the shell).
 * Actual behavior (based on herdr 0.8.0, tested):
 * - `herdr api snapshot`: JSON, containing `result.snapshot` (see HerdrModels)
 * - `herdr pane read`: Terminal content is plain text, without JSON wrapping (shown directly in the AgentDialog context)
 * - `herdr agent prompt`: Successfully outputs the CLI-wrapped JSON; in case of failure, **the exit code is non-zero (exit 1)**
 *   Also, error JSON on stderr** (e.g., `{"error":{"code":"agent_not_found",...},"id":"cli:agent:prompt"}`) —
 *   Must be evaluated by [parseCommandError] using [CommandOutput] (stderr + exitCode),
 *   Reading only from stdout will treat a failure as a success without any notification.
 */
object HerdrApi {
    const val SNAPSHOT_CMD = "herdr api snapshot"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * herdr binary path candidates (sshd's non-interactive exec PATH typically excludes ~/.local/bin,
     * Common installation locations such as /usr/local/bin and /opt/homebrew—try them one by one in case of failure.
     * $HOME is expanded by the remote /bin/sh -c (the standard execution method for SSH exec channels).
     *
     * After a hit, [HerdrProbe] resolves the $HOME-prefix candidates into absolute paths before returning them,
     * Downstream components must reuse this absolute path (when `mosh` is launched with `-- <BIN>`, `mosh-server` directly
     * execvp does not expand shell variables, and startExec does not expand single quotes ($HOME) — bare `herdr` or
     * The literal `$HOME/...` passes detection in non-default PATH scenarios but will inevitably fail during launch.
     */
    val BIN_CANDIDATES =
        listOf(
            "herdr",
            "\$HOME/.local/bin/herdr",
            "/usr/local/bin/herdr",
            "/opt/homebrew/bin/herdr",
        )

    /** Command candidates for `snapshot` (derived from [BIN_CANDIDATES]). */
    val SNAPSHOT_CMD_CANDIDATES = BIN_CANDIDATES.map { "$it api snapshot" }

    /** Read the most recent output from the pane (in the context of the "approve" dialog). The output is plain text. */
    fun paneReadCmd(
        paneId: String,
        lines: Int = 30,
    ): String = "herdr pane read $paneId --source recent --lines $lines"

    /** Commit a reply to the agent (approve via phone). The text is escaped using shell single quotes (exec runs via /bin/sh). */
    fun agentPromptCmd(
        paneId: String,
        text: String,
    ): String = "herdr agent prompt $paneId ${shQuote(text)}"

    /** Shell single-quote escape: `'` → `'\\''` (POSIX standard technique to prevent text injection). */
    fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Parse herdr CLI errors (`{"error":{...}}`) from command result.
     *
     * Order of evaluation:
     * 1. stderr/stdout contains error JSON (in case of herdr failure, the error JSON is written to stderr, e.g., agent_not_found)
     * 2. Non-zero exit code but no JSON → Generate an error (code = "exit_<N>", retaining exit code info)
     * 3. Connection-level failure (output is null) → synthetic error (code = "command_failed"),
     *    Avoid causing the caller to mistakenly interpret “command failed” as “committed.”
     * Returns null on success (exit code 0 and no error JSON).
     */
    fun parseCommandError(out: CommandOutput?): HerdrApiError? {
        if (out == null) return HerdrApiError("command_failed", "ssh command failed or timed out")
        val raw =
            listOf(out.stderr, out.stdout)
                .firstOrNull { it.contains("{\"error\"") }
                ?: ""
        if (raw.isNotEmpty()) {
            // Only extract the "error" object fragment (the CLI wrapper also includes an "id" field at the end; parse it with fault-tolerant truncation).
            val candidate = raw.substring(raw.indexOf("{\"error\""))
            return runCatching {
                json
                    .decodeFromString<HerdrCliResponse>(candidate)
                    .error
            }.getOrNull() ?: HerdrApiError("unknown", "unparseable herdr error")
        }
        val exit = out.exitCode
        if (exit != null && exit != 0) return HerdrApiError("exit_$exit", "command exited with code $exit")
        return null
    }
}
