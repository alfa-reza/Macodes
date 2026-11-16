package dev.macodes.data

import dev.macodes.ssh.SshErrorCode
import dev.macodes.ssh.SshException
import dev.macodes.util.withRepoLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/** Persistence sink for ended [SessionLog] records. */
fun interface SessionLogSink {
    fun submit(log: SessionLog)
}

/** Non-blocking sink backed by [HostRepository] running persistence off the caller thread. */
class RepositorySessionLogSink(
    private val repository: HostRepository,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SessionLogSink {
    override fun submit(log: SessionLog) {
        scope.launch(dispatcher) {
            repository.saveSessionLog(log)
        }
    }
}

/**
 * Thread-safe, idempotent lifecycle recorder for a single SSH, Mosh, or SFTP session attempt.
 * Records events in memory and persists only once the session reaches a terminal state.
 */
class SessionLogRecorder(
    private val host: Host,
    val protocol: ConnectionLogProtocol,
    private val sink: SessionLogSink,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val sessionId: String = newId(),
) {
    private val lock = Any()
    private val startedAt: Long = clock()
    private val events = mutableListOf<SessionLogEvent>()
    private var openedAt: Long? = null
    private var reconnecting: Boolean = false
    private var finalized: Boolean = false

    val isFinalized: Boolean
        get() = withRepoLock(lock) { finalized }

    val isOpened: Boolean
        get() = withRepoLock(lock) { openedAt != null }

    fun record(
        type: SessionLogEventType,
        detailCode: String? = null,
        timestamp: Long = clock(),
    ) {
        withRepoLock(lock) {
            if (finalized) return@withRepoLock
            // Suppress duplicate handshake milestones while auto-reconnect is retrying the same session.
            if (reconnecting && type in reconnectDedupTypes && events.any { it.type == type }) {
                return@withRepoLock
            }
            val safeDetail = SessionLogSanitizer.sanitizeDetailCode(detailCode)
            events +=
                SessionLogEvent(
                    id = newId(),
                    sessionId = sessionId,
                    type = type,
                    timestamp = timestamp,
                    detailCode = safeDetail,
                )
        }
    }

    fun markHostVerification(verified: Boolean) {
        record(
            if (verified) {
                SessionLogEventType.HOST_VERIFICATION_SUCCEEDED
            } else {
                SessionLogEventType.HOST_VERIFICATION_FAILED
            },
        )
    }

    fun markAuthSucceeded(method: String? = null) {
        record(SessionLogEventType.AUTHENTICATION_SUCCEEDED, detailCode = method)
    }

    fun markSessionOpened() {
        withRepoLock(lock) {
            if (finalized) return@withRepoLock
            val now = clock()
            val firstOpen = openedAt == null
            if (firstOpen) {
                openedAt = now
            }
            reconnecting = false
            if (firstOpen) {
                events +=
                    SessionLogEvent(
                        id = newId(),
                        sessionId = sessionId,
                        type = SessionLogEventType.SESSION_OPENED,
                        timestamp = now,
                    )
            }
        }
    }

    fun beginReconnectAttempt() {
        withRepoLock(lock) {
            if (!finalized) {
                reconnecting = true
            }
        }
    }

    fun completeNormal(detailCode: String? = "user") {
        val logToSave =
            withRepoLock(lock) {
                if (finalized) return@withRepoLock null
                val openTime = openedAt
                val now = clock()
                if (openTime == null) {
                    return@withRepoLock buildFinalLogLocked(
                        status = SessionLogStatus.FAILED,
                        endedAt = now,
                        errorCode = SessionLogErrorCode.CONNECTION_CANCELLED,
                        terminalEvent = SessionLogEventType.CONNECTION_FAILED,
                        detailCode = SessionLogErrorCode.CONNECTION_CANCELLED.name,
                    )
                }
                buildFinalLogLocked(
                    status = SessionLogStatus.COMPLETED,
                    endedAt = now,
                    errorCode = null,
                    terminalEvent = SessionLogEventType.SESSION_CLOSED,
                    detailCode = detailCode,
                )
            } ?: return
        sink.submit(logToSave)
    }

    fun fail(
        error: Throwable? = null,
        reason: String? = null,
        explicitCode: SessionLogErrorCode? = null,
        terminalEvent: SessionLogEventType? = null,
    ) {
        val logToSave =
            withRepoLock(lock) {
                if (finalized) return@withRepoLock null
                val code = explicitCode ?: SessionLogSanitizer.classifyError(error, reason, protocol)
                val now = clock()
                if (openedAt != null) {
                    return@withRepoLock buildFinalLogLocked(
                        status = SessionLogStatus.INTERRUPTED,
                        endedAt = now,
                        errorCode = code,
                        terminalEvent = SessionLogEventType.CONNECTION_INTERRUPTED,
                        detailCode = code.name,
                    )
                }
                if (error is SshException) {
                    when (error.code) {
                        SshErrorCode.AUTH_FAILED,
                        SshErrorCode.PASSWORD_AUTH_UNSUPPORTED,
                        SshErrorCode.KEY_PASSPHRASE_CANCELLED,
                        -> appendIfLastDifferentLocked(SessionLogEventType.AUTHENTICATION_FAILED, now, code.name)
                        SshErrorCode.KEY_LOAD_FAILED,
                        null,
                        -> Unit
                    }
                }
                val finalEvent =
                    terminalEvent ?: when (code) {
                        SessionLogErrorCode.UDP_TIMED_OUT -> SessionLogEventType.UDP_CONNECTION_TIMED_OUT
                        else -> SessionLogEventType.CONNECTION_FAILED
                    }
                buildFinalLogLocked(
                    status = SessionLogStatus.FAILED,
                    endedAt = now,
                    errorCode = code,
                    terminalEvent = finalEvent,
                    detailCode = code.name,
                )
            } ?: return
        sink.submit(logToSave)
    }

    fun interrupt(
        error: Throwable? = null,
        reason: String? = null,
        explicitCode: SessionLogErrorCode? = null,
    ) {
        val logToSave =
            withRepoLock(lock) {
                if (finalized) return@withRepoLock null
                val code = explicitCode ?: SessionLogSanitizer.classifyError(error, reason, protocol)
                val now = clock()
                if (openedAt == null) {
                    return@withRepoLock buildFinalLogLocked(
                        status = SessionLogStatus.FAILED,
                        endedAt = now,
                        errorCode = code,
                        terminalEvent = SessionLogEventType.CONNECTION_FAILED,
                        detailCode = code.name,
                    )
                }
                buildFinalLogLocked(
                    status = SessionLogStatus.INTERRUPTED,
                    endedAt = now,
                    errorCode = code,
                    terminalEvent = SessionLogEventType.CONNECTION_INTERRUPTED,
                    detailCode = code.name,
                )
            } ?: return
        sink.submit(logToSave)
    }

    private fun appendIfLastDifferentLocked(
        type: SessionLogEventType,
        timestamp: Long,
        detailCode: String?,
    ) {
        if (events.lastOrNull()?.type != type) {
            events +=
                SessionLogEvent(
                    id = newId(),
                    sessionId = sessionId,
                    type = type,
                    timestamp = timestamp,
                    detailCode = SessionLogSanitizer.sanitizeDetailCode(detailCode),
                )
        }
    }

    private fun buildFinalLogLocked(
        status: SessionLogStatus,
        endedAt: Long,
        errorCode: SessionLogErrorCode?,
        terminalEvent: SessionLogEventType,
        detailCode: String?,
    ): SessionLog {
        finalized = true
        reconnecting = false
        appendIfLastDifferentLocked(terminalEvent, endedAt, detailCode)
        val durationMs = openedAt?.let { (endedAt - it).coerceAtLeast(0L) }
        val isQuickConnect = host.id.startsWith("quickconnect:")
        return SessionLog(
            id = sessionId,
            hostId = if (isQuickConnect) null else host.id,
            hostLabel = resolveSessionLogHostLabel(host),
            username = host.username,
            hostname = host.hostname,
            port = host.port,
            protocol = protocol,
            startedAt = startedAt,
            endedAt = endedAt.coerceAtLeast(startedAt),
            durationMs = durationMs,
            status = status,
            errorCode = errorCode,
            events = events.toList(),
        )
    }

    private companion object {
        val reconnectDedupTypes =
            setOf(
                SessionLogEventType.CONNECTION_STARTED,
                SessionLogEventType.CONNECTION_ESTABLISHED,
                SessionLogEventType.HOST_VERIFICATION_SUCCEEDED,
                SessionLogEventType.AUTHENTICATION_SUCCEEDED,
                SessionLogEventType.SSH_BOOTSTRAP_STARTED,
                SessionLogEventType.SSH_BOOTSTRAP_SUCCEEDED,
            )
    }
}
