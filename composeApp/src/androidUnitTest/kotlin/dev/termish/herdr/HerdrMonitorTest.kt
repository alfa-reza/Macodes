package dev.termish.herdr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real herdr snapshot parse test: fixture captured from herdr 0.8.0 running on the local machine
 * (Output `herdr api snapshot` as-is; see resources/herdr-snapshot.json),
 * Ensure alignment of the model with the actual protocol structure (to prevent inconsistencies in fixture self-consistency).
 */
class HerdrSnapshotRealDataTest {
    private fun realSnapshot(): HerdrSessionSnapshot {
        val raw =
            HerdrSnapshotRealDataTest::class.java
                .getResourceAsStream("/herdr-snapshot.json")
                ?.readBytes()
                ?.decodeToString()
                ?: error("fixture 缺失: androidUnitTest resources 里应有 herdr-snapshot.json（herdr api snapshot 原样输出）")
        return assertNotNull(parseHerdrSnapshot(raw), "真实 herdr 快照应解析成功")
    }

    @Test
    fun realSnapshotParses() {
        val s = realSnapshot()
        assertTrue(s.protocol > 0, "protocol 应非零（真实协议版本）")
        assertTrue(s.panes.isNotEmpty())
        assertEquals(
            s.agents.size,
            s.agents
                .map { it.paneId }
                .toSet()
                .size,
            "agent pane_id 应唯一",
        )
    }

    @Test
    fun realSnapshotAgentsHaveExpectedShape() {
        val s = realSnapshot()
        // Actual status at the time of capture: two Pi agents (idle + working)
        assertTrue(s.agents.isNotEmpty())
        for (a in s.agents) {
            assertNotNull(a.paneId)
            assertTrue(a.agentStatus != HerdrAgentStatus.UNKNOWN || a.agent == null)
        }
        println("real agents: ${s.agents.map { "${it.agent}/${it.agentStatus}/${it.paneId}" }}")
    }

    @Test
    fun stateMachineRunsOnRealData() {
        val s = realSnapshot()
        val m = HerdrAgentStateMachine()
        val events = m.update(s)
        assertEquals(s.agents.size, events.size, "首轮应为首状态事件")
        // Same snapshot fed again: no changes
        assertTrue(m.update(s).isEmpty())
        println("real events: ${events.map { it::class.simpleName }}")
    }
}

/**
 * HerdrMonitor polling test: kotlinx-coroutines-test virtual time-driven,
 * `fake runCommand` returns a controllable sequence of snapshots to verify polling calls, state event callbacks,
 * blocked: Confirm notification text and stop semantics.
 */
class HerdrMonitorTest {
    private fun snapshotWith(vararg statuses: Pair<String, HerdrAgentStatus>): String {
        val agents =
            statuses.joinToString(",") { (pane, st) ->
                """{"pane_id":"$pane","agent":"pi","agent_status":"${st.name.lowercase()}","terminal_title":"t-$pane"}"""
            }
        return """{"id":"cli:api:snapshot","result":{"snapshot":{"version":"0.8.0","protocol":19,"workspaces":[],"tabs":[],"panes":[],"agents":[$agents],"layouts":[]}}}"""
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun pollsAndEmitsEvents() =
        kotlinx.coroutines.test.runTest {
            val commands = mutableListOf<String>()
            val received = mutableListOf<List<HerdrAgentEvent>>()
            var round = 0
            val monitor =
                HerdrMonitor(
                    hostName = "nas",
                    hostId = "h1",
                    runCommand = { cmd ->
                        commands += cmd
                        round++
                        // Round 1: working; Round 2 and beyond: blocked
                        if (round == 1) {
                            snapshotWith("w1:p1" to HerdrAgentStatus.WORKING)
                        } else {
                            snapshotWith("w1:p1" to HerdrAgentStatus.BLOCKED)
                        }
                    },
                    scope = this,
                    pollIntervalMs = 1_000,
                    onEvents = { received += it },
                )
            monitor.start()
            testScheduler.advanceTimeBy(10_000)
            // Assertions before `stop`: `stop()` resets the state machine
            assertEquals(HerdrAgentStatus.BLOCKED, monitor.currentStatus()["w1:p1"])
            monitor.stop()

            assertTrue(commands.isNotEmpty(), "轮询应执行 herdr api snapshot")
            assertTrue(commands.all { it == "herdr api snapshot" })
            // Event Stream: First round marked as “Working,” followed by one round marked as “Blocked” (will not be resubmitted once the status stabilizes).
            val all = received.flatten()
            assertIs<HerdrAgentEvent.Working>(all.first())
            assertIs<HerdrAgentEvent.Blocked>(all[1])
            assertEquals(2, all.size, "状态稳定后不应重复发事件")
        }

    @Test
    fun blockedNotificationText() {
        val monitor =
            HerdrMonitor(
                "nas",
                "h1",
                { null },
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            )
        val a =
            HerdrAgentInfo(
                paneId = "w1:p1",
                agent = "codex",
                agentStatus = HerdrAgentStatus.BLOCKED,
                terminalTitleStripped = "codex - refactor auth",
                cwd = "/repo",
            )
        assertEquals("codex 在 nas 等待回答：codex - refactor auth", monitor.blockedNotificationText("nas", a))
        // Fallback for cases with no title or cwd
        val bare = HerdrAgentInfo(paneId = "w1:p1", agentStatus = HerdrAgentStatus.BLOCKED)
        assertEquals("agent 在 nas 等待回答：请查看会话", monitor.blockedNotificationText("nas", bare))
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun failureBackoffThenPause() =
        kotlinx.coroutines.test.runTest {
            val calls = mutableListOf<String>()
            val monitor =
                HerdrMonitor(
                    hostName = "nas",
                    hostId = "h1",
                    runCommand = { cmd ->
                        calls += cmd
                        null
                    }, // Always failure (herdr not installed)
                    scope = this,
                    pollIntervalMs = 1_000,
                )
            monitor.start()
            testScheduler.advanceTimeBy(120_000) // 2 minutes: 3 failures → Pause → Retry after resuming
            monitor.stop()
            // Complete within the first 3 backoff periods (3+6+12=21s); thereafter, pause every 60 seconds and retry 3 times (3+6+12).
            // Approximately 6+6=12 calls within 120 seconds, but under no circumstances should this be 120 calls at a rate of one per second (to prevent flooding).
            assertTrue(calls.size < 30, "连续失败必须退避+暂停，实际调用 ${calls.size} 次")
            assertTrue(calls.size >= 6, "暂停后应自动恢复轮询，实际调用 ${calls.size} 次")
        }
}
