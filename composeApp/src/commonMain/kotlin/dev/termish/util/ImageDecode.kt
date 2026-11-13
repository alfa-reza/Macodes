package dev.termish.util

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decode image bytes into [ImageBitmap] (for SFTP image previews).
 * In case of failure (due to invalid or unsupported formats), return `null`, and display a tooltip in the UI layer.
 * Use the platform’s decoder to decode bitmaps; statically rasterize SVGs with a maximum length of 2048 pixels.
 */
expect fun decodeImage(
    bytes: ByteArray,
    isSvg: Boolean = false,
): ImageBitmap?
