package dev.termish.herdr

import dev.termish.notify.NotificationCenter
import dev.termish.notify.NotificationEvent
import dev.termish.util.TermLog
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * herdr agent monitor (V1: Polls `herdr api snapshot`).
 *
 * Host-level (one per host): Execute via [runCommand] using an authenticated SSH connection
 * `herdr api snapshot`: The snapshot generates migration events via [HerdrAgentStateMachine],
 * Callback to the UI (badge/panel); send an AGENT_TASK notification after “blocked” status is confirmed in two consecutive rounds.
 * (Phone app approval entry point).
 *
 * Guidelines:
 * - The polling interval includes ±20% jitter to prevent swarm effects; the caller is responsible for ensuring that the request is made only from the foreground and only when there is an active session on the host.
 *   start() (battery), stop() when the app goes to the background or the session closes
 * - Backoff interval for failures: 3s → 15s; pause for 60s after 3 consecutive failures (when herdr is not installed or the server is not launched)
 *   (No empty loop bombing)
 * - Blocked-notification debouncing: after entering blocked, two consecutive rounds of confirmation are required before sending; transient blocked
 *   (Agent asks and answers its own questions) No false positives; Unblocked/session cancelled if it expires
 */
class HerdrMonitor(
    private val hostName: String,
    private val hostId: String,
    /** Execute control plane commands (SshSession.runCommand) on an authenticated connection. */
    private val runCommand: (String) -> String?,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 4_000,
    /** State migration event callbacks (UI badges/panel refreshes). */
    private val onEvents: (List<HerdrAgentEvent>) -> Unit = {},
    /** Callback for the complete list of agents after each snapshot round (UI panel data source; includes rounds with no changes). */
    private val onAgents: (List<HerdrAgentInfo>) -> Unit = {},
) {
    companion object {
        /**
         * `snapshot` command candidates (probed in rotation; the hit is pinned to the index).
         * The single source of truth is located at [HerdrApi.SNAPSHOT_CMD_CANDIDATES].
         */
        private val SNAPSHOT_CMD_CANDIDATES = HerdrApi.SNAPSHOT_CMD_CANDIDATES
        private const val MIN_BACKOFF_MS = 3_000L
        private const val MAX_BACKOFF_MS = 15_000L

        /** Number of rounds to pause after consecutive failures (≈60s @ 4s polling): Purely delay-driven; the test can simulate the passage of time. */
        private const val PAUSE_ROUNDS = 15
        private const val MAX_CONSECUTIVE_FAILURES = 3

        /** Number of "confirmed" rounds: A notification will be sent only if the status remains "blocked" for two consecutive rounds after entry. */
        private const val BLOCKED_CONFIRM_ROUNDS = 2
    }

    private val machine = HerdrAgentStateMachine()
    private var job: Job? = null

    /** pane_id → Number of rounds confirmed as "blocked" (a notification is sent and the item is removed once the threshold is reached). */
    private val pendingNotify = HashMap<String, Int>()

    /** Panes for which a “blocked” notification has already been sent (to prevent duplicate notifications; cleared when unblocked or exited the “blocked” state). */
    private val notifiedBlocked = HashSet<String>()

    /** Index of the currently used herdr command candidates (toggle detection upon failure of polling). */
    private var cmdIndex = 0

    /** Whether polling is in progress. */
    fun isRunning(): Boolean = job?.isActive == true

    /** Current status of each pane (read from the UI). */
    fun currentStatus(): Map<String, HerdrAgentStatus> = machine.current()

    fun start() {
        if (job?.isActive == true) return
        machine.reset()
        pendingNotify.clear()
        notifiedBlocked.clear()
        TermLog.i("herdr") { "monitor start $hostName" }
        job =
            scope.launch {
                var backoffMs = MIN_BACKOFF_MS
                var failures = 0
                var pauseRounds = 0
                while (isActive) {
                    // Pause period for consecutive failures: Do not bombard the remote server (herdr not installed/not launched/connection disconnects)
                    if (pauseRounds > 0) {
                        pauseRounds--
                        delay(jitter(pollIntervalMs))
                        continue
                    }
                    val raw = runCommand(currentCmd())
                    val snapshot = raw?.let { parseHerdrSnapshot(it) }
                    if (snapshot == null) {
                        // Failure backoff: herdr not installed/not launched/connection disconnected; move on to the next candidate command (loop through candidates)
                        cmdIndex = (cmdIndex + 1) % SNAPSHOT_CMD_CANDIDATES.size
                        failures++
                        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                        TermLog.d("herdr") { "poll failed $hostName #$failures: ${raw?.take(80) ?: "null"}" }
                        if (failures >= MAX_CONSECUTIVE_FAILURES) {
                            pauseRounds = PAUSE_ROUNDS
                            failures = 0
                            backoffMs = MIN_BACKOFF_MS
                        }
                        delay(backoffMs)
                        continue
                    }
                    failures = 0
                    backoffMs = MIN_BACKOFF_MS
                    val events = machine.update(snapshot)
                    if (events.isNotEmpty()) {
                        TermLog.d(
                            "herdr",
                        ) { "events $hostName: ${events.joinToString { it::class.simpleName ?: "?" }}" }
                        onEvents(events)
                    }
                    onAgents(snapshot.agents)
                    confirmBlocked(snapshot)
                    delay(jitter(pollIntervalMs))
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
        machine.reset()
        pendingNotify.clear()
        notifiedBlocked.clear()
        TermLog.i("herdr") { "monitor stop $hostName" }
    }

    /**
     * Blocked Confirmation and Notification: New "Blocked" events enter the confirmation queue; those confirmed for ≥2 rounds
     * If the pane is still blocked, send an AGENT_TASK notification (Unblocked/status change triggered by
     * [onEvents] Clear path sync).
     */
    private fun confirmBlocked(snapshot: HerdrSessionSnapshot) {
        val current = machine.current()
        // Removed from the confirmation queue after leaving the "blocked" pane
        pendingNotify.keys.filter { current[it] != HerdrAgentStatus.BLOCKED }.forEach { pendingNotify.remove(it) }
        for (a in snapshot.agents) {
            if (a.agentStatus != HerdrAgentStatus.BLOCKED) continue
            val rounds = (pendingNotify[a.paneId] ?: 0) + 1
            if (rounds >= BLOCKED_CONFIRM_ROUNDS) {
                pendingNotify.remove(a.paneId)
                // Duplicate prevention: Notification is sent only once for consecutive "blocked" statuses in the same pane (reset only when "unblocked").
                if (notifiedBlocked.add(a.paneId)) {
                    postBlockedNotification(a)
                }
            } else {
                pendingNotify[a.paneId] = rounds
            }
        }
    }

    private fun postBlockedNotification(a: HerdrAgentInfo) {
        TermLog.w("herdr") { "agent BLOCKED $hostName ${a.paneId} agent=${a.agent} title=${a.title()}" }
        NotificationCenter.post(
            NotificationEvent.AGENT_TASK,
            "Termish",
            blockedNotificationText(hostName, a),
            hostId = hostId,
        )
    }

    /** Notification body (a standalone pure function to facilitate testing). */
    internal fun blockedNotificationText(
        hostName: String,
        a: HerdrAgentInfo,
    ): String {
        val agent = a.agent?.takeIf { it.isNotBlank() } ?: "agent"
        return "$agent 在 $hostName 等待回答：${a.title() ?: a.cwd ?: "请查看会话"}"
    }

    private fun currentCmd(): String = SNAPSHOT_CMD_CANDIDATES[cmdIndex]

    private fun HerdrAgentInfo.title(): String? = terminalTitleStripped ?: terminalTitle

    private fun jitter(base: Long): Long = (base * (0.8 + Random.nextDouble() * 0.4)).toLong()
}
