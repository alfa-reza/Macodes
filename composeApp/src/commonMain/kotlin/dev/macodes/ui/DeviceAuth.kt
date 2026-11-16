package dev.macodes.ui

import androidx.compose.runtime.Composable

/**
 * Prompts for device authentication (biometric or PIN/pattern/password) before revealing
 * sensitive credentials such as a saved passphrase. If the device has no lock screen set,
 * invokes [onResult] with `true`.
 */
@Composable
expect fun rememberDeviceAuthenticator(): (
    title: String,
    subtitle: String,
    onResult: (Boolean) -> Unit,
) -> Unit
