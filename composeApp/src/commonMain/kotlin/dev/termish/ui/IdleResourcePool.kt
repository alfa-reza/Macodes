package dev.termish.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Expensive resources are retained briefly after the screen is left; they are released only if they have not been reacquired by the time the timeout expires.
 *
 * All calls are executed sequentially within the same UI scope; no additional locking is required. The signature captures the host configuration or
 * credentials: an outdated resource is removed as soon as the signature changes, to avoid reusing obsolete connections.
 */
internal class IdleResourcePool<K : Any, S, V : Any>(
    private val scope: CoroutineScope,
    private val idleTimeoutMillis: Long,
    private val closeResource: (V) -> Unit,
    private val canCloseResource: (V) -> Boolean = { true },
) {
    private data class Entry<S, V>(
        val signature: S,
        val value: V,
        var closeJob: Job? = null,
    )

    private val entries = mutableMapOf<K, Entry<S, V>>()

    fun acquire(
        key: K,
        signature: S,
        create: () -> V,
    ): V {
        val existing = entries[key]
        if (existing != null && existing.signature == signature) {
            existing.closeJob?.cancel()
            existing.closeJob = null
            return existing.value
        }
        existing?.let(::closeEntry)
        return create().also { entries[key] = Entry(signature, it) }
    }

    fun release(key: K) {
        val entry = entries[key] ?: return
        entry.closeJob?.cancel()
        entry.closeJob =
            scope.launch {
                delay(idleTimeoutMillis)
                while (entries[key] === entry && !canCloseResource(entry.value)) {
                    delay(idleTimeoutMillis)
                }
                if (entries[key] === entry) {
                    entries.remove(key)
                    closeEntry(entry)
                }
            }
    }

    /** Explicitly terminate a resource (close a connection page, delete a host) without waiting for the idle timer to expire. */
    fun remove(key: K) {
        entries.remove(key)?.let(::closeEntry)
    }

    fun closeAll() {
        val current = entries.values.toList()
        entries.clear()
        current.forEach(::closeEntry)
    }

    private fun closeEntry(entry: Entry<S, V>) {
        entry.closeJob?.cancel()
        entry.closeJob = null
        runCatching { closeResource(entry.value) }
    }
}
