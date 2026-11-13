package dev.termish.screen

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Loopback test for screen streaming UDP roaming sessions (Android local JVM): real UDP socket,
 * The mock relay uses a standard DatagramSocket bound to a random port to verify the heartbeat loop and resume transmission after a network outage.
 */
class ScreenStreamUdpSessionTest {
    private val mtu = 400

    @Test
    fun firstPacketIsReloadThenHeartbeats() =
        runBlocking {
            val relay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            val session =
                ScreenStreamUdpSession(
                    ip = "127.0.0.1",
                    port = relay.localPort,
                    scope = this,
                    onVideoPacket = {},
                )
            session.start()
            try {
                // The first packet should be `reload` (restart the remote FFmpeg to re-read the streaming parameters).
                val first = receivePacket(relay)
                assertTrue(first != null && first.contentEquals(SCREEN_RELOAD_MAGIC), "首包应为 reload magic")
                // Subsequent packets are standard heartbeat
                val second = receivePacket(relay)
                // Subsequent packets are heartbeats carrying frame-loss feedback (THB\x01 + 1 byte frame-loss rate, initially 0%)
                assertTrue(
                    second != null && second!!.size == 5 && second!!.contentEquals(heartbeatWithLoss(0)),
                    "后续包应为带反馈的心跳",
                )
            } finally {
                session.close()
                relay.close()
            }
        }

    @Test
    fun heartbeatAndVideoRoundTrip() =
        runBlocking {
            val relay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            val received = CopyOnWriteArrayList<ByteArray>()
            val session =
                ScreenStreamUdpSession(
                    ip = "127.0.0.1",
                    port = relay.localPort,
                    scope = this,
                    onVideoPacket = { received.add(it) },
                )
            session.start()
            try {
                // 1. Wait for the phone’s heartbeat → Obtain the phone’s address (this is how the roaming address is updated)
                val phoneAddr = receiveHeartbeat(relay)
                assertTrue(phoneAddr != null, "应收到手机心跳包")

                // 2. Relay video packets (ScreenStreamSender fragmentation → standard socket transmission to phone address)
                val payload = Random(42).nextBytes(5000)
                sendVideo(relay, phoneAddr!!, payload)

                // 3. The phone should perform the reassembly of the complete video packet
                val got =
                    withTimeoutOrNull(5000) {
                        while (received.isEmpty()) delay(50)
                        received.first()
                    }
                assertTrue(got != null, "应收到重组后的视频包")
                assertContentEquals(payload, got)
            } finally {
                session.close()
                relay.close()
            }
        }

    @Test
    fun roamingSurvivesDisconnectAndResumes() =
        runBlocking {
            val relay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            val received = CopyOnWriteArrayList<ByteArray>()
            val session =
                ScreenStreamUdpSession(
                    ip = "127.0.0.1",
                    port = relay.localPort,
                    scope = this,
                    onVideoPacket = { received.add(it) },
                )
            session.start()
            try {
                val phoneAddr = receiveHeartbeat(relay)!!
                relay.soTimeout = 1 // Followed by non-blocking detection

                // First batch: accepted normally
                val p1 = Random(7).nextBytes(2000)
                sendVideo(relay, phoneAddr, p1)
                withTimeoutOrNull(5000) { while (received.size < 1) delay(50) }
                assertTrue(received.isNotEmpty(), "断网前应收到第一批")

                // Internet Disconnection: The relay stops transmitting for 3 seconds (mocking a WiFi disconnection). The session must not terminate.
                val before = received.size
                delay(3000)
                assertTrue(session.isActive(), "断网期间会话不应退出")

                // Resume: relay continues sending (the address may have changed, but the loopback address here remains the same)
                val p2 = Random(9).nextBytes(2000)
                sendVideo(relay, phoneAddr, p2)
                withTimeoutOrNull(5000) { while (received.size <= before) delay(50) }
                assertTrue(received.size > before, "恢复后应继续收到视频（续传）")
                assertContentEquals(p2, received.last())
            } finally {
                session.close()
                relay.close()
            }
        }

    /** Wait for a heartbeat packet from the phone; returns the phone's socket address. */
    private fun receiveHeartbeat(relay: DatagramSocket): java.net.SocketAddress? {
        val buf = ByteArray(64)
        relay.soTimeout = 5000
        val pkt = DatagramPacket(buf, buf.size)
        return try {
            relay.receive(pkt)
            assertTrue(pkt.length >= 4, "心跳包应至少 4 字节")
            pkt.socketAddress
        } catch (_: Exception) {
            null
        }
    }

    /** Receive a packet and return its content (distinguish reload / heartbeat magic). */
    private fun receivePacket(relay: DatagramSocket): ByteArray? {
        val buf = ByteArray(64)
        relay.soTimeout = 5000
        val pkt = DatagramPacket(buf, buf.size)
        return try {
            relay.receive(pkt)
            pkt.data.copyOf(pkt.length)
        } catch (_: Exception) {
            null
        }
    }

    /** Use ScreenStreamSender for fragmentation of the stream and sending of each segment to the phone’s address. */
    private fun sendVideo(
        relay: DatagramSocket,
        phoneAddr: java.net.SocketAddress,
        payload: ByteArray,
    ) {
        val frags = ArrayList<ByteArray>()
        ScreenStreamSender { frags.add(it) }.send(payload, mtu)
        for (f in frags) {
            relay.send(DatagramPacket(f, f.size, phoneAddr))
        }
    }
}
