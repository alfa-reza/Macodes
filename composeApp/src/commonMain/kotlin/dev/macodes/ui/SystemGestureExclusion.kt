package dev.macodes.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Exclude this region from the system’s back gesture (Android side-swipe back/predictive back).
 * For floating window handles: When the handle is against the right edge of the screen, a horizontal scale gesture starting from the handle
 * The system’s gesture response takes over (a quick swipe takes you straight to the home screen, and you can’t adjust the window width).
 * For non-Android platforms, implement a null implementation.
 */
@Composable
expect fun Modifier.excludeSystemBackGesture(): Modifier
