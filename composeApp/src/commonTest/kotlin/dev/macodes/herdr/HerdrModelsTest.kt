package dev.macodes.herdr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A simplified fixture that matches the structure of the actual `herdr api snapshot` output (see the actual snapshot in the androidUnitTest resources). */
private val SNAPSHOT_JSON =
    """
{"id":"cli:api:snapshot","result":{"snapshot":{
  "version":"0.8.0","protocol":19,
  "workspaces":[{"workspace_id":"w1","label":"dev","pane_count":2,"tab_count":2,"focused":true,"agent_status":"working","number":1,"active_tab_id":"w1:t1"}],
  "tabs":[{"tab_id":"w1:t1","workspace_id":"w1","label":"main","pane_count":1,"number":1,"focused":true,"agent_status":"idle"},
          {"tab_id":"w1:t2","workspace_id":"w1","label":"logs","pane_count":1,"number":2,"focused":false,"agent_status":"blocked"}],
  "panes":[{"pane_id":"w1:p1","terminal_id":"term_a","workspace_id":"w1","tab_id":"w1:t1","focused":true,"agent":"pi","agent_status":"idle",
            "terminal_title":"pi - dev","terminal_title_stripped":"pi - dev","cwd":"/repo","revision":3},
           {"pane_id":"w1:p2","terminal_id":"term_b","workspace_id":"w1","tab_id":"w1:t2","focused":false,"agent":"codex","agent_status":"blocked",
            "terminal_title":"codex - refactor auth","terminal_title_stripped":"codex - refactor auth","cwd":"/repo","revision":9}],
  "agents":[{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"w1:t1","agent":"pi","agent_status":"idle",
             "terminal_title":"pi - dev","terminal_title_stripped":"pi - dev","cwd":"/repo","focused":true,"state_change_seq":10,"revision":3},
            {"pane_id":"w1:p2","workspace_id":"w1","tab_id":"w1:t2","agent":"codex","agent_status":"blocked",
             "terminal_title":"codex - refactor auth","terminal_title_stripped":"codex - refactor auth","cwd":"/repo","focused":false,"state_change_seq":42,"revision":9}],
  "layouts":[],"focused_workspace_id":"w1","focused_tab_id":"w1:t1","focused_pane_id":"w1:p1"
}}}
    """.trimIndent()

class HerdrModelsTest {
    @Test
    fun parseSnapshotExtractsAgents() {
        val s = parseHerdrSnapshot(SNAPSHOT_JSON)
        assertNotNull(s, "合法快照应解析成功")
        assertEquals(19L, s.protocol)
        assertEquals(2, s.agents.size)

        val blocked = s.agents.first { it.paneId == "w1:p2" }
        assertEquals(HerdrAgentStatus.BLOCKED, blocked.agentStatus)
        assertEquals("codex", blocked.agent)
        assertEquals("codex - refactor auth", blocked.terminalTitleStripped)
        assertEquals("/repo", blocked.cwd)
        assertEquals(42L, blocked.stateChangeSeq)
    }

    @Test
    fun parseSnapshotToleratesUnknownFields() {
        // herdr upgrades may add fields: parsing must not fail under ignoreUnknownKeys.
        val withExtra =
            SNAPSHOT_JSON.replace(
                "\"pane_id\":\"w1:p2\"",
                "\"pane_id\":\"w1:p2\",\"future_field\":{\"nested\":[1,2,3]}",
            )
        assertNotNull(parseHerdrSnapshot(withExtra))
    }

    @Test
    fun parseSnapshotReturnsNullOnGarbage() {
        assertNull(parseHerdrSnapshot("not json at all"))
        assertNull(parseHerdrSnapshot(""))
        assertNull(parseHerdrSnapshot("{\"id\":\"x\",\"error\":{\"code\":\"not_found\"}}"))
    }

    @Test
    fun parseSnapshotToleratesMissingOptionalFields() {
        // Only the smallest pane containing `pane_id` and `agent_status` (when the `herdr` output fields are incomplete)
        val minimal =
            """
            {"id":"cli:api:snapshot","result":{"snapshot":{"version":"0.8.0","protocol":19,
              "workspaces":[],"tabs":[],"panes":[],"agents":[{"pane_id":"w1:p1","agent_status":"working"}],"layouts":[]}}}
            """.trimIndent()
        val s = parseHerdrSnapshot(minimal)
        assertNotNull(s)
        assertEquals(1, s.agents.size)
        assertEquals(HerdrAgentStatus.WORKING, s.agents[0].agentStatus)
        assertEquals("", s.agents[0].workspaceId)
    }
}

class HerdrAgentStateMachineTest {
    private fun snapshot(vararg statuses: Pair<String, HerdrAgentStatus>): HerdrSessionSnapshot =
        HerdrSessionSnapshot(
            agents =
                statuses.map { (pane, st) ->
                    HerdrAgentInfo(paneId = pane, agent = "pi", agentStatus = st)
                },
        )

    @Test
    fun firstUpdateEmitsCurrentStatuses() {
        val m = HerdrAgentStateMachine()
        val events = m.update(snapshot("w1:p1" to HerdrAgentStatus.IDLE, "w1:p2" to HerdrAgentStatus.WORKING))
        assertEquals(2, events.size)
        assertIs<HerdrAgentEvent.Idle>(events[0])
        assertIs<HerdrAgentEvent.Working>(events[1])
        assertEquals(HerdrAgentStatus.WORKING, m.current()["w1:p2"])
    }

    @Test
    fun unchangedStatusEmitsNothing() {
        val m = HerdrAgentStateMachine()
        m.update(snapshot("w1:p1" to HerdrAgentStatus.WORKING))
        assertTrue(m.update(snapshot("w1:p1" to HerdrAgentStatus.WORKING)).isEmpty(), "状态不变不应产生事件")
    }

    @Test
    fun blockedTransitionEmitsBlockedThenUnblocked() {
        val m = HerdrAgentStateMachine()
        m.update(snapshot("w1:p1" to HerdrAgentStatus.WORKING))

        val blocked = m.update(snapshot("w1:p1" to HerdrAgentStatus.BLOCKED))
        assertEquals(1, blocked.size)
        val b = assertIs<HerdrAgentEvent.Blocked>(blocked[0])
        assertEquals("w1:p1", b.paneId)

        val unblocked = m.update(snapshot("w1:p1" to HerdrAgentStatus.WORKING))
        assertIs<HerdrAgentEvent.Working>(unblocked[0])
        assertIs<HerdrAgentEvent.Unblocked>(unblocked[1])
    }

    @Test
    fun paneDisappearingWhileBlockedEmitsUnblocked() {
        val m = HerdrAgentStateMachine()
        m.update(snapshot("w1:p1" to HerdrAgentStatus.BLOCKED))
        val events = m.update(snapshot()) // The pane disappears in the snapshot (session closed)
        assertEquals(1, events.size)
        val u = assertIs<HerdrAgentEvent.Unblocked>(events[0])
        assertEquals("w1:p1", u.paneId)
        assertTrue(m.current().isEmpty())
    }

    @Test
    fun resetClearsState() {
        val m = HerdrAgentStateMachine()
        m.update(snapshot("w1:p1" to HerdrAgentStatus.BLOCKED))
        m.reset()
        // After resetting, if fed the same state again: treat it as the first time (send "Blocked"); do not use the old state to suppress the event.
        val events = m.update(snapshot("w1:p1" to HerdrAgentStatus.BLOCKED))
        assertIs<HerdrAgentEvent.Blocked>(events.single())
    }
}
