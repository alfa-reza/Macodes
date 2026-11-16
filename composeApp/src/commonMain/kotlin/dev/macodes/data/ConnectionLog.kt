package dev.macodes.data

import dev.macodes.ssh.SshErrorCode
import dev.macodes.ssh.SshException
import kotlinx.serialization.Serializable

/** Transport recorded in session logs. */
@Serializable
enum class ConnectionLogProtocol {
    SSH,
    MOSH,
    SFTP,
}

/** Terminal outcome of an ended session log. */
@Serializable
enum class SessionLogStatus {
    COMPLETED,
    FAILED,
    INTERRUPTED,
}

/** Real lifecycle milestones captured along a session timeline. */
@Serializable
enum class SessionLogEventType {
    CONNECTION_STARTED,
    CONNECTION_ESTABLISHED,
    HOST_VERIFICATION_SUCCEEDED,
    HOST_VERIFICATION_FAILED,
    AUTHENTICATION_SUCCEEDED,
    AUTHENTICATION_FAILED,
    SSH_BOOTSTRAP_STARTED,
    SSH_BOOTSTRAP_SUCCEEDED,
    UDP_CONNECTION_ESTABLISHED,
    UDP_CONNECTION_TIMED_OUT,
    SESSION_OPENED,
    SESSION_CLOSED,
    CONNECTION_FAILED,
    CONNECTION_INTERRUPTED,
}

/**
 * Finite allowlist of safe, localizable failure/interruption codes.
 * Raw library exceptions, credentials, keys, tokens, and paths are never persisted.
 */
@Serializable
enum class SessionLogErrorCode {
    HOST_KEY_MISMATCH,
    HOST_KEY_REJECTED,
    AUTHENTICATION_FAILED,
    AUTHENTICATION_CANCELLED,
    INVALID_PRIVATE_KEY,
    CONNECTION_TIMED_OUT,
    UDP_TIMED_OUT,
    CONNECTION_REFUSED,
    HOST_UNREACHABLE,
    UNKNOWN_HOST,
    CONNECTION_CANCELLED,
    KEEPALIVE_TIMEOUT,
    TRANSPORT_DISCONNECTED,
    SFTP_SUBSYSTEM_FAILED,
    MOSH_SESSION_ERROR,
    UNKNOWN_ERROR,
}

/** Individual lifecycle event belonging to a [SessionLog]. */
@Serializable
data class SessionLogEvent(
    val id: String,
    val sessionId: String,
    val type: SessionLogEventType,
    val timestamp: Long,
    val detailCode: String? = null,
)

/** Completed, failed, or interrupted SSH/Mosh/SFTP session record. */
@Serializable
data class SessionLog(
    val id: String,
    val hostId: String? = null,
    val hostLabel: String,
    val username: String,
    val hostname: String,
    val port: Int,
    val protocol: ConnectionLogProtocol,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long? = null,
    val status: SessionLogStatus,
    val errorCode: SessionLogErrorCode? = null,
    val events: List<SessionLogEvent> = emptyList(),
)

/** Sort direction for the Logs list. */
enum class SessionLogSortOrder {
    NEWEST_FIRST,
    OLDEST_FIRST,
}

/** Query parameters for listing, searching, filtering, and sorting session logs. */
data class SessionLogQuery(
    val search: String = "",
    val status: SessionLogStatus? = null,
    val protocol: ConnectionLogProtocol? = null,
    val sortOrder: SessionLogSortOrder = SessionLogSortOrder.NEWEST_FIRST,
)

internal const val MAX_SESSION_LOGS = 500
internal const val SESSION_LOG_RETENTION_DAYS = 30L
internal const val SESSION_LOG_RETENTION_MS = SESSION_LOG_RETENTION_DAYS * 24L * 60L * 60L * 1000L

/** Resolves a safe display host label; unnamed Quick Connect falls back to `username@hostname`. */
fun resolveSessionLogHostLabel(host: Host): String =
    if (host.id.startsWith("quickconnect:") && (host.name.isBlank() || host.name == host.hostname)) {
        "${host.username}@${host.hostname}"
    } else {
        host.name.ifBlank { "${host.username}@${host.hostname}" }
    }

/** Enforces the 30-day and 500-session retention window, keeping newest sessions first. */
fun applySessionLogRetention(
    logs: List<SessionLog>,
    now: Long,
): List<SessionLog> {
    val cutoff = now - SESSION_LOG_RETENTION_MS
    return logs
        .filter { it.endedAt >= cutoff }
        .sortedWith(compareByDescending<SessionLog> { it.startedAt }.thenByDescending { it.endedAt })
        .take(MAX_SESSION_LOGS)
}

/** Filters and sorts [logs] according to [query]. */
fun filterAndSortSessionLogs(
    logs: List<SessionLog>,
    query: SessionLogQuery,
    resolveErrorText: (SessionLogErrorCode) -> String = { it.name },
): List<SessionLog> {
    val needle = query.search.trim().lowercase()
    val filtered =
        logs.filter { entry ->
            (query.status == null || entry.status == query.status) &&
                (query.protocol == null || entry.protocol == query.protocol) &&
                (
                    needle.isEmpty() ||
                        entry.hostLabel.lowercase().contains(needle) ||
                        entry.username.lowercase().contains(needle) ||
                        entry.hostname.lowercase().contains(needle) ||
                        "${entry.username}@${entry.hostname}:${entry.port}".lowercase().contains(needle) ||
                        entry.port.toString().contains(needle) ||
                        entry.protocol.name
                            .lowercase()
                            .contains(needle) ||
                        (entry.errorCode != null && resolveErrorText(entry.errorCode).lowercase().contains(needle))
                )
        }
    return when (query.sortOrder) {
        SessionLogSortOrder.NEWEST_FIRST ->
            filtered.sortedWith(compareByDescending<SessionLog> { it.startedAt }.thenByDescending { it.endedAt })
        SessionLogSortOrder.OLDEST_FIRST ->
            filtered.sortedWith(compareBy<SessionLog> { it.startedAt }.thenBy { it.endedAt })
    }
}

/** OWASP-compliant error classifier: maps arbitrary errors strictly into [SessionLogErrorCode]. */
object SessionLogSanitizer {
    private val allowedDetailCodes =
        setOf("key", "password", "keyboard-interactive", "user", "remote", "sftp") +
            SessionLogErrorCode.entries.map { it.name }

    /** Returns [raw] only when it belongs to the known safe enum/tag allowlist. */
    fun sanitizeDetailCode(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return trimmed.takeIf { it in allowedDetailCodes }
    }

    /** Maps a [Throwable] or internal disconnect reason into a finite [SessionLogErrorCode]. */
    fun classifyError(
        error: Throwable? = null,
        reason: String? = null,
        protocol: ConnectionLogProtocol = ConnectionLogProtocol.SSH,
    ): SessionLogErrorCode {
        if (error is SshException) {
            return when (error.code) {
                SshErrorCode.PASSWORD_AUTH_UNSUPPORTED,
                SshErrorCode.AUTH_FAILED,
                -> SessionLogErrorCode.AUTHENTICATION_FAILED
                SshErrorCode.KEY_PASSPHRASE_CANCELLED -> SessionLogErrorCode.AUTHENTICATION_CANCELLED
                SshErrorCode.KEY_LOAD_FAILED -> SessionLogErrorCode.INVALID_PRIVATE_KEY
                null -> classifyByClassOrKeywords(error, reason, protocol)
            }
        }
        return classifyByClassOrKeywords(error, reason, protocol)
    }

    private fun classifyByClassOrKeywords(
        error: Throwable?,
        reason: String?,
        protocol: ConnectionLogProtocol,
    ): SessionLogErrorCode {
        val className = error?.let { it::class.simpleName.orEmpty() } ?: ""
        when {
            className.contains("SocketTimeout", ignoreCase = true) ||
                className.contains("Timeout", ignoreCase = true) ->
                return if (protocol == ConnectionLogProtocol.MOSH &&
                    reason?.contains("udp", ignoreCase = true) == true
                ) {
                    SessionLogErrorCode.UDP_TIMED_OUT
                } else {
                    SessionLogErrorCode.CONNECTION_TIMED_OUT
                }
            className.contains("UnknownHost", ignoreCase = true) ->
                return SessionLogErrorCode.UNKNOWN_HOST
            className.contains("ConnectException", ignoreCase = true) ->
                return SessionLogErrorCode.CONNECTION_REFUSED
            className.contains("Cancellation", ignoreCase = true) ->
                return SessionLogErrorCode.CONNECTION_CANCELLED
        }
        val signal =
            listOfNotNull(reason, error?.message)
                .joinToString(" ")
                .lowercase()
        return when {
            signal.contains("udp") && signal.contains("timed out") -> SessionLogErrorCode.UDP_TIMED_OUT
            signal.contains("timed out") || signal.contains("timeout") -> SessionLogErrorCode.CONNECTION_TIMED_OUT
            signal.contains("keepalive") -> SessionLogErrorCode.KEEPALIVE_TIMEOUT
            signal.contains("host key") && signal.contains("chang") -> SessionLogErrorCode.HOST_KEY_MISMATCH
            signal.contains("host key") -> SessionLogErrorCode.HOST_KEY_REJECTED
            signal.contains("auth") && signal.contains("cancel") -> SessionLogErrorCode.AUTHENTICATION_CANCELLED
            signal.contains("auth") || signal.contains("exhausted") -> SessionLogErrorCode.AUTHENTICATION_FAILED
            signal.contains("private key") || signal.contains("keyfile") -> SessionLogErrorCode.INVALID_PRIVATE_KEY
            signal.contains("refused") -> SessionLogErrorCode.CONNECTION_REFUSED
            signal.contains("unknown host") || signal.contains("resolve") -> SessionLogErrorCode.UNKNOWN_HOST
            signal.contains("unreachable") -> SessionLogErrorCode.HOST_UNREACHABLE
            signal.contains("cancel") -> SessionLogErrorCode.CONNECTION_CANCELLED
            signal.contains("sftp") -> SessionLogErrorCode.SFTP_SUBSYSTEM_FAILED
            protocol == ConnectionLogProtocol.MOSH && signal.contains("mosh") ->
                SessionLogErrorCode.MOSH_SESSION_ERROR
            signal.isNotEmpty() -> SessionLogErrorCode.TRANSPORT_DISCONNECTED
            else -> SessionLogErrorCode.UNKNOWN_ERROR
        }
    }
}
