package dev.macodes.ui

import androidx.compose.runtime.Composable

/** Cross-platform system back button handling (Android back gesture/back button, etc.). */
@Composable
expect fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)
