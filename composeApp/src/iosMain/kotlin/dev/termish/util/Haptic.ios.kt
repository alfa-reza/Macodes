package dev.termish.util

import platform.UIKit.UIImpactFeedbackGenerator

/*Instance is held globally (creation is costly; re-creating it drops feedback). */
private val generator = UIImpactFeedbackGenerator()

actual fun hapticTick() {
    generator.impactOccurredWithIntensity(0.8)
}
