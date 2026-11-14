package dev.macodes.herdr

/**
 * Agent state machine: snapshot diff → state migration event.
 *
 * Purely functional, no I/O, no UI—take a list of agents from each round’s snapshot as input, and output the difference compared to the previous round’s
 * State migration events. Entering or leaving the “blocked” state triggers notifications and UI badges.
 *
 * Evaluation criteria: the `agents` array (herdr has already performed agent detection), tracked by `pane_id`.
 */
sealed interface HerdrAgentEvent {
    /** Agent status: Blocked (waits for user response/approval). */
    data class Blocked(
        val paneId: String,
        val agent: String?,
        val title: String?,
        val cwd: String?,
    ) : HerdrAgentEvent

    /** Agent status changed from "blocked" (user replied/task resumed). */
    data class Unblocked(
        val paneId: String,
        val agent: String?,
    ) : HerdrAgentEvent

    /** The agent has started working. */
    data class Working(
        val paneId: String,
        val agent: String?,
    ) : HerdrAgentEvent

    /** Change “agent” to “idle” (including fallback displays with “done” semantics). */
    data class Idle(
        val paneId: String,
        val agent: String?,
    ) : HerdrAgentEvent
}

class HerdrAgentStateMachine {
    /** pane_id → Status in the previous snapshot (cross-round tracking). */
    private val prev = HashMap<String, HerdrAgentStatus>()

    /** Snapshot of the current state of each pane (for UI reading/testing assertions). */
    fun current(): Map<String, HerdrAgentStatus> = prev.toMap()

    /** Full reset (called after a monitor restart or reconnect to prevent pre-restart states from being treated as migrations). */
    fun reset() {
        prev.clear()
    }

    /**
     * Feed one round of snapshots; returns the state-migration events relative to the previous round.
     * Repeated occurrences of the same state do not generate events (the natural basis for polling debouncing).
     */
    fun update(snapshot: HerdrSessionSnapshot): List<HerdrAgentEvent> {
        val events = ArrayList<HerdrAgentEvent>()
        val cur = snapshot.agents.associate { it.paneId to it.agentStatus }

        for (a in snapshot.agents) {
            val before = prev[a.paneId]
            if (before == a.agentStatus) continue
            events += when (a.agentStatus) {
                HerdrAgentStatus.BLOCKED ->
                    HerdrAgentEvent.Blocked(
                        a.paneId,
                        a.agent,
                        a.terminalTitleStripped ?: a.terminalTitle,
                        a.cwd,
                    )
                HerdrAgentStatus.WORKING -> HerdrAgentEvent.Working(a.paneId, a.agent)
                HerdrAgentStatus.IDLE -> HerdrAgentEvent.Idle(a.paneId, a.agent)
                else -> null
            } ?: continue
            // blocked → not blocked: Exits the waiting state (approve completed/timeout/closed)
            if (before == HerdrAgentStatus.BLOCKED && a.agentStatus != HerdrAgentStatus.BLOCKED) {
                events += HerdrAgentEvent.Unblocked(a.paneId, a.agent)
            }
        }

        // Pane missing from the snapshot (session/workspace closed): If previously marked as "blocked," add "Unblocked,"
        // Otherwise, the notification will remain pending (your phone will keep thinking the agent is waiting for you).
        prev.keys.filter { it !in cur }.forEach { gone ->
            if (prev[gone] == HerdrAgentStatus.BLOCKED) {
                events += HerdrAgentEvent.Unblocked(gone, null)
            }
            prev.remove(gone)
        }
        prev.putAll(cur)
        return events
    }
}
