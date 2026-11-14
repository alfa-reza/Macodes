package dev.macodes.ui

import androidx.compose.runtime.Composable

/**
 * Platform system bar (status bar icons) appearance: use light-colored icons on a dark background (lightIcons=true),
 * Use dark icons on a light background (lightIcons=false). Toggle based on the app’s theme in real-time.
 */
@Composable
expect fun PlatformStatusBarIcons(lightIcons: Boolean)
