package dev.macodes.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VirtualMouseGeometryTest {
    @Test
    fun fittedFrameMatchesCenteredLetterboxAndBottomAlignment() {
        val centered = fittedScreenRect(1080f, 2000f, 1920f, 1080f, 1f, 0f, 0f, false)
        assertEquals(0f, centered.left, 0.01f)
        assertEquals(696.25f, centered.top, 0.01f)
        assertEquals(1080f, centered.right, 0.01f)
        assertEquals(1303.75f, centered.bottom, 0.01f)

        val bottom = fittedScreenRect(1080f, 2000f, 1920f, 1080f, 1f, 0f, 0f, true)
        assertEquals(1392.5f, bottom.top, 0.01f)
        assertEquals(2000f, bottom.bottom, 0.01f)
    }

    @Test
    fun rightControlAreaShiftsWithoutResizingFrame() {
        val normal = fittedScreenRect(1080f, 2000f, 1920f, 1080f, 1f, 0f, 0f, false)
        val shifted = fittedScreenRect(1080f, 2000f, 1920f, 1080f, 1f, -420f, 0f, false)
        assertEquals(normal.width, shifted.width, 0.01f)
        assertEquals(normal.height, shifted.height, 0.01f)
        assertEquals(normal.left - 420f, shifted.left, 0.01f)
        assertEquals(normal.right - 420f, shifted.right, 0.01f)
    }

    @Test
    fun cursorCoordinateComesFromFixedPanelAnchor() {
        val frame = ScreenRect(10f, 20f, 1010f, 520f)
        assertEquals(ScreenPoint(0.25f, 0.5f), cursorAtVirtualMouseAnchor(ScreenPoint(260f, 270f), frame))
        assertEquals(ScreenPoint(1f, 0f), cursorAtVirtualMouseAnchor(ScreenPoint(9999f, -20f), frame))
    }

    @Test
    fun cursorDrawingStaysInsideFrameWhileHotspotReachesRightBottom() {
        val frame = ScreenRect(10f, 20f, 1010f, 520f)
        val hotspot = cursorHotspot(ScreenPoint(1f, 1f), frame)
        assertEquals(ScreenPoint(1010f, 520f), hotspot)
        assertEquals(ScreenPoint(978f, 488f), cursorVisualOrigin(hotspot, frame, 32f, 32f))
    }

    @Test
    fun panelAnchorAndCursorStayInsideRemoteFrame() {
        val frame = ScreenRect(10f, 20f, 1010f, 520f)
        val left = clampVirtualMouseAnchor(ScreenPoint(-999f, -999f), frame, 800f, 200f, 80f)
        assertEquals(ScreenPoint(10f, 80f), left)

        val rightBottom = clampVirtualMouseAnchor(ScreenPoint(9999f, 9999f), frame, 800f, 200f, 80f)
        assertEquals(ScreenPoint(1010f, 520f), rightBottom)
    }

    @Test
    fun dockingStartsOnlyWhenWholeControlReachesRightEdge() {
        assertFalse(shouldDockVirtualMouseRight(300f, 700f, 1080f))
        assertTrue(shouldDockVirtualMouseRight(380f, 700f, 1080f))
    }

    @Test
    fun viewportWidthClampsPanelRightEdgeToViewportEdge() {
        // After the video is pushed leftwards by the right control area (frame.left goes negative), the anchor may still exceed frame.right
        // Fit within the control area, but ensure that the right edge of the panel ((anchor + controlWidth)) does not extend beyond the right edge of the viewport.
        val pushedFrame = ScreenRect(-100f, 20f, 900f, 520f)
        val anchor = clampVirtualMouseAnchor(ScreenPoint(9999f, 100f), pushedFrame, 800f, 200f, 80f, 1080f - 700f)
        // maxX = 1080 - 700 = 380
        assertEquals(380f, anchor.x)
        assertEquals(100f, anchor.y)
    }

    @Test
    fun viewportWidthKeepsPanelFullyVisibleWhenPushed() {
        // When fully expanded (overlap = controlWidth), the right edge of the panel aligns exactly with the right edge of the viewport:
        // Viewport 1080, anchor + controlWidth(700) = 1080
        val pushedFrame = ScreenRect(-700f, 20f, 300f, 520f)
        val anchor = clampVirtualMouseAnchor(ScreenPoint(9999f, 100f), pushedFrame, 800f, 200f, 80f, 380f)
        assertEquals(380f, anchor.x)
    }

    @Test
    fun pushAccumulatesOvershootInsteadOfDiscarding() {
        // Viewport 1080 / Control Area 700 → maxAnchorX=380. rawX does not extend past the right edge: do not push; anchor follows
        val before = computeVirtualMousePush(300f, 1080f, 700f)
        assertEquals(0f, before.overlapPx, 0.01f)
        assertEquals(300f, before.anchorX, 0.01f)

        // Overshoot 200: anchor fixed at 380, video shifted left by 200 (overshoot is cumulative rather than discarded per frame—
        // (Otherwise, it will be dragged to the right edge and get stuck, with the cursor never able to reach the rightmost column of the video.)
        val partial = computeVirtualMousePush(580f, 1080f, 700f)
        assertEquals(200f, partial.overlapPx, 0.01f)
        assertEquals(380f, partial.anchorX, 0.01f)

        // Overshoot exceeds control zone width: full push (overlap=700), right edge of video = 1080-700 = 380 =
        // anchor ——The cursor is positioned exactly on the rightmost column of the video (ToDesk effect); further overshoots are no longer accumulated
        val full = computeVirtualMousePush(9999f, 1080f, 700f)
        assertEquals(700f, full.overlapPx, 0.01f)
        assertEquals(380f, full.anchorX, 0.01f)
    }

    @Test
    fun pushSpringsBackBeforePanelMovesLeft() {
        // In the fully-pushed state, dragging left: rawX falls back and the video springs back first (overlap → 0) while the panel stays pinned;
        // Only after (rawX ≤ maxAnchorX) is the panel shifted to the left—which is naturally the inverse of a rightward push.
        val springBack = computeVirtualMousePush(780f, 1080f, 700f)
        assertEquals(400f, springBack.overlapPx, 0.01f)
        assertEquals(380f, springBack.anchorX, 0.01f)

        val reattached = computeVirtualMousePush(200f, 1080f, 700f)
        assertEquals(0f, reattached.overlapPx, 0.01f)
        assertEquals(200f, reattached.anchorX, 0.01f)
    }

    @Test
    fun pushHandlesNarrowViewport() {
        // maxAnchorX is clamped to 0 — it won’t crash and won’t go negative
        val push = computeVirtualMousePush(100f, 500f, 700f)
        assertEquals(100f, push.overlapPx, 0.01f)
        assertEquals(0f, push.anchorX, 0.01f)
    }

    @Test
    fun zoomedPushCapExtendsToBringRightEdgeToCursor() {
        // When zoomed in, the right edge of the screen is outside the viewport (2× width): the upper limit of the estimate increases, and the mouse pointer is pinned to the right edge,
        // The screen continuously translates in—the mouse cursor never leaves the screen (user feedback)
        val partial = computeVirtualMousePush(1500f, 1080f, 700f, 2160f)
        assertEquals(1120f, partial.overlapPx, 0.01f)
        assertEquals(380f, partial.anchorX, 0.01f)

        // Full push: Right margin 2160 - 1780 = 380 = anchor—the cursor can just reach the far-right column of the screen
        val full = computeVirtualMousePush(9999f, 1080f, 700f, 2160f)
        assertEquals(1780f, full.overlapPx, 0.01f)
        assertEquals(380f, full.anchorX, 0.01f)
    }

    @Test
    fun zoomedPushCapWhenRightEdgeAlreadyVisible() {
        // just a 120 push moves the right edge to the cursor
        // No further pushes (to avoid unnecessarily pushing content away)
        val push = computeVirtualMousePush(9999f, 1080f, 700f, 500f)
        assertEquals(120f, push.overlapPx, 0.01f)
        assertEquals(380f, push.anchorX, 0.01f)
    }
}
