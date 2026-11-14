package dev.macodes.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

/** Android: The system font includes built-in CJK fallback. */
@Composable
actual fun cjkFontFamily(): FontFamily = FontFamily.Default
