package dev.macodes.data

import com.russhwolf.settings.MapSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HostRepositoryConcurrencyTest {
    @Test
    fun concurrentUpsertHostCallsNeverDropRecords() {
        val repo = HostRepository(settings = MapSettings())
        val threadCount = 6
        val perThread = 10
        val pool = Executors.newFixedThreadPool(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            pool.execute {
                start.await()
                try {
                    for (i in 0 until perThread) {
                        repo.upsertHost(
                            Host(
                                id = "h-$t-$i",
                                name = "host-$t-$i",
                                hostname = "10.0.$t.$i",
                                port = 22,
                                username = "root",
                            ),
                        )
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        done.await()
        pool.shutdown()

        assertEquals(threadCount * perThread, repo.listHosts().size)
    }

    @Test
    fun quickConnectHostKeysStayCappedWithLruEviction() {
        val repo = HostRepository(settings = MapSettings())
        for (i in 1..100) {
            repo.recordQuickConnectFingerprint("10.0.0.$i", 22, "SHA256:k$i")
        }
        // Touch entry 1 so entry 2 becomes oldest
        repo.recordQuickConnectFingerprint("10.0.0.1", 22, "SHA256:k1-updated")
        repo.recordQuickConnectFingerprint("10.0.0.101", 22, "SHA256:k101")

        assertEquals("SHA256:k1-updated", repo.getQuickConnectFingerprint("10.0.0.1", 22))
        assertNull(repo.getQuickConnectFingerprint("10.0.0.2", 22))
        assertNotNull(repo.getQuickConnectFingerprint("10.0.0.101", 22))
    }
}
