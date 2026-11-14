package dev.macodes.mosh

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SspTest {
    // ---- Proto / Message ----

    @Test
    fun transportInstructionRoundTrip() {
        val inst =
            TransportInstruction(
                protocolVersion = 2,
                oldNum = 3u,
                newNum = 7u,
                ackNum = 11u,
                throwawayNum = 1u,
                diff = byteArrayOf(1, 2, 3, -1, 0),
                chaff = byteArrayOf(9, 9),
            )
        val parsed = TransportInstruction.parse(inst.serialize())
        assertEquals(2, parsed.protocolVersion)
        assertEquals(3uL, parsed.oldNum)
        assertEquals(7uL, parsed.newNum)
        assertEquals(11uL, parsed.ackNum)
        assertEquals(1uL, parsed.throwawayNum)
        assertContentEquals(inst.diff, parsed.diff)
        assertContentEquals(inst.chaff, parsed.chaff)
    }

    @Test
    fun userMessageKeystrokeMerge() {
        // Merge adjacent keystrokes; resize forms its own entry
        val bytes =
            encodeUserMessage(
                listOf(
                    UserEventOut.Keystrokes(byteArrayOf('a'.code.toByte())),
                    UserEventOut.Keystrokes(byteArrayOf('b'.code.toByte())),
                    UserEventOut.Resize(120, 40),
                ),
            )
        // Parse and verify directly in wire format: UserMessage { 2 instructions }
        val r = ProtoReader(bytes)
        var count = 0
        while (r.hasMore) {
            val (f, w) = r.nextTag()!!
            assertEquals(1, f)
            val inst = ProtoReader(r.readBytes())
            val (ef, _) = inst.nextTag()!!
            when (count) {
                0 -> assertEquals(2, ef) // keystroke extension
                1 -> assertEquals(3, ef) // resize extension
            }
            assertEquals(w, 2)
            count++
        }
        assertEquals(2, count)
    }

    @Test
    fun hostMessageDecode() {
        // Manually construct HostMessage: instruction { hostbytes { hoststring: "hi" } }, instruction { echoack { 8: 42 } }
        val hb = ProtoWriter()
        hb.bytes(4, "hi".encodeToByteArray())
        val inst1 = ProtoWriter()
        inst1.message(2, hb)
        val ea = ProtoWriter()
        ea.varint(8, 42u)
        val inst2 = ProtoWriter()
        inst2.message(7, ea)
        val outer = ProtoWriter()
        outer.message(1, inst1)
        outer.message(1, inst2)

        val events = decodeHostMessage(outer.toByteArray())
        assertEquals(2, events.size)
        assertContentEquals("hi".encodeToByteArray(), (events[0] as HostEventIn.HostBytes).bytes)
        assertEquals(42uL, (events[1] as HostEventIn.EchoAck).echoAckNum)
    }

    // ---- UserStream ----

    @Test
    fun userStreamDiffAndSubtract() {
        val base = UserStream()
        "ls -l\n".encodeToByteArray().forEach { base.pushByte(it) }
        val cur = base.copy()
        cur.pushByte('x'.code.toByte())
        cur.pushResize(100, 30)

        val diff = cur.diffFrom(base)
        assertTrue(diff.isNotEmpty())
        // Empty diff: Same state
        assertEquals(0, cur.diffFrom(cur).size)

        // After "subtract," "cur" is left with only the suffix
        cur.subtract(base)
        val diff2 = cur.diffFrom(UserStream())
        assertContentEquals(diff, diff2)
    }

    // ---- Fragmentation ----

    @Test
    fun fragmentRoundTripAndAssembly() {
        val fragmenter = Fragmenter()
        val big =
            TransportInstruction(
                oldNum = 0u,
                newNum = 1u,
                ackNum = 0u,
                throwawayNum = 0u,
                diff = kotlin.random.Random(42).nextBytes(5000), // Fixed seed, random data; prevent zlib from compressing into a single chunk
            )
        val frags = fragmenter.makeFragments(big, 400)
        assertTrue(frags.size > 3)
        val asm = FragmentAssembly()
        var done = false
        // Even if the order is scrambled, it should still be possible for reassembly to occur (items with the same ID should be placed in numerical order).
        for (f in frags.reversed()) {
            done = asm.addFragment(f)
        }
        assertTrue(done)
        val restored = asm.assembly()
        assertEquals(1uL, restored.newNum)
        assertContentEquals(big.diff, restored.diff)
    }

    @Test
    fun fragmenterIncrementsIdOnHeaderChange() {
        val f = Fragmenter()
        val a = TransportInstruction(oldNum = 0u, newNum = 1u)
        val id1 = f.makeFragments(a, 400).first().id
        val id2 = f.makeFragments(a, 400).first().id // Same as header → Same as id
        assertEquals(id1, id2)
        val b = TransportInstruction(oldNum = 0u, newNum = 2u)
        val id3 = f.makeFragments(b, 400).first().id
        assertTrue(id3 > id1)
    }

    // ---- Crypto session ----

    @Test
    fun cryptoSessionRoundTrip() {
        val keyRaw = ByteArray(16) { it.toByte() }
        val key =
            dev.macodes.util
                .base64Encode(keyRaw)
                .substring(0, 22)
        val sender = MoshCryptoSession(key)
        val serverOcb = Ocb(keyRaw) // Server-side equivalent primitives (encryption with direction bit set to 1)
        val payload = "fragment-bytes".encodeToByteArray()

        // Client → Server (direction bit 0): Production format 8B seq || ciphertext || 16B tag
        val toServer = sender.encrypt(5uL, 1234, 5678, payload)
        assertEquals(5, toServer[7].toInt())
        val ts = byteArrayOf(0x04, 0xd2.toByte(), 0x16, 0x2e.toByte())
        val expectedCt = serverOcb.encrypt(nonceBytes(5uL), ts + payload)
        assertTrue(
            toServer.copyOfRange(8, toServer.size).contentEquals(expectedCt),
            "客户端密文与服务器侧不一致\nclient=${toServer.copyOfRange(8, toServer.size).toHex()}\nserver=${expectedCt.toHex()}",
        )
        val serverPlain = serverOcb.decrypt(nonceBytes(5uL), toServer.copyOfRange(8, toServer.size))
        assertNotNull(serverPlain)
        assertEquals(4 + payload.size, serverPlain.size)

        // Server → Client (direction = 1): The client must be able to parse
        val serverSeq = (1uL shl 63) or 5uL
        val serverCt = serverOcb.encrypt(nonceBytes(serverSeq), ts + payload)
        val toClient = ByteArray(8 + serverCt.size)
        for (i in 0..7) toClient[i] = (serverSeq shr (56 - 8 * i)).toByte()
        serverCt.copyInto(toClient, 8)
        val pkt = sender.decrypt(toClient)
        assertNotNull(pkt)
        assertEquals(5uL, pkt.seq) // The direction indicator is masked, followed by the number.
        assertEquals(1234, pkt.timestamp) // 0x04d2
        assertEquals(5678, pkt.timestampReply) // 0x162e
        assertContentEquals(payload, pkt.payload)
    }

    private fun nonceBytes(seqWithDir: ULong): ByteArray {
        val n = ByteArray(12)
        for (i in 0..7) n[4 + i] = (seqWithDir shr (56 - 8 * i)).toByte()
        return n
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun cryptoSessionRejectsTamper() {
        val key =
            dev.macodes.util
                .base64Encode(ByteArray(16) { (it * 3).toByte() })
                .substring(0, 22)
        val s = MoshCryptoSession(key)
        val dg = s.encrypt(0uL, 0, 0xffff, byteArrayOf(1, 2, 3))
        dg[10] = (dg[10].toInt() xor 0x40).toByte()
        assertNull(s.decrypt(dg))
        assertNull(s.decrypt(dg.copyOf(10))) // truncation
    }

    @Test
    fun cryptoSessionRejectsClientDirectionReplay() {
        // A direction of 0 (TO_SERVER) indicates a packet sent by the client itself; such packets must be rejected when replayed.
        val key =
            dev.macodes.util
                .base64Encode(ByteArray(16) { it.toByte() })
                .substring(0, 22)
        val s = MoshCryptoSession(key)
        val dg = s.encrypt(0uL, 0, 0xffff, byteArrayOf(1, 2, 3))
        assertNull(s.decrypt(dg))
    }

    @Test
    fun cryptoSessionValidatesKeyEncoding() {
        val canonical =
            dev.macodes.util
                .base64Encode(ByteArray(16))
                .substring(0, 22)
        // 21 instances of A + B: 22 characters, but the last 4 bits are non-zero; mosh Base64Key will reject this.
        val nonCanonical = "AAAAAAAAAAAAAAAAAAAAAB"
        assertFailsWith<IllegalArgumentException> { MoshCryptoSession(nonCanonical) }
        assertFailsWith<IllegalArgumentException> { MoshCryptoSession(canonical + "A") } // Length
        MoshCryptoSession(canonical) // Canonical key: normal
    }
}
