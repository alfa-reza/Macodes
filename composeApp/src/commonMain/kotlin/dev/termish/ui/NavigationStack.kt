package dev.termish.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

internal enum class NavigationDirection {
    NONE,
    FORWARD,
    BACKWARD,
}

/**
 * A small, observable screen stack.
 *
 * AppRoot enters and exits the top-level screen only through this stack, so backing out of a subpage is not hard-coded to the homepage.
 * Consecutive navigations to the same screen will be deduplicated to prevent duplicate stacking of async connection callbacks.
 */
internal class NavigationStack<T>(
    initial: T,
) {
    private val entries = mutableStateListOf(initial)
    private val directionState = mutableStateOf(NavigationDirection.NONE)

    val direction: NavigationDirection
        get() = directionState.value

    val current: T
        get() = entries.last()

    val canPop: Boolean
        get() = entries.size > 1

    val size: Int
        get() = entries.size

    fun push(value: T) {
        if (entries.last() == value) return
        directionState.value = NavigationDirection.FORWARD
        entries.add(value)
    }

    fun replace(value: T) {
        directionState.value = NavigationDirection.NONE
        entries[entries.lastIndex] = value
    }

    fun pop(): Boolean {
        if (!canPop) return false
        directionState.value = NavigationDirection.BACKWARD
        entries.removeAt(entries.lastIndex)
        return true
    }

    fun reset(value: T) {
        directionState.value = NavigationDirection.NONE
        entries.clear()
        entries.add(value)
    }

    internal fun snapshot(): List<T> = entries.toList()
}
