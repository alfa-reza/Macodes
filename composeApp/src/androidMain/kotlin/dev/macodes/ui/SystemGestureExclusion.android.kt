package dev.macodes.ui

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView

/** Android: Declare this region as excluded from the window’s system gesture list (updated in real-time based on layout position). */
@Composable
actual fun Modifier.excludeSystemBackGesture(): Modifier =
    composed {
        val view = LocalView.current
        this.onGloballyPositioned { coords ->
            val window = (view.context as? Activity)?.window ?: return@onGloballyPositioned
            // setSystemGestureExclusionRects is available on API 29+ (minSdk 26):
            // Earlier versions do not support predictive return gestures; no need to exclude them (lint NewApi requires conditional execution).
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@onGloballyPositioned
            val b = coords.boundsInWindow()
            window.setSystemGestureExclusionRects(
                listOf(Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())),
            )
        }
    }
