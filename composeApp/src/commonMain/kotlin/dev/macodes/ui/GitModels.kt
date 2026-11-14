package dev.macodes.ui

/**
 * Data Model and Parsing of the Git Visualization Panel (pure functions, no Compose dependencies, unit-testable).
 *
 * Data source: Execute `git status --porcelain=v1 --branch` via the terminal channel /
 * `git diff --no-color`, parse the remote output into structured UI data.
 *
 * git status single-file entry (porcelain=v1 XY line).
 */

data class GitEntry(
    val path: String,
    /** Stage status code; ' ' = no changes. */
    val indexStatus: Char,
    /** Worktree status codes; ' ' = no changes. */
    val worktreeStatus: Char,
    /** Not tracked (??). */
    val untracked: Boolean = false,
    /** Merge conflicts (UU/AA/DD, etc.). */
    val conflicted: Boolean = false,
    /** Rename/copy target path (non-empty during R/C; porcelain’s `old -> new`). */
    val renameTarget: String? = null,
    /** Untracked directories (porcelain output ends with / and is not expanded recursively). */
    val isDir: Boolean = false,
) {
    /** Display path: Rename to show the new path. */
    val displayPath: String get() = renameTarget ?: path

    /** Are there any changes in the stage area (A/M/D/R/C, etc.)? */
    val isStaged: Boolean get() = indexStatus != ' '

    /** Is it possible to view the diff (excluding untracked directories)? */
    val diffable: Boolean get() = !(untracked && isDir)

    /** Status code text (shown as badges). */
    val statusCode: String get() = if (untracked) "??" else "${indexStatus}$worktreeStatus"
}

/** Result of parsing `git status --porcelain=v1 --branch`. */
data class GitStatusResult(
    /** Current branch; null for detached HEAD or when not in a repository. */
    val branch: String?,
    /** Ahead of upstream by N commits ([ahead N]). */
    val ahead: Int,
    /** Number of commits behind the upstream ([behind N]). */
    val behind: Int,
    val entries: List<GitEntry>,
    /** Errors in the original output (e.g., `fatal:`, `/ command not found`, etc.); if no errors exist, return `null`. */
    val error: String?,
)

/** diff row type. */
enum class GitDiffKind { HEADER, HUNK, CONTEXT, ADD, REMOVE, NO_NEWLINE }

/** A row showing the parsed diff. */
data class GitDiffLine(
    val kind: GitDiffKind,
    val text: String,
)

/**
 * Parse the output of `git status --porcelain=v1 --branch`.
 *
 * When executing Git commands in the terminal, there is noise such as command echo; the parser should only parse format rows:
 * - `## branch...upstream [ahead 1, behind 2]` branch row
 * - `XY path` row (C-style quotes in porcelain paths will be unescaped)
 */
fun parseGitStatus(text: String): GitStatusResult {
    var branch: String? = null
    var ahead = 0
    var behind = 0
    val entries = ArrayList<GitEntry>()
    var error: String? = null

    for (raw in text.lineSequence()) {
        val line = raw.trimEnd('\r')
        if (line.isEmpty()) continue
        // Error detection (Git output to stderr, merged via `2>&1`)
        if (error == null &&
            (
                line.startsWith("fatal:") ||
                    line.startsWith("error:") ||
                    line.contains("command not found") ||
                    line.contains("not a git repository")
            )
        ) {
            error = line
            continue
        }
        if (line.startsWith("## ")) {
            // `## main...origin/main [ahead 1, behind 2]` / `## main` / `## HEAD (no branch)`
            val rest = line.removePrefix("## ").trim()
            val name = rest.substringBefore("...").trim()
            if (name.isNotEmpty() && name != "HEAD (no branch)") branch = name
            val aheadIdx = rest.indexOf("ahead ")
            if (aheadIdx >= 0) {
                ahead = rest.substring(aheadIdx + 6).takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            }
            val behindIdx = rest.indexOf("behind ")
            if (behindIdx >= 0) {
                behind = rest.substring(behindIdx + 7).takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            }
            continue
        }
        // Row: `XY path` (X=index, Y=worktree; `M`=modify the worktree only,
        // `??` = not tracked, `!!` = ignore, `R  old -> new` = rename; at least one column must not be blank)
        if (line.length >= 4 &&
            line[2] == ' ' &&
            (line[0] != ' ' || line[1] != ' ') &&
            (line[0] == ' ' || line[0].isLetter() || line[0] == '?' || line[0] == '!') &&
            (line[1] == ' ' || line[1].isLetter() || line[1] == '?' || line[1] == '!')
        ) {
            val x = line[0]
            val y = line[1]
            var pathPart = line.substring(3)
            val untracked = x == '?' && y == '?'
            val conflicted = (x == 'U' || y == 'U' || (x == 'A' && y == 'A') || (x == 'D' && y == 'D'))
            var renameTarget: String? = null
            // Rename/Copy: `R  old -> new` (for paths in quotes, `R  "old" -> "new"`)
            val arrow = pathPart.indexOf(" -> ")
            if ((x == 'R' || x == 'C') && arrow >= 0) {
                renameTarget = unescapePorcelainPath(pathPart.substring(arrow + 4))
                pathPart = pathPart.substring(0, arrow)
            }
            val path = unescapePorcelainPath(pathPart)
            if (path.isEmpty()) continue
            entries.add(
                GitEntry(
                    path = path,
                    indexStatus = if (untracked) ' ' else x,
                    worktreeStatus = if (untracked) ' ' else y,
                    untracked = untracked,
                    conflicted = conflicted,
                    renameTarget = renameTarget,
                    isDir = untracked && path.endsWith('/'),
                ),
            )
            continue
        }
        // Ignore other rows (such as command echo and other noise).
    }
    return GitStatusResult(branch, ahead, behind, entries, error)
}

/**
 * Parse the output of `git diff --no-color` (including `--no-index` diffs for new files and binary notices).
 * Skip rows that are not in diff format (such as command output).
 */
fun parseGitDiff(text: String): List<GitDiffLine> {
    val lines = ArrayList<GitDiffLine>()
    for (raw in text.lineSequence()) {
        val line = raw.trimEnd('\r')
        if (line.isEmpty()) {
            lines.add(GitDiffLine(GitDiffKind.CONTEXT, ""))
            continue
        }
        val kind =
            when {
                line.startsWith("diff --git ") ||
                    line.startsWith("index ") ||
                    line.startsWith("new file mode ") ||
                    line.startsWith("deleted file mode ") ||
                    line.startsWith("old mode ") ||
                    line.startsWith("new mode ") ||
                    line.startsWith("similarity ") ||
                    line.startsWith("rename ") ||
                    line.startsWith("copy ") ||
                    line.startsWith("Binary files ") ||
                    line.startsWith("--- ") ||
                    line.startsWith("+++ ") -> GitDiffKind.HEADER
                line.startsWith("@@") -> GitDiffKind.HUNK
                line.startsWith("+") -> GitDiffKind.ADD
                line.startsWith("-") -> GitDiffKind.REMOVE
                line.startsWith("\\ No newline") -> GitDiffKind.NO_NEWLINE
                line.startsWith(" ") -> GitDiffKind.CONTEXT
                else -> continue // Skip noise (such as command echo).
            }
        lines.add(GitDiffLine(kind, line))
    }
    return lines
}

/** Escape single quotes in shell commands (to safely concatenate paths from Git output into remote commands). */
fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/**
 * Unescape C-style quotes in porcelain output path: `"a\tb"` → `a<TAB>b`.
 * Only paths containing special characters are enclosed in quotes; regular paths are returned as-is.
 */
fun unescapePorcelainPath(p: String): String {
    if (p.length < 2 || p.first() != '"' || p.last() != '"') return p
    val inner = p.substring(1, p.length - 1)
    val sb = StringBuilder(inner.length)
    var i = 0
    while (i < inner.length) {
        val c = inner[i]
        if (c != '\\' || i + 1 >= inner.length) {
            sb.append(c)
            i++
            continue
        }
        val n = inner[i + 1]
        when (n) {
            'a' -> {
                sb.append('\u0007')
                i += 2
            }
            'b' -> {
                sb.append('\b')
                i += 2
            }
            't' -> {
                sb.append('\t')
                i += 2
            }
            'n' -> {
                sb.append('\n')
                i += 2
            }
            'v' -> {
                sb.append('\u000b')
                i += 2
            }
            'f' -> {
                sb.append('\u000c')
                i += 2
            }
            'r' -> {
                sb.append('\r')
                i += 2
            }
            '"' -> {
                sb.append('"')
                i += 2
            }
            '\\' -> {
                sb.append('\\')
                i += 2
            }
            in '0'..'7' -> {
                // Octal escape \ooo (up to 3 digits)
                var v = n - '0'
                var j = i + 2
                var digits = 1
                while (j < inner.length && digits < 3 && inner[j] in '0'..'7') {
                    v = v * 8 + (inner[j] - '0')
                    j++
                    digits++
                }
                sb.append(v.toChar())
                i = j
            }
            else -> {
                sb.append(n)
                i += 2
            }
        }
    }
    return sb.toString()
}
