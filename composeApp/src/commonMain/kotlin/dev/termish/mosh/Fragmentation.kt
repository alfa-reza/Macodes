package dev.termish.mosh

/**
 * SSP fragmentation layer: Fragment wire format = 8B instruction id || 2B (final<<15 | num) || content.
 * The wire format is consistent with mosh's SSP fragmentation protocol (required for interoperability) and is implemented as a standalone Kotlin rewrite.
 */
internal class Fragment(
    val id: ULong,
    val num: Int,
    val final: Boolean,
    val contents: ByteArray,
) {
    fun toBytes(): ByteArray {
        val out = ByteArray(10 + contents.size)
        for (i in 0..7) out[i] = (id shr (56 - 8 * i)).toByte()
        val combined = (if (final) 0x8000 else 0) or num
        out[8] = (combined shr 8).toByte()
        out[9] = combined.toByte()
        contents.copyInto(out, 10)
        return out
    }

    companion object {
        const val HEADER_LEN = 10

        fun parse(data: ByteArray): Fragment {
            require(data.size >= HEADER_LEN)
            val id = (0..7).fold(0uL) { acc, i -> (acc shl 8) or (data[i].toInt() and 0xff).toULong() }
            val combined = ((data[8].toInt() and 0xff) shl 8) or (data[9].toInt() and 0xff)
            return Fragment(
                id = id,
                num = combined and 0x7fff,
                final = combined and 0x8000 != 0,
                contents = data.copyOfRange(HEADER_LEN, data.size),
            )
        }
    }
}

/** Receiver-side reassembly: after collecting all fragments with the same id, decompress them and restore the Instruction. */
internal class FragmentAssembly {
    private var fragments = arrayOfNulls<Fragment>(0)
    private var currentId: ULong? = null
    private var arrived = 0
    private var total = -1

    /** Set this bit when a new id overrides an unfinished packet (assembly abandoned due to packet loss; for upper-layer frame-loss statistics; cleared on completion). */
    var lastAbandoned = false
        private set

    fun addFragment(frag: Fragment): Boolean {
        if (currentId != frag.id) {
            lastAbandoned = currentId != null && (total == -1 || arrived < total)
            fragments = arrayOfNulls(frag.num + 1)
            fragments[frag.num] = frag
            arrived = 1
            total = -1
            currentId = frag.id
        } else {
            lastAbandoned = false
            if (fragments.size > frag.num && fragments[frag.num] != null) {
                // Ignore duplicate fragmentation
            } else {
                if (fragments.size < frag.num + 1) {
                    fragments = fragments.copyOf(frag.num + 1)
                }
                fragments[frag.num] = frag
                arrived++
            }
        }
        if (frag.final) {
            total = frag.num + 1
            if (fragments.size > total) fragments = fragments.copyOf(total)
        }
        return total != -1 && arrived == total
    }

    fun assembly(): TransportInstruction = TransportInstruction.parse(assembleBytes())

    /** General: Returns the original bytes after reassembly and decompression (used for non-SSP payloads such as video streams; the caller is responsible for parsing them). */
    fun assembleBytes(): ByteArray {
        val encoded = fragments.joinToByteArray()
        fragments = arrayOfNulls(0)
        arrived = 0
        total = -1
        return zlibDecompress(encoded)
    }

    private fun Array<Fragment?>.joinToByteArray(): ByteArray {
        val parts = map { it!!.contents }
        val out = ByteArray(parts.sumOf { it.size })
        var off = 0
        for (p in parts) {
            p.copyInto(out, off)
            off += p.size
        }
        return out
    }
}

/** Fragmentation: SSP reuses instruction IDs with the same header; general payloads use a new ID each time. Content is fragmented according to MTU. */
internal class Fragmenter {
    private var nextInstructionId = 0uL
    private var lastInstruction: TransportInstruction? = null
    private var lastMtu = -1

    /** SSP-specific: TransportInstruction fragmentation (reuse instruction IDs from the header; deduplicate on resend). */
    fun makeFragments(
        inst: TransportInstruction,
        mtu: Int,
    ): List<Fragment> {
        val usable = mtu - Fragment.HEADER_LEN
        val last = lastInstruction
        // SSP Semantics: Re-submissions of "old/new" must include the same diff (to prevent content drift in re-submissions).
        if (last != null && inst.oldNum == last.oldNum && inst.newNum == last.newNum) {
            check(inst.diff.contentEquals(last.diff)) { "同 old/new 的 diff 不一致" }
        }
        if (last == null || !inst.sameHeaderAs(last) || lastMtu != usable) {
            nextInstructionId++
        }
        lastInstruction = inst
        lastMtu = usable
        return sliceToFragments(inst.serialize(), mtu, nextInstructionId)
    }

    /** General: Any payload fragmentation (each packet is allocated a new instruction ID; no SSP reuse semantics). */
    fun makeFragments(
        payload: ByteArray,
        mtu: Int,
    ): List<Fragment> {
        nextInstructionId++
        return sliceToFragments(payload, mtu, nextInstructionId)
    }

    /** Compress + slice by MTU (common logic). */
    private fun sliceToFragments(
        payload: ByteArray,
        mtu: Int,
        id: ULong,
    ): List<Fragment> {
        val usable = mtu - Fragment.HEADER_LEN
        val compressed = zlibCompress(payload)
        val out = ArrayList<Fragment>()
        var num = 0
        var off = 0
        while (off < compressed.size) {
            check(num < 0x8000) { "分片数超限（fragment_num 高位是 final 标志位）" }
            val end = minOf(off + usable, compressed.size)
            out.add(Fragment(id, num++, end == compressed.size, compressed.copyOfRange(off, end)))
            off = end
        }
        return out
    }
}
