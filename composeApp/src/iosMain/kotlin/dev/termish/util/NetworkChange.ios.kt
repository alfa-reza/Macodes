package dev.termish.util

import androidx.compose.runtime.Composable

/** On iOS, the app suspends in the background and automatically reconnects when brought back to the foreground (using AppRoot lifecycle hooks), eliminating the need for a separate network listener. */
@Composable
actual fun observeNetworkChange(onChange: (NetworkChangeKind) -> Unit): () -> Unit = {}
