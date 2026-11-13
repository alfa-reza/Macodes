package dev.termish.screen

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit test for semi-reliable fragmentation of screen streaming (Solution A streaming prototype):
 * Verify round-trip, out-of-order, duplicate fragments, packet loss and discard, and consecutive multiple packets after reusing the Mosh fragment format.
 */
class ScreenStreamTransportTest {
    private val mtu = 400

    @Test
    fun roundTripSinglePacketOutOfOrder() {
        val datagrams = ArrayList<ByteArray>()
        val sender = ScreenStreamSender { datagrams.add(it) }
        val receiver = ScreenStreamReceiver()

        // Use a fixed seed for random data to prevent zlib from compressing the content into a single block
        val payload = Random(42).nextBytes(5000)
        sender.send(payload, mtu)
        assertTrue(datagrams.size > 3, "5000B @ mtu=$mtu 应切成多片，实际 ${datagrams.size}")

        var result: ByteArray? = null
        // Randomized input: Elements with the same ID are sorted by num; reassembly should still be possible.
        for (dg in datagrams.reversed()) {
            receiver.onDatagram(dg)?.let { result = it }
        }
        assertNotNull(result, "乱序收齐后应重组出完整包")
        assertContentEquals(payload, result)
    }

    @Test
    fun duplicateFragmentIsIgnored() {
        val datagrams = ArrayList<ByteArray>()
        val sender = ScreenStreamSender { datagrams.add(it) }
        val receiver = ScreenStreamReceiver()

        val payload = Random(7).nextBytes(2000)
        sender.send(payload, mtu)

        // The first segment is fed twice; the rest are normal. Fragmentation should be ignored without disrupting the reassembly.
        var result: ByteArray? = null
        receiver.onDatagram(datagrams[0])
        receiver.onDatagram(datagrams[0]) // Duplicate
        for (dg in datagrams.drop(1)) {
            receiver.onDatagram(dg)?.let { result = it }
        }
        assertNotNull(result)
        assertContentEquals(payload, result)
    }

    @Test
    fun droppedMiddleFragmentDiscardsWholePacketAndRecovers() {
        val datagrams = ArrayList<ByteArray>()
        val sender = ScreenStreamSender { datagrams.add(it) }
        val receiver = ScreenStreamReceiver()

        // First packet: one segment missing in the middle (num=1) → always incomplete (semi-reliable: discard the entire packet)
        val p1 = Random(11).nextBytes(3000)
        sender.send(p1, mtu)
        assertTrue(datagrams.size > 3)
        for ((i, dg) in datagrams.withIndex()) {
            if (i == 1) continue // dropped: num=1
            assertNull(receiver.onDatagram(dg), "丢片后不应重组出包")
        }

        // Second batch: Same sender (id increments), complete input → new id triggers reset, normal reassembly
        val p2 = Random(13).nextBytes(3000)
        val before = datagrams.size
        sender.send(p2, mtu)
        var result: ByteArray? = null
        for (dg in datagrams.subList(before, datagrams.size)) {
            receiver.onDatagram(dg)?.let { result = it }
        }
        assertNotNull(result, "下一包应能正常重组（丢包自动恢复）")
        assertContentEquals(p2, result)
    }

    @Test
    fun consecutivePacketsReassembleInOrder() {
        val datagrams = ArrayList<ByteArray>()
        val sender = ScreenStreamSender { datagrams.add(it) }
        val receiver = ScreenStreamReceiver()

        val packets = listOf(Random(1).nextBytes(800), Random(2).nextBytes(1500), Random(3).nextBytes(3000))
        val results = ArrayList<ByteArray>()
        for (p in packets) {
            val before = datagrams.size
            sender.send(p, mtu)
            // Feed in package by package (naturally toggling at the new ID bounds for each package)
            for (dg in datagrams.subList(before, datagrams.size)) {
                receiver.onDatagram(dg)?.let { results.add(it) }
            }
        }
        assertEquals(3, results.size, "三个包应各重组一次")
        packets.forEachIndexed { i, p -> assertContentEquals(p, results[i]) }
    }

    @Test
    fun tinyPayloadIsSingleFragment() {
        val datagrams = ArrayList<ByteArray>()
        val sender = ScreenStreamSender { datagrams.add(it) }
        val receiver = ScreenStreamReceiver()

        val payload = byteArrayOf(0x01, 0x02, 0x03)
        sender.send(payload, mtu)
        assertEquals(1, datagrams.size, "小 payload 应是单片")

        val result = receiver.onDatagram(datagrams[0])
        assertNotNull(result)
        assertContentEquals(payload, result)
    }

    @Test
    fun garbageDatagramIsIgnored() {
        val receiver = ScreenStreamReceiver()
        assertNull(receiver.onDatagram(ByteArray(3) { 0x00 }), "非法分片应返回 null 不抛异常")
    }
}
