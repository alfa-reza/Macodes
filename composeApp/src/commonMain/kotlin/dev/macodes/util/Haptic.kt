package dev.macodes.util

/**
 * Light haptic feedback (for toolbar buttons, success with connections, and other actions).
 * Controlled by the “Haptic Feedback” switch on the Settings page (determined by the caller).
 * Android: System KEYBOARD_TAP tap (no permissions required).
 */
expect fun hapticTick()
