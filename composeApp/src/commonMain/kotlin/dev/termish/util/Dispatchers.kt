package dev.termish.util

import kotlinx.coroutines.CoroutineDispatcher

/** A blocking dispatcher for socket/SSH work. */
expect fun ioDispatcher(): CoroutineDispatcher
