package dev.macodes.util

import kotlinx.coroutines.CoroutineDispatcher

/** A blocking dispatcher for socket/SSH work. */
expect fun ioDispatcher(): CoroutineDispatcher

internal expect fun <T> withRepoLock(
    lock: Any,
    block: () -> T,
): T
