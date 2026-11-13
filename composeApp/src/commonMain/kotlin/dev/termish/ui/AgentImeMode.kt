package dev.termish.ui

import androidx.compose.runtime.Composable

/** The Agent conversation page uses the system’s resize semantics; the terminal screen retains its policy of handling the IME independently. */
@Composable
expect fun AgentImeResizeEffect()
