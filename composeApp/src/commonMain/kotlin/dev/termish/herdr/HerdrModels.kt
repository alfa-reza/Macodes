package dev.termish.herdr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * herdr (the runtime on which your coding agents run) agent state model.
 *
 * This file describes only the minimum set of fields required for V1 polling (a subset of the `herdr api snapshot` output),
 * Parsing follows the `ignoreUnknownKeys` fault-tolerance policy—herdr upgrades that add fields do not break client code.
 * See the complete protocol in the herdr repository at docs/socket-api.mdx (session.snapshot / events.subscribe).
 *
 * herdr agent semantic status (pane.agent_status).
 */

@Serializable
enum class HerdrAgentStatus {
    @SerialName("idle")
    IDLE,

    @SerialName("working")
    WORKING,

    @SerialName("blocked")
    BLOCKED,

    @SerialName("done")
    DONE,

    @SerialName("unknown")
    UNKNOWN,
}

/** A single pane (terminal window) in a session snapshot. */
@Serializable
data class HerdrPaneInfo(
    @SerialName("pane_id") val paneId: String,
    @SerialName("workspace_id") val workspaceId: String = "",
    @SerialName("tab_id") val tabId: String = "",
    /** Detected agent name (pi / codex / claude …); null for non-agent panes. */
    val agent: String? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    @SerialName("agent_status") val agentStatus: HerdrAgentStatus = HerdrAgentStatus.UNKNOWN,
    @SerialName("terminal_title") val terminalTitle: String? = null,
    @SerialName("terminal_title_stripped") val terminalTitleStripped: String? = null,
    val cwd: String? = null,
    val focused: Boolean = false,
    val revision: Long = 0,
)

/** Agents detected in session snapshots (array elements of `agents`; parallel to the `panes` array). */
@Serializable
data class HerdrAgentInfo(
    @SerialName("pane_id") val paneId: String,
    @SerialName("workspace_id") val workspaceId: String = "",
    @SerialName("tab_id") val tabId: String = "",
    val agent: String? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    @SerialName("agent_status") val agentStatus: HerdrAgentStatus = HerdrAgentStatus.UNKNOWN,
    @SerialName("terminal_title") val terminalTitle: String? = null,
    @SerialName("terminal_title_stripped") val terminalTitleStripped: String? = null,
    val cwd: String? = null,
    val focused: Boolean = false,
    @SerialName("state_change_seq") val stateChangeSeq: Long = 0,
    val revision: Long = 0,
)

/** Top-level session snapshot of `herdr api snapshot`. */
@Serializable
data class HerdrSessionSnapshot(
    val version: String = "",
    val protocol: Long = 0,
    val workspaces: List<HerdrWorkspaceInfo> = emptyList(),
    val tabs: List<HerdrTabInfo> = emptyList(),
    val panes: List<HerdrPaneInfo> = emptyList(),
    val agents: List<HerdrAgentInfo> = emptyList(),
)

@Serializable
data class HerdrWorkspaceInfo(
    @SerialName("workspace_id") val workspaceId: String,
    val label: String = "",
    @SerialName("pane_count") val paneCount: Int = 0,
    @SerialName("tab_count") val tabCount: Int = 0,
)

@Serializable
data class HerdrTabInfo(
    @SerialName("tab_id") val tabId: String,
    @SerialName("workspace_id") val workspaceId: String = "",
    val label: String = "",
    @SerialName("pane_count") val paneCount: Int = 0,
)

/** herdr API errors (CLI output compatible). */
@Serializable
data class HerdrApiError(
    val code: String = "",
    val message: String = "",
)

/** `herdr api snapshot` CLI output: `{"id","result":{"snapshot":…}}` (the structure of the `result` in the raw socket response is the same). */
@Serializable
data class HerdrCliResponse(
    val id: String = "",
    val result: HerdrCliResult? = null,
    val error: HerdrApiError? = null,
)

@Serializable
data class HerdrCliResult(
    val snapshot: HerdrSessionSnapshot? = null,
    /** The `type` field in raw socket responses (e.g., `session_snapshot`); CLI output does not include this field. */
    val type: String? = null,
)

private val json =
    Json {
        ignoreUnknownKeys = true // herdr upgrade adds fields without breaking client functionality
        coerceInputValues = true // Empty strings or missing enumeration values are handled as default.
    }

/**
 * Parse the output of `herdr api snapshot`. CLI output and raw socket responses
 * The `result` structure is similar (CLI is a socket client); consistently follow
 * `result.snapshot` extraction; returns null on parse failure (no exception thrown).
 */
fun parseHerdrSnapshot(raw: String): HerdrSessionSnapshot? =
    try {
        json.decodeFromString<HerdrCliResponse>(raw).result?.snapshot
    } catch (_: Exception) {
        null
    }
