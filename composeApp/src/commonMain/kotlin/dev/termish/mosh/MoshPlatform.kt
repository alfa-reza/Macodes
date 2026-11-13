package dev.termish.mosh

/**
 * Blocking UDP socket abstraction (for use in I/O threads/coroutines).
 * returns `null` to indicate a timeout (used as the SSP driver).
 *
 * Platform send result: TOO_LARGE (EMSGSIZE) is used for MTU fallback feedback.
 */

internal enum class SendResult { OK, TOO_LARGE, FAILED }

/** Received datagram. [congestionExperienced] is the ECN CE bit flag (not currently supported by the platform; always false). */
internal class UdpDatagram(
    val data: ByteArray,
    val congestionExperienced: Boolean = false,
)

internal expect class MoshUdpSocket(
    ip: String,
    port: Int,
) {
    /** Is the parsed remote address family IPv6? The transport layer selects the MTU based on this (mosh uses the value of `sa_family`,
     *  (Do not rely on hostnames that have not been parsed for this determination.) */
    val isIpv6: Boolean

    fun send(data: ByteArray): SendResult

    /** Returns null if no data is received within the timeoutMillis period. */
    fun receive(timeoutMillis: Int): UdpDatagram?

    fun close()
}

internal expect fun zlibCompress(data: ByteArray): ByteArray

internal expect fun zlibDecompress(data: ByteArray): ByteArray
