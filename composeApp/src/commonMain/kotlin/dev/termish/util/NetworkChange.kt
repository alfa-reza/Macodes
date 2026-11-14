package dev.termish.util

import androidx.compose.runtime.Composable

/** Network events: Complete disconnection vs. changes to the default network instance. */
enum class NetworkChangeKind {
    /** Network disconnection (network jitter on the same IP; mosh can resume without needing to be rebuilt). */
    LOST,

    /** Default network changes (Wi-Fi/cellular toggling, switching between networks of the same type, or switching on/off VPN). */
    DEFAULT_NETWORK_CHANGED,
}

/** A network change listener; returns a cancellation function. */
@Composable
expect fun observeNetworkChange(onChange: (NetworkChangeKind) -> Unit): () -> Unit
