package dev.termish.ui

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformStatusBarIcons(lightIcons: Boolean) {
    // The iOS status bar style is controlled by Info.plist / the host; nothing to handle here
}

@Composable
actual fun PlatformImmersiveMode(immersive: Boolean) {
    // When iOS is in fullscreen mode, content filling the screen is handled by the Compose layer (the status bar is the Dynamic Island/capsule and cannot be hidden).
}
