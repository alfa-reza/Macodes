package dev.termish.ui

import dev.termish.data.Host
import kotlin.test.Test
import kotlin.test.assertEquals

/** Terminal screen session tab title logic: Give priority to custom names; if no name is provided, fall back to `user@host`. */
class TerminalScreenTest {
    private fun host(
        name: String,
        hostname: String,
        username: String = "root",
    ) = Host(
        id = "h1",
        name = name,
        hostname = hostname,
        username = username,
    )

    @Test
    fun customNameWins() {
        assertEquals("我的服务器", sessionTabTitle(host("我的服务器", "192.168.1.10")))
        assertEquals("web-prod", sessionTabTitle(host("web-prod", "prod.example.com")))
    }

    @Test
    fun unnamedFallsBackToUserAtHost() {
        // Unnamed: When saving, "name" reverts to "hostname" → shows "user@host" (provides more info)
        assertEquals("root@192.168.1.10", sessionTabTitle(host("192.168.1.10", "192.168.1.10")))
        assertEquals("alice@nas.local", sessionTabTitle(host("nas.local", "nas.local", username = "alice")))
    }

    @Test
    fun nameEqualToHostnameButDifferentUserShowsUserAtHost() {
        // If the name matches the hostname but the user is different: "name" is treated as unnamed and still shows "user@host"
        assertEquals("root@devbox", sessionTabTitle(host("devbox", "devbox")))
    }

    @Test
    fun blankNameFallsBack() {
        assertEquals("root@server", sessionTabTitle(host("", "server")))
    }

    // ---------- Session ID on the same host (current tab list position: 1, 2, 3… Deleting will automatically reorder) ----------

    @Test
    fun seqShownWhenMultipleSessions() {
        val h = host("我的服务器", "192.168.1.10")
        assertEquals("我的服务器 (1)", sessionTabTitle(h, seq = 1, showSeq = true))
        assertEquals("我的服务器 (2)", sessionTabTitle(h, seq = 2, showSeq = true))
    }

    @Test
    fun seqHiddenForSingleSession() {
        // Do not show parentheses when there is only one session on the host (default parameter)
        assertEquals("我的服务器", sessionTabTitle(host("我的服务器", "192.168.1.10"), seq = 1))
        assertEquals("root@server", sessionTabTitle(host("", "server"), seq = 3))
    }

    @Test
    fun seqAppendsAfterFallbackTitle() {
        val h = host("nas.local", "nas.local", username = "alice")
        assertEquals("alice@nas.local (4)", sessionTabTitle(h, seq = 4, showSeq = true))
    }
}
