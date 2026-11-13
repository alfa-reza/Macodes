package dev.termish.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface as SkiaTypeface
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle

/**
 * iOS: Explicitly use the PingFang SC system font by name for CJK characters.
 *
 * Do not use FontFamily.Default: CMP 1.8.1’s fallback for missing characters in Canvas path drawing is unreliable
 * (The wide character "run" still appears as garbled text even when set to "Default"); the font resource (Noto Sans SC) on iOS
 * Async loading also has timing issues (which once caused an entire section of Chinese text to appear as garbled characters). FontMgr uses the CoreText system
 * Font library: syncs and is deterministic; PingFang SC is required for iOS 9+.
 *
 * Fallback chain: legacyMakeTypeface → matchFamilyStyle → Default.
 * If PingFang cannot be loaded (theoretically only in extreme environments), fall back to the default: font issues should not cause
 * The app crashes during the composition phase; after a fallback, it reverts to garbled text at most, and its behavior is no worse than before the fix.
 */
private val cjkFamily: FontFamily by lazy {
    runCatching {
        val typeface =
            FontMgr.default.legacyMakeTypeface("PingFang SC", FontStyle.NORMAL)
                ?: FontMgr.default.matchFamilyStyle("PingFang SC", FontStyle.NORMAL)
        typeface?.let { FontFamily(SkiaTypeface(it)) } ?: FontFamily.Default
    }.getOrDefault(FontFamily.Default)
}

@Composable
actual fun cjkFontFamily(): FontFamily = cjkFamily
