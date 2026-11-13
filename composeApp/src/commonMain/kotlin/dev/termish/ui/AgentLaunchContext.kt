package dev.termish.ui

private const val MAX_AGENT_HANDOFF_TEXT_CHARS = 12_000
private const val MAX_AGENT_HANDOFF_PATHS = 100

/** One-time context passed to the Agent from the terminal or via SFTP; it is not written to the host or the Agent’s default configuration. */
data class AgentLaunchContext(
    val prompt: String,
    val directory: String? = null,
)

/**
 * The most valuable information in terminal output is usually at the end (error stack traces, exit status); if the output is very long, retain the end portion and clearly mark it as undergoing truncation.
 * [wrapPrompt] Be responsible for localization, and clearly state that the content is untrusted data to avoid treating remote output as instructions.
 */
internal fun terminalAgentLaunchContext(
    selection: String,
    directory: String?,
    wrapPrompt: (String) -> String,
): AgentLaunchContext? {
    val normalized = selection.trim()
    if (normalized.isEmpty()) return null
    val excerpt =
        if (normalized.length <= MAX_AGENT_HANDOFF_TEXT_CHARS) {
            normalized
        } else {
            "…\n" + normalized.takeLast(MAX_AGENT_HANDOFF_TEXT_CHARS)
        }
    return AgentLaunchContext(
        prompt = wrapPrompt(excerpt),
        directory = directory?.trim()?.takeIf(String::isNotEmpty),
    )
}

/**
 * SFTP files are located on the same remote host as the Agent; only absolute paths should be referenced—do not download them to a phone and then upload them.
 * Avoid duplicates and adhere to character and text limits to prevent selecting multiple items at once, which would create an excessively large prompt.
 */
internal fun sftpAgentLaunchContext(
    paths: List<String>,
    directory: String,
    wrapPrompt: (String) -> String,
): AgentLaunchContext? {
    val unique =
        paths
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .take(MAX_AGENT_HANDOFF_PATHS)
            .toList()
    if (unique.isEmpty()) return null
    val pathList = unique.joinToString("\n") { "- $it" }.take(MAX_AGENT_HANDOFF_TEXT_CHARS)
    return AgentLaunchContext(
        prompt = wrapPrompt(pathList),
        directory = directory.trim().takeIf(String::isNotEmpty),
    )
}
