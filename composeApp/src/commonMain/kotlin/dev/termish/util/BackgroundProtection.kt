package dev.termish.util

/** Android background battery policy status; other platforms return [NOT_APPLICABLE]. */
enum class BackgroundProtectionState {
    NOT_APPLICABLE,
    SYSTEM_MANAGED,
    SYSTEM_EXEMPT,
}

/*Battery management entries differ by vendor; used only to pick the appropriate user-facing guidance text. */
enum class BackgroundProtectionVendor {
    GENERIC,
    OPPO_FAMILY,
    XIAOMI_FAMILY,
    VIVO_FAMILY,
    SAMSUNG,
}

/** Query the exemption status of the Doze system. Vendor-specific background switches typically cannot be read by the app. */
expect fun backgroundProtectionState(): BackgroundProtectionState

/** Query the device manufacturer to show the applicable background settings path. */
expect fun backgroundProtectionVendor(): BackgroundProtectionVendor

/** Open the system details page for the current app; permanent denials for battery, notifications, and microphone can all be resumed from here. */
expect fun openApplicationSettings()

internal fun shouldShowBackgroundProtectionGuide(
    state: BackgroundProtectionState,
    vendor: BackgroundProtectionVendor,
    alreadyPrompted: Boolean,
    hasConnectedSshSession: Boolean,
): Boolean =
    !alreadyPrompted &&
        hasConnectedSshSession &&
        state != BackgroundProtectionState.NOT_APPLICABLE &&
        (state == BackgroundProtectionState.SYSTEM_MANAGED || vendor != BackgroundProtectionVendor.GENERIC)
