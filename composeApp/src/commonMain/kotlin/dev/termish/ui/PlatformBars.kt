package dev.termish.ui

import androidx.compose.runtime.Composable

/**
 * Platform system bar (status bar icons) appearance: use light-colored icons on a dark background (lightIcons=true),
 * Use dark icons on a light background (lightIcons=false). Toggle based on the app’s theme in real-time.
 */
@Composable
expect fun PlatformStatusBarIcons(lightIcons: Boolean)

/**
 * Immersive mode (fullscreen video): The system status bar is hidden when `immersive=true` is set; it is resumed upon exit.
 * For non-Android platforms, use a null implementation (the Compose layer handles content padding).
 */
@Composable
expect fun PlatformImmersiveMode(immersive: Boolean)
