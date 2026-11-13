package dev.termish.mosh

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.zip.Deflater
import java.util.zip.Inflater

internal actual class MoshUdpSocket actual constructor(
    ip: String,
    port: Int,
) {
    private val remoteAddr = InetAddress.getByName(ip)
    actual val isIpv6: Boolean = remoteAddr is java.net.Inet6Address
    private val socket =
        DatagramSocket().apply {
            // Screen streaming / mosh both involve bursty small packets: increase the buffering to prevent packet loss during bursts.
            // (The default size is typically 64–208 KB; fragmentation of 55×1200 bytes is enough to fill the quota.)
            // Screen stream tests show thousands of packets per second; 1 MB is still consumed slowly until the buffer is full (RcvbufErrors +80 per second),
            // Set the limit to 4MB to allow ample window for processing.
            runCatching { receiveBufferSize = 4 shl 20 }
            connect(remoteAddr, port)
        }
    private val buf = ByteArray(4096)
    private val pkt = DatagramPacket(buf, buf.size)

    actual fun send(data: ByteArray): SendResult =
        try {
            socket.send(DatagramPacket(data, data.size))
            SendResult.OK
        } catch (e: java.net.SocketException) {
            // The Java layer on Linux/Android does not expose `errno`; "Message too long" is a common text for `EMSGSIZE`.
            if (e.message?.contains("too long", ignoreCase = true) == true) {
                SendResult.TOO_LARGE
            } else {
                SendResult.FAILED
            }
        } catch (_: Exception) {
            SendResult.FAILED
        }

    actual fun receive(timeoutMillis: Int): UdpDatagram? {
        // Hot-path optimization: Set `soTimeout` only when it changes (calling `setsockopt` for every packet is a redundant system call,
        // (Thousands of packets per second in the screen stream); DatagramPacket reuse (eliminates the need to allocate a packet for each one)
        if (socket.soTimeout != timeoutMillis.coerceAtLeast(1)) {
            socket.soTimeout = timeoutMillis.coerceAtLeast(1)
        }
        pkt.length = buf.size
        return try {
            socket.receive(pkt)
            UdpDatagram(pkt.data.copyOf(pkt.length))
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    actual fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}

internal actual fun zlibCompress(data: ByteArray): ByteArray {
    val deflater = Deflater()
    deflater.setInput(data)
    deflater.finish()
    val buf = ByteArray(data.size + 64)
    val out = java.io.ByteArrayOutputStream()
    try {
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
    } finally {
        deflater.end()
    }
    return out.toByteArray()
}

internal actual fun zlibDecompress(data: ByteArray): ByteArray {
    val inflater = Inflater()
    inflater.setInput(data)
    val buf = ByteArray(8192)
    val out = java.io.ByteArrayOutputStream()
    try {
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            }
            out.write(buf, 0, n)
        }
    } finally {
        inflater.end()
    }
    return out.toByteArray()
}
