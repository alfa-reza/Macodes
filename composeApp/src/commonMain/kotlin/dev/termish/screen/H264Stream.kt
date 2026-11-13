package dev.termish.screen

/**
 * H.264 Annex-B Byte Stream Parsing (Pure Kotlin, Zero Platform Dependencies):
 * - Split NAL units based on start code (00 00 01 / 00 00 00 01)
 * - Extract SPS (nal_type=7) / PPS (nal_type=8) — required for the CSD decoder
 * - Identify keyframes (IDR, nal_type=5) and frame bounds
 *
 * Remote (ffmpeg -f h264 -) has a raw Annex-B output: SPS/PPS periodic retransmission
 * (ffmpeg includes this by default before every keyframe), and the decoder can enter the stream at any point.
 */
object H264Stream {
    /** One NAL unit: type + payload (excluding start code). */
    data class Nal(
        val type: Int,
        val data: ByteArray,
    )

    /**
     * Extract complete NAL units from the byte stream; returns null if more data is needed.
     * Internal byte-array buffering (push stream reads 64KB per chunk, byte-by-byte boxing into an `ArrayList<Byte>`
     * Generates millions of garbage objects per second—GC pressure at 30 fps during streaming can cause frame stuttering).
     */
    class AnnexBParser {
        private var buf = ByteArray(4096)
        private var size = 0
        private var startCodeLen = 0

        /** Append data and attempt to extract the next NAL; returns null when empty (the remainder stays in the buffer; [drain] continues extracting). */
        fun push(bytes: ByteArray): Nal? {
            ensure(size + bytes.size)
            bytes.copyInto(buf, size)
            size += bytes.size
            return next()
        }

        /** Do not append data; continue extracting NALs from the existing buffer (the remaining buffer after extraction is reserved for the next data). */
        fun drain(): Nal? = next()

        private fun ensure(need: Int) {
            if (need <= buf.size) return
            var cap = buf.size
            while (cap < need) cap = cap * 2
            buf = buf.copyOf(cap)
        }

        /** Attempt to extract a NAL from the buffer; if insufficient data is available, return null (retain the buffer). */
        private fun next(): Nal? {
            // 1. Ensure the buffering begins with "start code" (discard stray bytes)
            while (true) {
                val idx = findStartCode(0)
                if (idx < 0) {
                    // No complete "start code": Retain up to 3 bytes at the end (which may be a "start code" prefix).
                    trimTail(3)
                    return null
                }
                if (idx > 0) {
                    buf.copyInto(buf, 0, idx, size)
                    size -= idx
                }
                // To determine a 4-byte start code, check if buf[0..2] is all 0s: if buf[2] == 1, it is a 3-byte start code.
                // "start code" is immediately followed by an NAL header (when the first byte is exactly 0x01, buf[3] is also 1,
                // (It may be misinterpreted as 4 bytes, causing the NAL header’s first byte to be dropped and resulting in a corrupted NAL.)
                startCodeLen =
                    if (size >= 4 &&
                        buf[0].toInt() == 0 &&
                        buf[1].toInt() == 0 &&
                        buf[2].toInt() == 0
                    ) {
                        4
                    } else {
                        3
                    }
                // 2. Find the next "start code" to end the NAL
                val nextIdx = findStartCode(startCodeLen)
                if (nextIdx < 0) {
                    // The complete NAL has not yet been assembled: retain all buffering (including start code) and other data.
                    return null
                }
                if (nextIdx == startCodeLen) {
                    // Consecutive "start code" (empty): Skip the previous one and retry
                    buf.copyInto(buf, 0, startCodeLen, size)
                    size -= startCodeLen
                    continue
                }
                val nalLen = nextIdx - startCodeLen
                val nal = ByteArray(nalLen)
                buf.copyInto(nal, 0, startCodeLen, nextIdx)
                buf.copyInto(buf, 0, nextIdx, size)
                size -= nextIdx
                return Nal(nal[0].toInt() and 0x1f, nal)
            }
        }

        private fun trimTail(keep: Int) {
            if (size <= keep) return
            buf.copyInto(buf, 0, size - keep, size)
            size = keep
        }

        private fun findStartCode(from: Int): Int {
            var i = from
            while (i + 2 < size) {
                if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1) return i
                if (i + 3 < size &&
                    buf[i].toInt() == 0 &&
                    buf[i + 1].toInt() == 0 &&
                    buf[i + 2].toInt() == 0 &&
                    buf[i + 3].toInt() == 1
                ) {
                    return i
                }
                i++
            }
            return -1
        }
    }

    /** SPS/PPS bytes (Annex-B format, fed to the CSD decoder). */
    data class ParameterSets(
        val sps: ByteArray?,
        val pps: ByteArray?,
    )

    /** Extract SPS/PPS from the complete Annex-B stream (including the “start code” prefix, for MediaCodec CSD). */
    fun extractParameterSets(annexB: ByteArray): ParameterSets {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val parser = AnnexBParser()
        // The `push` return value is also NAL (the first one); it must not be omitted. Subsequently, `drain` flushes the remaining buffer.
        var nal = parser.push(annexB)
        while (nal != null) {
            when (nal.type) {
                7 -> sps = withStartCode(nal.data)
                8 -> pps = withStartCode(nal.data)
                else -> if (sps != null && pps != null) break
            }
            nal = parser.drain()
        }
        return ParameterSets(sps, pps)
    }

    internal fun withStartCode(nal: ByteArray): ByteArray {
        val out = ByteArray(nal.size + 4)
        out[0] = 0
        out[1] = 0
        out[2] = 0
        out[3] = 1
        nal.copyInto(out, 4)
        return out
    }

    /**
     * Does the frame contain an IDR NAL (nal_type=5)?
     *
     * After the surface is rebuilt, immediately configure using the cached parameter sets; playback must wait for IDR: P-frames depend on prior reference
     * Reference frames fed to the new decoder do not produce an image (some devices report an error directly). Remote keyint ≈ 0.5s,
     * Wait until the cost of IDR is manageable and does not have any dependency on remote servers to repeat SPS/PPS at keyframes.
     */
    fun containsIdr(annexB: ByteArray): Boolean {
        var i = 0
        val n = annexB.size
        while (i + 3 < n) {
            if (annexB[i] == 0.toByte() && annexB[i + 1] == 0.toByte()) {
                if (annexB[i + 2] == 1.toByte()) {
                    if ((annexB[i + 3].toInt() and 0x1F) == 5) return true
                    i += 3
                } else if (i + 4 < n && annexB[i + 2] == 0.toByte() && annexB[i + 3] == 1.toByte()) {
                    if ((annexB[i + 4].toInt() and 0x1F) == 5) return true
                    i += 4
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        return false
    }

    /**
     * Convert a complete Annex-B access unit into a length-prefixed sample used by Apple/AVCC.
     * The 00 00 01 / 00 00 00 01 preceding each NAL will be replaced with a 4-byte big-endian length; empty NAL
     * Will be ignored. Returns null if the input does not contain "start code" or exceeds the length overflow, to avoid passing a corrupted frame to
     * Platform decoder.
     */
    fun toAvccSample(annexB: ByteArray): ByteArray? {
        if (annexB.size < 4) return null
        val ranges = ArrayList<Pair<Int, Int>>(4)
        var searchFrom = 0
        while (true) {
            val start = findStartCode(annexB, searchFrom) ?: break
            val nalStart = start.first + start.second
            val next = findStartCode(annexB, nalStart)
            val nalEnd = next?.first ?: annexB.size
            if (nalEnd > nalStart) ranges += nalStart to nalEnd
            if (next == null) break
            searchFrom = next.first
        }
        if (ranges.isEmpty()) return null

        var outputSize = 0L
        for ((start, end) in ranges) outputSize += 4L + (end - start)
        if (outputSize <= 0L || outputSize > Int.MAX_VALUE) return null

        val output = ByteArray(outputSize.toInt())
        var out = 0
        for ((start, end) in ranges) {
            val size = end - start
            output[out] = (size ushr 24).toByte()
            output[out + 1] = (size ushr 16).toByte()
            output[out + 2] = (size ushr 8).toByte()
            output[out + 3] = size.toByte()
            annexB.copyInto(output, out + 4, start, end)
            out += 4 + size
        }
        return output
    }

    /** Remove the Annex-B start code from the parameter set for use in the CoreMedia format description. */
    fun stripStartCode(nal: ByteArray): ByteArray {
        val start = findStartCode(nal, 0)
        return if (start?.first == 0) nal.copyOfRange(start.second, nal.size) else nal.copyOf()
    }

    /** Return the start code's position and length; prioritize recognizing the 4-byte form. */
    private fun findStartCode(
        bytes: ByteArray,
        from: Int,
    ): Pair<Int, Int>? {
        var i = from.coerceAtLeast(0)
        while (i + 2 < bytes.size) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) {
                if (i + 3 < bytes.size && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()) {
                    return i to 4
                }
                if (bytes[i + 2] == 1.toByte()) return i to 3
            }
            i++
        }
        return null
    }

    /**
     * Parses the width and height after clipping from SPS NAL (H.264 exp-golomb; returns null on failure).
     *
     * Also accepts the Annex-B format used by raw NAL and MediaCodec CSD; remove leading zeros before reading.
     * emulation-prevention byte (00 00 03). Some OPPO/MTK decoders in
     * When configuring, ensure that MediaFormat includes the exact width and height.
     */
    fun parseSpsDimensions(sps: ByteArray): Pair<Int, Int>? {
        val start =
            when {
                sps.size >= 4 &&
                    sps[0] == 0.toByte() &&
                    sps[1] == 0.toByte() &&
                    sps[2] == 0.toByte() &&
                    sps[3] == 1.toByte() -> 4
                sps.size >= 3 && sps[0] == 0.toByte() && sps[1] == 0.toByte() && sps[2] == 1.toByte() -> 3
                else -> 0
            }
        if (sps.size - start < 4) return null
        val rbsp = ByteArray(sps.size - start)
        var rbspSize = 0
        var zeroCount = 0
        for (i in start until sps.size) {
            val value = sps[i].toInt() and 0xff
            if (zeroCount >= 2 && value == 3) {
                zeroCount = 0
                continue
            }
            rbsp[rbspSize++] = sps[i]
            zeroCount = if (value == 0) zeroCount + 1 else 0
        }
        val data = rbsp.copyOf(rbspSize)
        return try {
            var bitPos = 0

            fun readBit(): Int {
                require(bitPos < data.size * 8)
                val b = data[bitPos / 8].toInt() and 0xff
                val v = (b ushr (7 - bitPos % 8)) and 1
                bitPos++
                return v
            }

            fun readBits(n: Int): Int {
                var v = 0
                repeat(n) { v = (v shl 1) or readBit() }
                return v
            }

            fun readUe(): Int {
                var zeros = 0
                while (readBit() == 0) {
                    zeros++
                    require(zeros < 31)
                }
                return (1 shl zeros) - 1 + readBits(zeros)
            }

            fun readSe(): Int {
                val codeNum = readUe()
                return if (codeNum and 1 == 0) -(codeNum / 2) else (codeNum + 1) / 2
            }

            fun skipScalingList(count: Int) {
                var lastScale = 8
                var nextScale = 8
                repeat(count) {
                    if (nextScale != 0) nextScale = (lastScale + readSe() + 256) % 256
                    if (nextScale != 0) lastScale = nextScale
                }
            }

            readBits(8) // NAL header
            val profile = readBits(8)
            readBits(8) // constraint flags
            readBits(8) // level_idc
            readUe() // seq_parameter_set_id
            var chromaFormat = 1
            var separateColourPlane = 0
            if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
                chromaFormat = readUe()
                if (chromaFormat == 3) separateColourPlane = readBit()
                readUe() // bit_depth_luma_minus8
                readUe() // bit_depth_chroma_minus8
                readBit() // qpprime_y_zero_transform_bypass_flag
                if (readBit() == 1) {
                    repeat(if (chromaFormat == 3) 12 else 8) { index ->
                        if (readBit() == 1) skipScalingList(if (index < 6) 16 else 64)
                    }
                }
            }
            readUe() // log2_max_frame_num_minus4
            when (readUe()) { // pic_order_cnt_type
                0 -> readUe()
                1 -> {
                    readBit() // delta_pic_order_always_zero_flag
                    readSe() // offset_for_non_ref_pic
                    readSe() // offset_for_top_to_bottom_field
                    repeat(readUe()) { readSe() }
                }
            }
            readUe() // max_num_ref_frames
            readBit() // gaps_in_frame_num_value_allowed_flag
            val widthMbs = readUe() + 1
            val heightMapUnits = readUe() + 1
            val frameMbsOnly = readBit()
            if (frameMbsOnly == 0) readBit() // mb_adaptive_frame_field_flag
            readBit() // direct_8x8_inference_flag
            var cropLeft = 0
            var cropRight = 0
            var cropTop = 0
            var cropBottom = 0
            if (readBit() == 1) {
                cropLeft = readUe()
                cropRight = readUe()
                cropTop = readUe()
                cropBottom = readUe()
            }
            val chromaArrayType = if (separateColourPlane == 1) 0 else chromaFormat
            val subWidth = if (chromaArrayType == 1 || chromaArrayType == 2) 2 else 1
            val subHeight = if (chromaArrayType == 1) 2 else 1
            val cropUnitX = if (chromaArrayType == 0) 1 else subWidth
            val cropUnitY = if (chromaArrayType == 0) 2 - frameMbsOnly else subHeight * (2 - frameMbsOnly)
            val width = widthMbs * 16 - (cropLeft + cropRight) * cropUnitX
            val height = (2 - frameMbsOnly) * heightMapUnits * 16 - (cropTop + cropBottom) * cropUnitY
            require(width > 0 && height > 0)
            width to height
        } catch (_: Exception) {
            null
        }
    }

    /** nal_type → Name (debug/log). */
    fun typeName(type: Int): String =
        when (type) {
            1 -> "SLICE"
            5 -> "IDR"
            6 -> "SEI"
            7 -> "SPS"
            8 -> "PPS"
            else -> "T$type"
        }
}
