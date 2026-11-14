package dev.macodes.agent

enum class AgentBridgeState {
    IDLE,
    CONNECTING,
    NEEDS_INSTALL,
    INSTALLING,
    READY,
    UNSUPPORTED_PLATFORM,
    ERROR,
}

data class AgentBridgeAgent(
    val id: String,
    val label: String,
    val available: Boolean,
    val supported: Boolean,
)

data class AgentBridgeSessionInfo(
    val id: String,
    val agent: String,
    val title: String,
    val cwd: String,
    val busy: Boolean,
    val messageCount: Int,
    val model: String? = null,
    val provider: String? = null,
    val waitingApproval: Boolean = false,
)

/** A recoverable session that the Agent CLI saves on a remote host. */
data class AgentNativeSessionInfo(
    val nativeId: String,
    val agent: String,
    val title: String,
    val cwd: String,
    val messageCount: Int,
    val updatedAt: Long,
    val importedSessionId: String? = null,
)

data class AgentApprovalRequest(
    val id: String,
    val sessionId: String,
    val turnId: String,
    val agent: String,
    val kind: String,
    val title: String,
    val message: String,
    val command: String,
    val cwd: String,
    val details: String,
    val placeholder: String,
    val prefill: String,
    val options: List<String>,
    val secret: Boolean = false,
    val allowCustom: Boolean = false,
    val timeoutMs: Long = 0L,
)

/** Provider credentials are passed only within the current SSH request; Bridge does not support persistence for the [apiKey]. */
data class AgentProviderRuntime(
    val id: String,
    val type: String,
    val apiKey: String,
    val baseUrl: String,
    /** Anthropic-compatible endpoint dedicated to Claude (available when provided by OpenAI-type providers). */
    val anthropicBaseUrl: String = "",
    /** Pi CLI’s own provider ID (e.g., deepseek), separate from the [type] wire protocol. */
    val piProvider: String = "",
)

data class AgentAttachment(
    val name: String,
    val remotePath: String,
    val size: Long,
)

/** Files generated or modified by the agent on the remote end during this round. */
data class AgentArtifact(
    val name: String,
    val remotePath: String,
    val size: Long,
    val kind: String,
)

data class AgentPendingAttachment(
    val sourceId: String,
    val name: String,
    val size: Long,
    val readChunk: () -> ByteArray?,
    val close: () -> Unit,
    val openReader: (() -> AgentAttachmentReader?)? = null,
)

data class AgentAttachmentReader(
    val readChunk: () -> ByteArray?,
    val close: () -> Unit,
)

data class AgentChatMessage(
    val role: String,
    val text: String,
    val id: String = "",
    val turnId: String = "",
    val activityId: String? = null,
    val agent: String? = null,
    val toolName: String? = null,
    val toolInput: String? = null,
    val isError: Boolean = false,
    val running: Boolean = false,
    val createdAt: Long = 0L,
    val completedAt: Long? = null,
    val attachments: List<AgentAttachment> = emptyList(),
    val artifacts: List<AgentArtifact> = emptyList(),
)

data class AgentDiagnostics(
    val reconnectAttempt: Int = 0,
    val reconnectCount: Int = 0,
    val lastRequestLatencyMs: Long? = null,
    val lastRequestMethod: String? = null,
    val loadedMessageCount: Int = 0,
    val totalMessageCount: Int = 0,
)

enum class AgentInstallPhase {
    IDLE,
    INSTALLING,
    SUCCEEDED,
    FAILED,
}

data class AgentInstallStatus(
    val agentId: String? = null,
    val phase: AgentInstallPhase = AgentInstallPhase.IDLE,
    val detail: String = "",
)

data class AgentDirectory(
    val name: String,
    val path: String,
)
