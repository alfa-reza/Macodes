package dev.termish.mosh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** MoshTransport timing/send path regression: nowMs injection, sendDatagram bypass catch. */
class TransportTest {
    private class Harness {
        var now = 0L
        val sent = ArrayList<ByteArray>()
        var states = 0
        val transport =
            MoshTransport(
                ipv6Path = false,
                initialCols = 80,
                initialRows = 24,
                key =
                    dev.termish.util
                        .base64Encode(ByteArray(16))
                        .substring(0, 22),
                nowMs = { now },
                sendDatagram = {
                    sent.add(it)
                    SendResult.OK
                },
                onNewState = { states++ },
            )
    }

    @Test
    fun rationalizeWithUnackedPrefixDoesNotThrow() {
        // Regression: If `front` is not acknowledged and `current` has ≥2 differing bytes, `rationalizeStates`
        // `rationalizeStates` held a reference and decremented it anyway, causing a “prefix mismatch” that crashed the session.
        val h = Harness()
        h.transport.pushBytes("abc123".encodeToByteArray())
        h.now += 100
        h.transport.tick()
        h.now += 100
        h.transport.tick() // Second rationalize (front has already been decremented once)
        h.now += 5000
        h.transport.tick()
        assertTrue(h.sent.isNotEmpty())
    }

    @Test
    fun emptyAckHeartbeat() {
        val h = Harness()
        h.transport.tick() // Initial immediate ack
        val initial = h.sent.size
        h.now += 3500
        h.transport.tick()
        assertTrue(h.sent.size > initial) // there is a heartbeat empty-ack after 3s
    }

    @Test
    fun inputTriggersFragmentedSend() {
        val h = Harness()
        h.transport.tick()
        val before = h.sent.size
        h.transport.pushBytes(ByteArray(2000) { (it % 256).toByte() })
        h.now += 300 // Exceeded the sendInterval limit of 250 ms (frame rate limiting is normal behavior for mosh)
        h.transport.tick() // Record mindelayClock
        h.now += 20 // Exceeded mindelay (8 ms)
        h.transport.tick()
        assertTrue(h.sent.size > before) // User input generates a new datagram (including fragmentation)
    }

    @Test
    fun serverShutdownIsAckedWithMaxNum() {
        // Server shutdown (new_num = ULLONG_MAX): The client should send an accelerated acknowledgment with ack_num=-1,
        // Afterward, `counterpartyShutdownAckSent()` returns `true` (one of the conditions for a clean exit from Mosh).
        val h = Harness()
        h.transport.tick() // Initial handshake ack
        val inst =
            TransportInstruction(
                protocolVersion = 2,
                oldNum = 0u,
                newNum = ULong.MAX_VALUE,
                ackNum = 0u,
                throwawayNum = 0u,
                diff = ByteArray(0),
                chaff = ByteArray(0),
            )
        val payload = Fragmenter().makeFragments(inst, 400).first().toBytes()
        h.transport.processPacket(MoshCryptoSession.PlainPacket(1u, 0, 0xffff, payload))
        assertFalse(h.transport.counterpartyShutdownAckSent())
        h.now += 5000 // Exceeding ACK_INTERVAL while ack_num == -1 will trigger acceleration.
        h.transport.tick()
        assertTrue(h.transport.counterpartyShutdownAckSent())
    }

    @Test
    fun emptyDiffStateDoesNotNotifyRenderer() {
        // An empty diff (pure ack/heartbeat) should not trigger onNewState: since the content has not changed, this avoids an unnecessary full UI copy.
        val h = Harness()
        val inst =
            TransportInstruction(
                protocolVersion = 2,
                oldNum = 0u,
                newNum = 1u,
                ackNum = 0u,
                throwawayNum = 0u,
                diff = ByteArray(0),
                chaff = ByteArray(0),
            )
        val payload = Fragmenter().makeFragments(inst, 400).first().toBytes()
        h.transport.processPacket(MoshCryptoSession.PlainPacket(1u, 0, 0xffff, payload))
        assertEquals(0, h.states)
    }
}
