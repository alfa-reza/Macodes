package dev.macodes.data

import kotlinx.serialization.Serializable

/** Transport recorded in connection logs. */
@Serializable
enum class ConnectionLogProtocol {
    SSH,
    MOSH,
    SFTP,
}

/** Connection lifecycle outcome recorded in Workspace logs. */
@Serializable
enum class ConnectionLogEvent {
    CONNECTED,
    DISCONNECTED,
    FAILED,
}

/** Structured connection history entry shown in Workspace -> Logs. */
@Serializable
data class ConnectionLogEntry(
    val id: String,
    val hostLabel: String,
    val endpoint: String,
    val protocol: ConnectionLogProtocol,
    val event: ConnectionLogEvent,
    val detail: String = "",
    val timestamp: Long,
)
