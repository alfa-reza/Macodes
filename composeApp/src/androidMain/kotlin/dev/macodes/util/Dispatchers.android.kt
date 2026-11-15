package dev.macodes.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

internal actual fun <T> withRepoLock(
    lock: Any,
    block: () -> T,
): T = synchronized(lock, block)
