package dev.macodes.herdr

import dev.macodes.util.TermLog

/**
 * herdr probe (connection-level control plane operation): Test each candidate path one by one on an authenticated connection
 * `<bin> --version`—the first candidate that returns a valid version output is the hit.
 *
 * Detect using `--version` instead of `api snapshot`: snapshot requires the herdr daemon to be running
 * running (exit 1 + error JSON when `server_not_running`), and the probe happens before herdr is injected
 * before—a host that was just installed or never started will inevitably not have the daemon, and the probe will misjudge “installed”
 * as “not installed” (the install-guide card spins indefinitely, and it still reports failure after install). `--version` does not depend on
 * daemon—the meaning is “the binary exists and is executable.”
 * (During runtime, whether the "daemon is running" is determined by HerdrMonitor using snapshot polling + failure backoff)
 * (Each of the two paths serves its own purpose.)
 *
 * The detection result represents connection-level knowledge—the hit path must extend downstream (mosh boots with `-- <BIN>`),
 * exec (fallback to startExec), otherwise non-default PATH (~/.local/bin, /opt/homebrew)
 * Scenario detection passes, but launch always fails.
 */
object HerdrProbe {
    /** Detection result: path of the matching binary. */
    data class Result(
        val bin: String,
    )

    /**
     * Check if herdr is installed.
     *
     * @param runCommand Executes a one-time command on an authenticated connection (an adaptation of SshSession.runCommand,
     *   (A fixed timeout is applied upon input; a return value of null is treated as a failure for that candidate.)
     * @return Match result; if all candidates fail (not installed or invalid output), return null
     */
    fun probe(runCommand: (String) -> String?): Result? {
        for (bin in HerdrApi.BIN_CANDIDATES) {
            // Ubuntu Snap version of herdr (/snap/bin): sandboxed (restricted to home directory permissions), and
            // Macodes Workbench is incompatible (herdr notice "you are using a snap
            // "version") — skip detection; use the official script version (~/.local/bin)
            if (isSnapHerdr(bin, runCommand)) {
                TermLog.w("herdr") { "skip snap-packaged herdr (bin=$bin)" }
                continue
            }
            val raw = runCommand("$bin --version") ?: continue
            if (!isVersionOutput(raw)) continue
            TermLog.d("herdr") { "probe hit bin=$bin" }
            return Result(resolveHome(bin, runCommand))
        }
        return null
    }

    /**
     * Determine whether PATH candidates resolve to a snap version (prefixed with `/snap/`).
     * Commands installed via snap live in /snap/bin (Ubuntu’s default PATH includes it); the snap sandbox
     * prevents reading or writing outside the user’s home directory and restricts the daemon—detecting a snap Herdr means it will
     * fail on the next launch (user feedback: shows a “snap version” notice when connecting to Ubuntu).
     */
    private fun isSnapHerdr(
        bin: String,
        runCommand: (String) -> String?,
    ): Boolean {
        if (bin != "herdr") return false
        val resolved = runCommand("command -v herdr") ?: return false
        return resolved.startsWith("/snap/")
    }

    /**
     * Version output validation: `herdr 0.8.0` format (first line starts with “herdr” and includes a version token).
     * Prevents false positives caused by shims with the same name but different functionality in the PATH or those that only print usage information.
     */
    internal fun isVersionOutput(raw: String): Boolean =
        raw
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.matches(Regex("herdr\\s+\\S+.*")) == true

    /**
     * Candidates for the `$HOME` prefix → absolute path.
     *
     * When a command is executed via a remote shell (runCommand → sshd → user shell), $HOME will be
     * expands, so the literal candidate can hit; but downstream, receiving the literal path will inevitably fail—mosh bootstrap
     * The single quotes around `-- '<BIN>'` prevent shell expansion, while the mosh-server child process treats the text following `--` as
     * the argument is passed directly to `execvp` (not via the shell, see mosh-server.cc run_command), literal
     * `$HOME/...` will result in the error `execvp: $HOME/...: No such file or directory`;
     * Similarly, single quotes in the `exec` fallback channel (`startExec`) are not expanded. Matching results must first be parsed into
     * Absolute paths are carried through to downstream.
     *
     * If echo fails (abnormal environment), fall back to returning the literal as-is (best effort: the probe at least proves the path works).
     */
    private fun resolveHome(
        bin: String,
        runCommand: (String) -> String?,
    ): String {
        if (!bin.startsWith("\$HOME")) return bin
        val home = runCommand("echo \$HOME")?.trim()?.takeIf { it.startsWith("/") }
        return home?.let { bin.replaceFirst("\$HOME", it) } ?: bin
    }
}
