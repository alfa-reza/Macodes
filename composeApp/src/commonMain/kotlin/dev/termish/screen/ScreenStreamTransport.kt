package dev.termish.screen

import dev.termish.mosh.Fragment
import dev.termish.mosh.FragmentAssembly
import dev.termish.mosh.Fragmenter

/**
 * Semi-reliable fragmentation for screen streaming (Solution A streaming prototype).
 *
 * Reuse Mosh’s SSP fragment format ([Fragment] id/num/final), but the payload is video frame bytes rather than
 * SSP directive. Key differences from [dev.termish.mosh.MoshTransport]: Video streams are **unidirectional and may drop frames**.
 * Acceptable**, so here there is only “fragmentation + reassembly + packet loss,” with no state sync and no retransmission,
 * No RTT estimate—much simpler than the SSP transport layer.
 *
 * Semi-reliable semantics:
 * - Sender side: allocate a new instruction id for each video packet; zlib compression + slicing by MTU
 * - Receiving end: Tolerates out-of-order or duplicate fragmentation; discards the entire packet if a fragment is missing (it’s okay if a single packet is lost in a video stream,
 *   the decoder waits for the next IDR to recover)
 * - In a continuous video stream, in the event of packet loss, the new ID of the next packet will cause the receiving end to automatically reset (it will not get permanently stuck).
 *
 * Thread model: One instance at each end; not thread-safe; driven by a single thread per session (consistent with MoshTransport).
 */
class ScreenStreamSender(
    /** Low-level datagram transmission (UDP send; silent on failure, no retransmission of video streams). */
    private val sendDatagram: (ByteArray) -> Unit,
) {
    private val fragmenter = Fragmenter()

    /** Send a video payload (raw bytes, internally zlib-compressed with fragmentation). */
    fun send(
        payload: ByteArray,
        mtu: Int,
    ) {
        for (frag in fragmenter.makeFragments(payload, mtu)) {
            sendDatagram(frag.toBytes())
        }
    }
}

/** Receive-end reassembly: Feeds in a datagram; a non-null return value indicates that a complete video packet has been reassembled (bytes after decompression). */
class ScreenStreamReceiver {
    private val assembly = FragmentAssembly()

    /** Debugging: did the most recent feed overwrite an unfinished block (unfinished assembly abandoned due to packet loss; the flag is cleared after the upper layer consumes it)? */
    var lastAbandoned = false

    /**
     * Feed in a datagram.
     *
     * @return the decompressed bytes of the complete packet; null = the packet is not yet complete (waiting for more fragments,
     *  or fragmentation corruption / packet loss—the latter will remain null until the next packet’s new ID arrives and triggers a reset).
     */
    fun onDatagram(data: ByteArray): ByteArray? {
        val frag =
            try {
                Fragment.parse(data)
            } catch (_: Exception) {
                return null
            }
        if (!assembly.addFragment(frag)) {
            lastAbandoned = assembly.lastAbandoned
            return null
        }
        lastAbandoned = false
        return assembly.assembleBytes()
    }
}
