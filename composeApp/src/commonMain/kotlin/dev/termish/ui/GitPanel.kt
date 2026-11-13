package dev.termish.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.termish.herdr.parseHerdrSnapshot
import dev.termish.ssh.AuthPrompt
import dev.termish.ssh.HostKeyInfo
import dev.termish.ssh.SshCallbacks
import dev.termish.ssh.SshConnection
import dev.termish.ssh.SshSession
import dev.termish.term.TerminalBuffer
import dev.termish.term.TerminalLine
import dev.termish.ui.theme.TerminalTheme
import dev.termish.util.TermLog
import dev.termish.util.ioDispatcher
import dev.termish.util.monospaceFontFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Git commands timeout (local Git operations typically take milliseconds; even status and diff commands for large repositories take far less time than that). */
private const val GIT_TIMEOUT_MS = 30_000L

/** Upper limit for diff output (truncated via head to prevent extremely large files from flooding the terminal scrollback). */
private const val DIFF_MAX_LINES = 600

/** Extraction of the work directory from the default bash PS1 (`user@host:~/path$` / `#`). */
private val PROMPT_CWD_REGEX = Regex("""^[^\s@]+@[^\s:]+:([^\s$#]*)[$#]\s*$""")

/**
 * Working directory detection script (executed via the `exec` channel, does not interact with the terminal; POSIX sh-compatible):
 * 1. tmux focused-pane path (queryable via the tmux daemon)
 * 2. CWD of the agent process (pi/codex/claude) — the fullscreen TUI runtime shell’s
 *    cwd refers to the project directory before the agent is launched (the agent is a shell subprocess; the shell’s cwd remains unchanged).
 * 3. Current working directory (cwd) in interactive shells (bash/zsh/fish)
 * The candidate `cwd` must be verified as a repository using `git rev-parse` before being returned (in the case of multiple shell sessions, take
 * (actual workspace); do not use `tail -1` (a race condition upon process exit may result in a dead PID).
 * 4. Fallback for no verification: The current working directory (cwd) of any interactive shell—if no repository exists, have the panel display “not git”
 *    "Repository / Git not installed" as the exact error message, rather than falling back to the terminal upon detection failure.
 *
 * cwd: Obtain cross-platform (cwd_of): On Linux, use /proc/<PID>/cwd (millisecond-level accuracy); if /proc is unavailable
 * macOS/BSD lsof output (`lsof -a -p <PID> -d cwd -Fn` for n<PATH> rows) —
 * Previously, it only used /proc, so there would be a failure in remote detection on macOS (the panel reported “Unable to determine the remote working directory”).
 */
private const val WORKDIR_PROBE_SCRIPT =
    "cwd_of() { " +
        "c=\$(readlink /proc/\$1/cwd 2>/dev/null); " +
        "[ -n \"\$c\" ] || " +
        "c=\$(lsof -a -p \$1 -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1); " +
        "printf '%s' \"\$c\"; }; " +
        "probe() { " +
        "c=\$(cwd_of \$1); " +
        "[ -n \"\$c\" ] && git -C \"\$c\" rev-parse --git-dir >/dev/null 2>&1 && { echo \"\$c\"; exit 0; }; }; " +
        "tmux display-message -p '#{pane_current_path}' 2>/dev/null; " +
        "for p in pi pi-coding-agent codex claude; do " +
        "for pid in \$(pgrep -u \$(id -u) -x \$p 2>/dev/null); do probe \$pid; done; done; " +
        "for s in bash zsh fish; do " +
        "for pid in \$(pgrep -u \$(id -u) -x \$s 2>/dev/null); do probe \$pid; done; done; " +
        "for s in bash zsh fish; do " +
        "for pid in \$(pgrep -u \$(id -u) -x \$s 2>/dev/null); do " +
        "c=\$(cwd_of \$pid); " +
        "[ -n \"\$c\" ] && { echo \"\$c\"; exit 0; }; " +
        "done; done; echo ''"

/** Git command execution timed out. */
internal class GitTimeoutException : Exception("git command timed out")

/** Git command execution failure (not due to a timeout, e.g., unable to access the working directory). */
internal class GitCommandException(
    message: String,
) : Exception(message)

/** Unable to determine the remote working directory (no available independent channel) — the command is not executed; the panel maps it to localized text. */
internal class GitWorkdirUnknownException : Exception()

/**
 * Auxiliary callbacks for mosh control plane connections: Ignore all output (do not pollute the terminal buffer),
 * Host key trust carries over from the main connection (same host already confirmed); the private-key passphrase is not prompted (returns null when passwordless).
 */
private object AuxCallbacks : SshCallbacks {
    override suspend fun onOutput(data: ByteArray) {}

    override suspend fun onStderr(data: ByteArray) {}

    override fun onExitStatus(status: Int) {}

    override fun onClosed(reason: String?) {}

    override suspend fun onPrompt(prompt: AuthPrompt): List<String>? = null

    override fun verifyHostKey(hostKey: HostKeyInfo): Boolean = true
}

/**
 * Git Command Executor Abstraction: The panel depends solely on this contract and does not have any binding to a specific session format.
 * Two implementations:
 * - [TerminalGitCommandRunner]: Terminal screen (SSH reuses an authenticated connection / mosh control plane connection)
 * - [AgentGitCommandRunner]: Agent dialog page (reuses the Agent Bridge to control the connection,
 *   The working directory is taken directly from the Agent session (cwd—the project directory the agent is currently working on).
 */
internal interface GitCommandRunner {
    /** Working directory (navigate to with `cd` before execution; detect/set during refresh). */
    var workdir: String?

    /** Detect the working directory; if unavailable, return null (the command will not execute, and an error will be reported in the panel). */
    suspend fun fetchWorkdir(): String?

    /** Execute Git commands and return the full output (with ANSI characters stripped); throw [GitCommandException] / [GitWorkdirUnknownException] / [GitTimeoutException] on failure. */
    suspend fun run(
        command: String,
        timeoutMs: Long = GIT_TIMEOUT_MS,
    ): String
}

/**
 * Terminal Git Command Executor—**Never performs command injection into the interactive terminal**; selects a separate channel based on session mode:
 *
 * - **SSH/herdr**: `SshSession.runCommand` standalone exec channel (reuses an authenticated connection,
 *   No PTY, non-interactive shell / herdr TUI), output is caught directly; herdr's cwd
 *   is the workspace in the active pane.
 * - **mosh**: Independent SSH connection for the control plane `connectAndRun` (official mosh architecture:
 *   (Interaction uses UDP; the control plane uses SSH).
 *
 * The working directory is detected by [fetchWorkdir]; if it cannot be detected, a [GitWorkdirUnknownException] is thrown.
 * (Error messages appear in the panel; the terminal remains uncluttered).
 */
internal class TerminalGitCommandRunner(
    private val controller: TerminalController,
) : GitCommandRunner {
    /** Herdr sessions: cwd prioritizes the Herdr snapshot (the workspace in the focus pane).
     *  herdr is a session-launch type rather than a host-connection mode; the detection path remains unchanged. */
    val herdrMode: Boolean get() = controller.launchHerdr && controller.herdrBin != null

    /** Working directory (first use `cd` to set the directory in a standalone exec channel; detected during refresh—see [fetchWorkdir]). */
    override var workdir: String? = null

    /** Reuse the exec channel of an already authenticated connection (SSH/herdr mode). */
    private val execSession: SshSession?
        get() = controller.session?.takeIf { it.isActive() }

    /**
     * Detect the Git working directory (using `cd` to locate it before executing in a separate channel), and organize by mode:
     * 1. herdr: `herdr api snapshot` — the current working directory (the directory where the agent is modifying code) in the focus pane
     * 2. tmux: `tmux display-message -p '#{pane_current_path}'` (tmux daemon
     *    You can query the focus pane path from any process—standard practice for agent workflows)
     * 3. /proc process detection: `readlink /proc/<PID>/cwd` to retrieve the agent (pi/codex/claude) or
     *    The working directory of an interactive shell process—**critical in fullscreen program (TUI running) scenarios**:
     *    There is no prompt to parse in the terminal, but the shell’s current working directory (cwd) is the project directory prior to the agent’s launch.
     *    (An agent is a subprocess of the shell; the shell’s current working directory remains unchanged.)
     * 4. Bash prompt: parse \w in the terminal's last prompt line (bare-shell scenario; PS1 customization
     *    (format mismatch → skip; under mosh the bootstrap channel is closed and `~` can't be parsed, so only absolute paths are accepted)
     * If no result is returned, return null (the caller will receive an error—**do not revert to the interactive terminal for command injection**).
     * mosh mode (SSH bootstrap channel closed): probing goes via the control-plane connection (connectAndRun).
     */
    override suspend fun fetchWorkdir(): String? {
        if (herdrMode) {
            return withContext(ioDispatcher()) {
                val bin = controller.herdrBin?.let { shellQuote(it) } ?: "herdr"
                TermLog.i("git") { "herdr workdir: bin=$bin session=${controller.session != null}" }
                val raw =
                    controller.session?.runCommand("$bin api snapshot", 5_000)
                        ?: run {
                            TermLog.w("git") { "herdr snapshot: no session or null output" }
                            return@withContext null
                        }
                TermLog.i("git") { "herdr snapshot raw=${raw.take(120)}" }
                val snap =
                    parseHerdrSnapshot(raw) ?: run {
                        TermLog.w("git") { "herdr snapshot parse failed" }
                        return@withContext null
                    }
                snap.panes
                    .firstOrNull { it.focused }
                    ?.cwd
                    ?.takeIf { it.isNotBlank() }
                    ?: snap.agents
                        .firstOrNull { it.focused }
                        ?.cwd
                        ?.takeIf { it.isNotBlank() }
                    ?: snap.panes.firstOrNull { !it.cwd.isNullOrBlank() }?.cwd
            }
        }
        // Parsing the \w token (most accurate in the current session; automatically skipped if it does not match in fullscreen programs or when using a custom PS1)
        val promptPath = parsePromptCwd(controller.buffer)
        if (promptPath != null) {
            val session = execSession
            if (session != null) {
                val home =
                    withContext(ioDispatcher()) {
                        session.runCommand("echo \$HOME", 3_000)
                    }?.trim().orEmpty()
                val resolved =
                    when {
                        promptPath == "~" -> home
                        promptPath.startsWith("~/") -> home + promptPath.removePrefix("~")
                        else -> promptPath
                    }
                if (resolved.isNotBlank() && resolved.startsWith("/")) {
                    TermLog.d("git") { "workdir via prompt: $resolved" }
                    return resolved
                }
            } else if (promptPath.startsWith("/")) {
                // mosh (bootstrap channel closed): cannot parse ~; absolute paths usable directly
                TermLog.d("git") { "workdir via prompt: $promptPath" }
                return promptPath
            }
        }
        // tmux → /proc process monitoring (agent preferred; fallback to interactive shell)
        val session = execSession
        if (session != null) {
            val raw =
                withContext(ioDispatcher()) {
                    session.runCommand(WORKDIR_PROBE_SCRIPT, 5_000)
                }
            TermLog.d("git") { "probe raw=[${raw?.replace("\n", "\\n") ?: "null"}]" }
            val probed = firstPathLine(raw)
            if (probed != null) {
                TermLog.d("git") { "workdir via probe: $probed" }
                return probed
            }
        } else if (controller.moshSession != null) {
            // mosh: SSH bootstrap channel closed; probing goes via the control-plane connection (single connection)
            val raw =
                withContext(ioDispatcher()) {
                    val aux = newAuxConnection() ?: return@withContext null
                    try {
                        aux.connectAndRun(WORKDIR_PROBE_SCRIPT, 5_000).output
                    } catch (e: Exception) {
                        TermLog.w("git") { "aux probe failed: ${e.message}" }
                        null
                    } finally {
                        try {
                            aux.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            val probed = firstPathLine(raw)
            if (probed != null) {
                TermLog.d("git") { "workdir via aux probe: $probed" }
                return probed
            }
        }
        TermLog.d("git") { "workdir probe failed" }
        return null
    }

    /** Extract the first row containing an absolute path from the output (tmux and /proc may each output one row; do not concatenate them). */
    private fun firstPathLine(raw: String?): String? = raw?.lineSequence()?.firstOrNull { it.trimStart().startsWith("/") }?.trim()

    /** Create a new Mosh control plane connection (official Mosh architecture: interaction via UDP, control plane via a separate SSH connection). */
    private fun newAuxConnection(): SshSession? =
        try {
            val conn =
                SshConnection(
                    host = controller.host.hostname,
                    port = controller.host.port,
                    username = controller.host.username,
                    password = controller.password,
                    privateKeyPem = controller.privateKeyPem,
                    connectTimeoutMillis = 10_000,
                    keepAliveSeconds = 0,
                )
            controller.sessionFactory(conn, AuxCallbacks)
        } catch (e: Exception) {
            TermLog.w("git") { "aux connection failed: ${e.message}" }
            null
        }

    /** Extract \w from the default bash PS1 `user@host:~/path$` (or `#` for root);
     *  Rows in the input (where content follows the `$` symbol) do not match; avoid treating half-lines of commands as prompts. */
    private fun parsePromptCwd(buffer: TerminalBuffer): String? {
        val total = buffer.totalLines()
        for (i in (total - 1) downTo maxOf(0, total - 40)) {
            val text = lineText(buffer.absLine(i))
            val m = PROMPT_CWD_REGEX.find(text) ?: continue
            return m.groupValues[1]
        }
        return null
    }

    /**
     * Execute [command] and return the full output (ANSI characters stripped) — **do not perform terminal injection**:
     * 1. **Standalone exec channel**: Reuse an authenticated connection (SSH/herdr) and first switch to `<CWD>`—
     *    Commands do not enter the interactive terminal, keeping the screen clutter-free (industry standard: VS Code Remote-SSH’s git
     *    (Operations also go through a separate exec channel.)
     * 2. **mosh control plane connection** (mosh's SSH bootstrap channel is closed): lazily create an independent SSH connection
     *    `connectAndRun` (mosh official architecture: interaction via UDP, control plane via SSH).
     * Throw [GitWorkdirUnknownException] when the working directory is unknown (error reported in the panel; herdr mode)
     * Throw [GitCommandException] and prevent injection into the herdr/pi input field.
     */
    override suspend fun run(
        command: String,
        timeoutMs: Long,
    ): String {
        val dir =
            workdir ?: run {
                // For the "herdr" mode, report the specific cause; for all other scenarios, uniformly report "Unable to determine working directory."
                if (herdrMode) throw GitCommandException("无法获取 herdr 工作区（snapshot 失败）")
                throw GitWorkdirUnknownException()
            }
        val full = gitCommandInDirectory(dir, command)
        // 1) Independent exec channel: Reuse authenticated connections (SSH/herdr)
        val session = execSession
        if (session != null) {
            TermLog.d("git") { "run exec cwd=$dir cmd=$full" }
            return withContext(ioDispatcher()) {
                session.runCommand(full, timeoutMs) ?: throw GitTimeoutException()
            }.let { stripAnsiOutput(it) }
        }
        // 2) mosh control plane connection: SSH bootstrap channel closed; lazily create an independent connection (mosh official architecture)
        if (controller.moshSession != null) {
            TermLog.d("git") { "run aux-ssh cwd=$dir cmd=$full" }
            return withContext(ioDispatcher()) {
                val aux = newAuxConnection() ?: throw GitTimeoutException()
                try {
                    aux.connectAndRun(full, timeoutMs).output
                } catch (e: Exception) {
                    TermLog.w("git") { "aux ssh failed: ${e.message}" }
                    throw GitTimeoutException()
                } finally {
                    try {
                        aux.close()
                    } catch (_: Exception) {
                    }
                }
            }.let { stripAnsiOutput(it) }
        }
        // Extreme cases such as connection failures or race conditions: neither the exec nor mosh channels are available
        throw GitWorkdirUnknownException()
    }

    private fun lineText(line: TerminalLine): String {
        val sb = StringBuilder(line.cols)
        for (c in line.cells) {
            if (c.isWideTail) continue
            sb.append(c.codePoint.toChar())
        }
        var end = sb.length
        while (end > 0 && sb[end - 1] == ' ') end--
        return sb.substring(0, end)
    }
}

/**
 * Git executor on the Agent dialog page: Reuse the Agent Bridge’s control connection to run a separate exec channel
 * (Do not perform injection into or interference with a running agent); working directory = Agent session cwd
 * (the project directory the agent is working on; more accurate than prompt detection.)
 */
internal class AgentGitCommandRunner(
    private val controller: dev.termish.agent.AgentBridgeController,
) : GitCommandRunner {
    override var workdir: String? = null

    override suspend fun fetchWorkdir(): String? {
        val cwd = controller.currentSession?.cwd?.takeIf { it.isNotBlank() }
        workdir = cwd
        return cwd
    }

    override suspend fun run(
        command: String,
        timeoutMs: Long,
    ): String {
        val dir = workdir ?: throw GitWorkdirUnknownException()
        val full = gitCommandInDirectory(dir, command)
        val out = controller.runRemoteCommand(full, timeoutMs) ?: throw GitTimeoutException()
        return stripAnsiOutput(out)
    }
}

/** The entire command chain is executed in the repository directory; `git -C` only constrains the first `git` and cannot override `&& git …`. */
internal fun gitCommandInDirectory(
    directory: String,
    command: String,
): String = "cd ${shellQuote(directory)} && $command"

/** Remove any remaining ANSI escape sequences from the output (git color.ui has been forced off as a double precaution). */
private fun stripAnsiOutput(s: String): String {
    if ('\u001b' !in s) return s
    return ANSI_ESCAPE_REGEX.replace(s, "")
}

// SGR/CSI and OSC sequences (double safeguard: fragments that may not have been fully consumed before terminal rendering)
private val ANSI_ESCAPE_REGEX = Regex("\u001b\\[[0-9;?]*[a-zA-Z]|\u001b\\][^\u0007]*\u0007")

/** Git status badge color scheme: Aligns with terminal ANSI semantics (modify = yellow, add = green, delete = red, etc.). */
private fun statusColor(
    code: String,
    theme: TerminalTheme,
): Color {
    val c = code.firstOrNull() ?: return theme.ansi(7)
    return when {
        c == '?' || c == '!' -> theme.ansi(6) // Not tracked/ignored: cyan
        c == 'A' -> theme.ansi(2) // New: Green
        c == 'D' -> theme.ansi(1) // Delete: red
        c == 'R' || c == 'C' -> theme.ansi(5) // Rename/Copy: purple
        c == 'U' || code.contains('U') -> theme.ansi(1) // Conflict: Red
        else -> theme.ansi(3) // Modified: yellow
    }
}

/**
 * Git Floating Panel: Floating buttons on the right side of the terminal canvas (draggable) + status/diff panel + commit dialog.
 *
 * Shows only when SSH/mosh is connected. Git commands travel only through a separate channel (SSH reuses an authenticated connection's
 * exec channel / mosh (control plane connection), **never perform injection into an interactive terminal**; when it cannot detect the working
 * directory it reports the error in the panel. herdr-mode commands run on a separate exec channel (not injected into the herdr/pi input box).
 * [open]/[onOpenChange] state hoisting: an entry also lives in the keyboard toolbar's “⋯” overflow panel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GitOverlay(
    /** Is the session connected (terminal screen: SSH/Mosh CONNECTED; Agent tab: Bridge READY)? */
    connected: Boolean,
    /** Whether the terminal is in the alternate screen of a fullscreen app (vim/tmux) (panel never injects into the TUI); always false on the Agent page. */
    inAltScreen: Boolean,
    runner: GitCommandRunner,
    theme: TerminalTheme,
    /** Panel open state (held by the caller, shared by the FAB and the toolbar overflow panel). */
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    /** Leave height at the bottom (keyboard toolbar + spacing): The FAB hovers above the toolbar. */
    bottomInset: Dp,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    if (!connected) return

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var branch by remember { mutableStateOf<String?>(null) }
    var ahead by remember { mutableIntStateOf(0) }
    var behind by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<GitEntry>>(emptyList()) }
    var selected by remember { mutableStateOf<GitEntry?>(null) }
    var diffLines by remember { mutableStateOf<List<GitDiffLine>>(emptyList()) }
    var diffLoading by remember { mutableStateOf(false) }
    var diffError by remember { mutableStateOf<String?>(null) }
    var stagedOverride by remember { mutableStateOf<Boolean?>(null) }
    var commitOpen by remember { mutableStateOf(false) }
    var commitMsg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // The Git panel does not support a “half-collapsed” state: it can only be fully expanded or collapsed. Otherwise, when scrolling down from the details page,
    // If a sheet is set to "Hidden" but its outer "open" attribute remains true, it will leave an empty state that cannot be reopened.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Maintain a hierarchical back path within the panel: diff → status page → close panel, without any dependency on the top button.
    // ModalBottomSheet / The commit dialog will consume its own return event first.
    PlatformBackHandler(enabled = open) {
        if (selected != null) {
            selected = null
        } else {
            onOpenChange(false)
        }
    }

    // Git commands use a separate exec channel (reusing an authenticated connection / mosh control plane connection) and never inject into the
    // interactive terminal — this also works in fullscreen apps like vim/tmux (commands never enter the TUI). When the working
    // directory cannot be detected, the panel reports the error (fullscreen: prompt to exit first).
    fun friendly(raw: String): String =
        when {
            raw.contains("not a git repository") -> s.git.notRepo
            raw.contains("command not found") -> s.git.notInstalled
            raw.contains("nothing to commit") -> s.git.nothingToCommit
            else -> raw.take(240)
        }

    /** Extract error rows from the output and convert them into user-friendly text; return `null` if no errors are found. */
    fun extractError(out: String): String? {
        for (raw in out.lineSequence()) {
            val l = raw.trim()
            if (l.startsWith("fatal:") ||
                l.startsWith("error:") ||
                l.contains("command not found") ||
                l.contains("nothing to commit")
            ) {
                return friendly(l)
            }
        }
        return null
    }

    /** Execute the command (busy to prevent re-entry), and return the error message or null. */
    suspend fun exec(cmd: String): String? {
        if (busy) return null
        busy = true
        try {
            return extractError(runner.run(cmd))
        } catch (e: GitCommandException) {
            return e.message
        } catch (e: GitWorkdirUnknownException) {
            return s.git.workdirUnknown
        } catch (e: GitTimeoutException) {
            return s.git.timeout
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e.message
        } finally {
            busy = false
        }
    }

    /**
     * Detect the Git working directory (located over a separate channel). herdr goes via snapshot; SSH/mosh via
     * prompt parsing / tmux / /proc process probing. If undetectable, the command does not run; the panel reports an
     * error and never falls back to injecting commands into the interactive terminal.
     */
    suspend fun probeWorkdir() {
        runner.workdir = runner.fetchWorkdir()
        TermLog.d("git") { "workdir=${runner.workdir}" }
    }

    suspend fun refresh() {
        if (busy) return
        busy = true
        loading = true
        error = null
        try {
            probeWorkdir()
            val out =
                runner.run(
                    "git -c color.ui=false -c core.quotepath=false status --porcelain=v1 --branch 2>&1 | head -n 2001",
                )
            val res = parseGitStatus(out)
            error = res.error?.let { friendly(it) }
            branch = res.branch
            ahead = res.ahead
            behind = res.behind
            entries = res.entries
        } catch (e: GitWorkdirUnknownException) {
            error = s.git.workdirUnknown
        } catch (e: GitTimeoutException) {
            error = s.git.timeout
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
            busy = false
        }
    }

    suspend fun loadDiff(
        entry: GitEntry,
        staged: Boolean,
    ) {
        if (busy || !entry.diffable) return
        busy = true
        diffLoading = true
        diffError = null
        try {
            val cmd =
                when {
                    entry.untracked ->
                        "git -c color.ui=false -c core.quotepath=false diff --no-index --no-color -- /dev/null " +
                            "${shellQuote(entry.path)}"
                    staged ->
                        "git -c color.ui=false -c core.quotepath=false diff --cached --no-color -- ${shellQuote(
                            entry.path,
                        )}"
                    else ->
                        "git -c color.ui=false -c core.quotepath=false diff --no-color -- ${shellQuote(entry.path)}"
                }
            val out = runner.run("$cmd 2>&1 | head -n ${DIFF_MAX_LINES + 1}")
            diffLines = parseGitDiff(out)
            diffError = extractError(out)
        } catch (e: GitWorkdirUnknownException) {
            diffError = s.git.workdirUnknown
        } catch (e: GitTimeoutException) {
            diffError = s.git.timeout
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diffError = e.message
        } finally {
            diffLoading = false
            busy = false
        }
    }

    suspend fun doCommit() {
        val msg = commitMsg.trim()
        if (msg.isEmpty()) return
        commitOpen = false
        val err = exec("git add -A 2>&1 && git commit -m ${shellQuote(msg)} 2>&1")
        if (err == null) {
            onToast(s.git.committed)
            commitMsg = ""
        } else {
            error = err
        }
        refresh()
    }

    // Refresh the status when the panel is opened; clear the view when closed.
    LaunchedEffect(open) {
        if (open) refresh() else selected = null
    }
    // Selected files: Reset staged preferences and start diff loading
    LaunchedEffect(selected) {
        stagedOverride = null
    }
    val stagedOnly = selected?.let { it.isStaged && it.worktreeStatus == ' ' } ?: false
    val showStaged = stagedOverride ?: stagedOnly
    val canToggleStaged = selected?.isStaged == true && selected?.worktreeStatus != ' '
    LaunchedEffect(selected, showStaged) {
        val e = selected ?: return@LaunchedEffect
        if (e.diffable) loadDiff(e, showStaged)
    }

    // The entry has been moved to the bottom-right menu (CanvasToolMenu): Since Git is rarely used, it no longer takes up space on the canvas.
    // Independent floating position; the panel body remains unchanged.
    if (open) {
        ModalBottomSheet(
            onDismissRequest = {
                // Swiping down, tapping the mask, and the Sheet itself going back all indicate closing. At this point, Material has already
                // When a sheet is moved to "Hidden," the outer "open" must sync; clearing only the detail level is not sufficient.
                selected = null
                onOpenChange(false)
            },
            sheetState = sheetState,
            containerColor = theme.background(),
            contentColor = theme.foreground(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.72f)
                    .imePadding(),
            ) {
                // Fullscreen programs (vim/tmux) where the working directory cannot be detected: display a tooltip only; do not execute.
                // (Do not perform TUI injection); if detection is successful (via a separate exec channel), proceed as normal.
                if (inAltScreen && runner.workdir == null && !loading) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                s.git.fullscreenHint,
                                style = MaterialTheme.typography.bodyMedium,
                                color = theme.foreground(),
                            )
                            Spacer(Modifier.height(16.dp))
                            TextButton(onClick = { onOpenChange(false) }) {
                                Text(s.terminalCancel)
                            }
                        }
                    }
                } else if (selected == null) {
                    GitStatusHeader(
                        theme = theme,
                        branch = branch,
                        ahead = ahead,
                        behind = behind,
                        entryCount = entries.size,
                        loading = loading,
                        onRefresh = { scope.launch { refresh() } },
                        onClose = { onOpenChange(false) },
                    )
                    HorizontalDivider(color = theme.foreground().copy(alpha = 0.2f))
                    when {
                        loading -> GitCenteredHint(theme, s.git.loading, spinner = true, modifier = Modifier.weight(1f))
                        error != null ->
                            GitCenteredHint(
                                theme,
                                error!!,
                                retry = { scope.launch { refresh() } },
                                onClose = { onOpenChange(false) },
                                modifier = Modifier.weight(1f),
                            )
                        entries.isEmpty() -> GitCenteredHint(theme, s.git.empty, modifier = Modifier.weight(1f))
                        else ->
                            LazyColumn(
                                Modifier.weight(1f),
                            ) {
                                items(entries, key = { it.path }) { entry ->
                                    GitFileRow(entry, theme, s) { selected = entry }
                                }
                            }
                    }
                    // Bottom actions: Stage / Unstage / Commit
                    HorizontalDivider(color = theme.foreground().copy(alpha = 0.2f))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    exec("git add -A 2>&1")
                                    refresh()
                                }
                            },
                            enabled = entries.isNotEmpty() && !busy,
                        ) {
                            Text(s.git.stageAll, color = theme.ansi(2))
                        }
                        if (entries.any { it.isStaged }) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        exec("git reset -q 2>&1")
                                        refresh()
                                    }
                                },
                                enabled = !busy,
                            ) {
                                Text(s.git.unstageAll, color = theme.ansi(3))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { commitOpen = true },
                            enabled = entries.isNotEmpty() && !busy,
                        ) {
                            Text(s.git.commit, color = theme.ansi(2), fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    // ---- Diff Page ----
                    val entry = selected!!
                    GitDiffHeader(
                        theme = theme,
                        entry = entry,
                        staged = showStaged,
                        canToggle = canToggleStaged,
                        lines = diffLines,
                        onBack = { selected = null },
                        onToggleStaged = { stagedOverride = it },
                    )
                    HorizontalDivider(color = theme.foreground().copy(alpha = 0.2f))
                    when {
                        !entry.diffable -> GitCenteredHint(theme, s.git.dirHint, modifier = Modifier.weight(1f))
                        diffLoading ->
                            GitCenteredHint(
                                theme,
                                s.git.diffLoading,
                                spinner = true,
                                modifier = Modifier.weight(1f),
                            )
                        diffError != null ->
                            GitCenteredHint(
                                theme,
                                diffError!!,
                                retry = {
                                    scope.launch { loadDiff(entry, showStaged) }
                                },
                                onClose = { selected = null },
                                modifier = Modifier.weight(1f),
                            )
                        diffLines.isEmpty() -> GitCenteredHint(theme, s.git.noDiff, modifier = Modifier.weight(1f))
                        else ->
                            LazyColumn(Modifier.weight(1f)) {
                                items(diffLines) { line -> GitDiffRow(line, theme) }
                                if (diffLines.size >= DIFF_MAX_LINES) {
                                    item { GitCenteredHint(theme, s.git.diffTruncated) }
                                }
                            }
                    }
                }
            }
        }
    }

    // ---- Commit Dialog ----
    if (commitOpen) {
        AlertDialog(
            onDismissRequest = { commitOpen = false },
            title = { Text(s.git.commitTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = commitMsg,
                        onValueChange = { commitMsg = it },
                        label = { Text(s.git.commitMessage) },
                        minLines = 2,
                    )
                    Text(
                        s.git.commitHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { scope.launch { doCommit() } },
                    enabled = commitMsg.isNotBlank() && !busy,
                ) {
                    Text(s.git.commitConfirm)
                }
            },
            dismissButton = {
                TextButton(onClick = { commitOpen = false }) {
                    Text(s.terminalCancel)
                }
            },
        )
    }
}

/** Panel header: Branch + ahead/behind + number of changes + refresh + Close. */
@Composable
private fun GitStatusHeader(
    theme: TerminalTheme,
    branch: String?,
    ahead: Int,
    behind: Int,
    entryCount: Int,
    loading: Boolean,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalAppStrings.current
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GitBranchIcon(theme.foreground(), Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            branch ?: s.git.noBranch,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = theme.foreground(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (ahead > 0 || behind > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                "↑$ahead ↓$behind",
                style = MaterialTheme.typography.labelSmall,
                color = theme.ansi(4),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            s.git.changes(entryCount),
            style = MaterialTheme.typography.labelSmall,
            color = theme.foreground().copy(alpha = 0.6f),
        )
        IconButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = s.git.refresh,
                tint = theme.foreground().copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = s.git.close,
                tint = theme.foreground().copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Diff page header: Back + file path + stage toggle + row count. */
@Composable
private fun GitDiffHeader(
    theme: TerminalTheme,
    entry: GitEntry,
    staged: Boolean,
    canToggle: Boolean,
    lines: List<GitDiffLine>,
    onBack: () -> Unit,
    onToggleStaged: (Boolean) -> Unit,
) {
    val s = LocalAppStrings.current
    val adds = lines.count { it.kind == GitDiffKind.ADD }
    val removes = lines.count { it.kind == GitDiffKind.REMOVE }
    Row(
        Modifier.fillMaxWidth().padding(start = 2.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = s.navBack,
                tint = theme.foreground(),
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                entry.displayPath,
                style = MaterialTheme.typography.labelMedium,
                color = theme.foreground(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (adds + removes > 0) {
                Text(
                    "+$adds -$removes",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.foreground().copy(alpha = 0.6f),
                )
            }
        }
        if (canToggle) {
            // Toggle "Stage" / "Unstaged"
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(theme.foreground().copy(alpha = 0.08f)),
            ) {
                DiffTab(s.git.stagedTab, selected = staged, theme) { onToggleStaged(true) }
                DiffTab(s.git.worktreeTab, selected = !staged, theme) { onToggleStaged(false) }
            }
        } else if (entry.untracked) {
            Text(
                s.git.untracked,
                style = MaterialTheme.typography.labelSmall,
                color = theme.ansi(6),
                modifier = Modifier.padding(end = 4.dp),
            )
        }
    }
}

@Composable
private fun DiffTab(
    label: String,
    selected: Boolean,
    theme: TerminalTheme,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) theme.background() else theme.foreground().copy(alpha = 0.7f),
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) theme.cursor() else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** File row: Status badge + path (show the new path after renaming). */
@Composable
private fun GitFileRow(
    entry: GitEntry,
    theme: TerminalTheme,
    s: AppStrings,
    onClick: () -> Unit,
) {
    val badgeColor = statusColor(entry.statusCode, theme)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            entry.statusCode.trim(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = monospaceFontFamily(),
            color = badgeColor,
            modifier =
                Modifier
                    .width(30.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(badgeColor.copy(alpha = 0.14f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            maxLines = 1,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.displayPath,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = monospaceFontFamily(),
                color = theme.foreground(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.renameTarget != null && entry.renameTarget != entry.path) {
                Text(
                    entry.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.foreground().copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (entry.conflicted) {
            Text(
                s.git.conflicted,
                style = MaterialTheme.typography.labelSmall,
                color = theme.ansi(1),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Single-line diff rendering: + green / - red / @@ blue / context gray, monospaced font. */
@Composable
private fun GitDiffRow(
    line: GitDiffLine,
    theme: TerminalTheme,
) {
    val (color, bg) =
        when (line.kind) {
            GitDiffKind.ADD -> theme.ansi(2) to theme.ansi(2).copy(alpha = 0.10f)
            GitDiffKind.REMOVE -> theme.ansi(1) to theme.ansi(1).copy(alpha = 0.10f)
            GitDiffKind.HUNK -> theme.ansi(4) to Color.Transparent
            GitDiffKind.HEADER, GitDiffKind.NO_NEWLINE ->
                theme.foreground().copy(alpha = 0.5f) to Color.Transparent
            GitDiffKind.CONTEXT -> theme.foreground().copy(alpha = 0.8f) to Color.Transparent
        }
    Text(
        line.text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = monospaceFontFamily(),
        color = color,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(bg)
                .padding(horizontal = 12.dp, vertical = 1.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Centered tooltips within the panel (loading / error / empty state). The modifier is provided by the caller; “weight” fills the remaining space. */
@Composable
private fun GitCenteredHint(
    theme: TerminalTheme,
    text: String,
    spinner: Boolean = false,
    retry: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val s = LocalAppStrings.current
    Box(
        modifier.fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (spinner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = theme.foreground().copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(10.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = theme.foreground().copy(alpha = 0.7f),
            )
            if (retry != null) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = retry) { Text(s.git.retry, color = theme.ansi(4)) }
            }
            if (onClose != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onClose) { Text(s.git.close) }
            }
        }
    }
}

/** Hand-drawn Git branch icon (three nodes + two curves, 24 viewport normalized coordinates). */
@Composable
fun GitBranchIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.11f

        fun p(
            fx: Float,
            fy: Float,
        ) = Offset(w * fx, h * fy)
        val curve =
            Path().apply {
                // Top-left tip → Right-side fork
                moveTo(p(0.333f, 0.292f).x, p(0.333f, 0.292f).y)
                cubicTo(
                    p(0.333f, 0.45f).x,
                    p(0.333f, 0.45f).y,
                    p(0.52f, 0.45f).x,
                    p(0.52f, 0.45f).y,
                    p(0.708f, 0.5f).x,
                    p(0.708f, 0.5f).y,
                )
                // Fork on the right → main at the bottom left
                cubicTo(
                    p(0.52f, 0.55f).x,
                    p(0.52f, 0.55f).y,
                    p(0.333f, 0.55f).x,
                    p(0.333f, 0.55f).y,
                    p(0.333f, 0.708f).x,
                    p(0.333f, 0.708f).y,
                )
            }
        drawPath(curve, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawCircle(tint, w * 0.085f, p(0.333f, 0.292f))
        drawCircle(tint, w * 0.085f, p(0.333f, 0.708f))
        drawCircle(tint, w * 0.085f, p(0.708f, 0.5f))
    }
}
