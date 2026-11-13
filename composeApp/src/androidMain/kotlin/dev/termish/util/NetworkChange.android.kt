package dev.termish.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

private const val LOST_CONFIRM_MS = 1_000L

/**
 * Android: Monitor changes in the default network connection. Network toggling (Wi-Fi ↔ mobile data, switching between networks of the same type, VPN)
 * notify the upper layer to verify SSH; Mosh continues to rely on the protocol's own roaming capability.
 */
@Composable
actual fun observeNetworkChange(onChange: (NetworkChangeKind) -> Unit): () -> Unit {
    val context = LocalContext.current
    val cm =
        remember {
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        }
    val latestOnChange by rememberUpdatedState(onChange)
    val unregister =
        remember(cm) {
            // Default network and transport-type baseline; after registering, the system will immediately perform one callback, which records but does not trigger.
            var lastNetwork: Network? = null
            var lastTransport = -1
            var lastLostAt = 0L // LOST Throttling: Separate from toggling throttling to avoid swallowing subsequent default network changes
            var lastSwitchAt = 0L // DEFAULT_NETWORK_CHANGED Throttling
            val callbackHandler = Handler(Looper.getMainLooper())
            var pendingLost: Runnable? = null

            fun cancelPendingLost() {
                pendingLost?.let(callbackHandler::removeCallbacks)
                pendingLost = null
            }

            fun transportOf(caps: NetworkCapabilities?): Int =
                when {
                    caps == null -> -1
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
                    else -> 3
                }

            fun notifyNetworkChanged() {
                val now = SystemClock.elapsedRealtime()
                if (now - lastSwitchAt >= 3_000) {
                    lastSwitchAt = now
                    latestOnChange(NetworkChangeKind.DEFAULT_NETWORK_CHANGED)
                }
            }

            fun handleNetwork(
                network: Network,
                t: Int,
            ) {
                if (t == -1) return // capabilities not ready: wait until onCapabilitiesChanged to check, to avoid a false "transport toggle"
                if (lastNetwork == null || lastTransport == -1) {
                    lastNetwork = network
                    lastTransport = t // First-time registration callback: Establish a baseline; do not trigger
                    return
                }
                // Network handle changes can also cover AP/network changes within the same Wi-Fi; consider only the transport
                // This type of IP change will be missed. When capabilities jitter on the same handle, only
                // transport: It is only necessary to verify the old SSH when there is an actual change.
                val changed = network != lastNetwork || t != lastTransport
                lastNetwork = network
                lastTransport = t
                if (changed) notifyNetworkChanged()
            }

            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        // When the default network switches, the system may first report the old network's onLost, then later report the new network's
                        // onAvailable. Cancel pending LOSTs to avoid, on a brief app or Wi-Fi jitter,
                        // actively closing a still-healthy SSH session and showing “Reconnect.”
                        cancelPendingLost()
                        // When `onAvailable` is called, capabilities may not yet be ready (a null value may be returned); once they are truly ready,
                        // onCapabilitiesChanged will be called as a callback once more; it serves as a fallback to ensure events are not lost.
                        handleNetwork(network, transportOf(cm.getNetworkCapabilities(network)))
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        caps: NetworkCapabilities,
                    ) {
                        cancelPendingLost()
                        handleNetwork(network, transportOf(caps))
                    }

                    override fun onLost(network: Network) {
                        if (lastTransport == -1) return
                        cancelPendingLost()
                        val confirmLost =
                            Runnable {
                                pendingLost = null
                                val active = cm.activeNetwork
                                if (active != null) {
                                    // The old default network is gone, but the new default network is now available: this is a toggle rather than
                                    // Completely offline. Rely on the default network detection; do not send a LOST packet.
                                    handleNetwork(active, transportOf(cm.getNetworkCapabilities(active)))
                                    return@Runnable
                                }
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastLostAt >= 3_000) {
                                    lastLostAt = now
                                    latestOnChange(NetworkChangeKind.LOST)
                                }
                            }
                        pendingLost = confirmLost
                        callbackHandler.postDelayed(confirmLost, LOST_CONFIRM_MS)
                        // Do not reset the baseline: Changes to the default network should still be detectable after a new network appears.
                    }
                }
            cm.registerDefaultNetworkCallback(callback, callbackHandler)
            val unregisterFn: () -> Unit = {
                cancelPendingLost()
                cm.unregisterNetworkCallback(callback)
            }
            unregisterFn
        }
    return unregister
}
