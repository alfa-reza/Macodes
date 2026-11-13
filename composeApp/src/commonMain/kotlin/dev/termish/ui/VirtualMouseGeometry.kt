package dev.termish.ui

import kotlin.math.max
import kotlin.math.min

/** Floating-point coordinates that do not depend on Compose, intended for use by both virtual mouse bounds logic and unit tests. */
internal data class ScreenPoint(
    val x: Float,
    val y: Float,
)

/** The actual visible rectangle of the remote screen within the player container. */
internal data class ScreenRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

/**
 * Calculate the actual rectangle for the Fit screen; the formula must be consistent with [ScreenVideoSurface] and the outer graphicsLayer
 * Maintain consistency so that the virtual cursor lands within the actual video pixels rather than within the player’s black borders.
 */
internal fun fittedScreenRect(
    containerWidth: Float,
    containerHeight: Float,
    videoWidth: Float,
    videoHeight: Float,
    zoom: Float,
    panX: Float,
    panY: Float,
    alignBottom: Boolean,
): ScreenRect {
    if (containerWidth <= 0f || containerHeight <= 0f) return ScreenRect(0f, 0f, 0f, 0f)
    val sourceWidth = videoWidth.takeIf { it > 0f } ?: containerWidth
    val sourceHeight = videoHeight.takeIf { it > 0f } ?: containerHeight
    val fit = min(containerWidth / sourceWidth, containerHeight / sourceHeight)
    val fittedWidth = sourceWidth * fit
    val fittedHeight = sourceHeight * fit
    val safeZoom = zoom.coerceAtLeast(1f)
    val left = (containerWidth - fittedWidth) / 2f * safeZoom + panX
    val topBase = if (alignBottom) containerHeight - fittedHeight else (containerHeight - fittedHeight) / 2f
    val top = topBase * safeZoom + panY
    return ScreenRect(
        left = left,
        top = top,
        right = left + fittedWidth * safeZoom,
        bottom = top + fittedHeight * safeZoom,
    )
}

/** Fix the arrow hotspot at the top-left corner of the virtual mouse and convert it to 0..1 coordinates sent to the remote server. */
internal fun cursorAtVirtualMouseAnchor(
    anchor: ScreenPoint,
    frame: ScreenRect,
): ScreenPoint {
    if (frame.width <= 0f || frame.height <= 0f) return ScreenPoint(0f, 0f)
    return ScreenPoint(
        x = ((anchor.x - frame.left) / frame.width).coerceIn(0f, 1f),
        y = ((anchor.y - frame.top) / frame.height).coerceIn(0f, 1f),
    )
}

/** Normalize remote coordinates to hotspot positions on the local screen. */
internal fun cursorHotspot(
    cursor: ScreenPoint,
    frame: ScreenRect,
): ScreenPoint =
    ScreenPoint(
        x = frame.left + cursor.x.coerceIn(0f, 1f) * frame.width,
        y = frame.top + cursor.y.coerceIn(0f, 1f) * frame.height,
    )

/**
 * Arrow hotspots can extend to the four edges of the screen, but the arrow graphic itself must not be out of bounds: only the drawing origin may move,
 * Do not change the hotspot coordinates sent to the remote server.
 */
internal fun cursorVisualOrigin(
    hotspot: ScreenPoint,
    frame: ScreenRect,
    cursorWidth: Float,
    cursorHeight: Float,
): ScreenPoint {
    val maxX = (frame.right - cursorWidth).coerceAtLeast(frame.left)
    val maxY = (frame.bottom - cursorHeight).coerceAtLeast(frame.top)
    return ScreenPoint(
        x = hotspot.x.coerceIn(frame.left, maxX),
        y = hotspot.y.coerceIn(frame.top, maxY),
    )
}

/** Keep the arrow hotspot within the remote screen at all times, while ensuring the entire controller remains on the phone screen as much as possible. */
internal fun clampVirtualMouseAnchor(
    proposed: ScreenPoint,
    frame: ScreenRect,
    viewportHeight: Float,
    controlHeight: Float,
    topInset: Float,
    /** Viewport width (px): null = same as `frame.right` (old behavior); non-null = the panel’s right edge aligns with the right edge of the viewport (fully visible). */
    viewportWidth: Float? = null,
): ScreenPoint {
    if (frame.width <= 0f || frame.height <= 0f || viewportHeight <= 0f) return proposed
    val minY = max(frame.top, topInset)
    val maxY = min(frame.bottom, viewportHeight - controlHeight).coerceAtLeast(minY)
    // The left edge of the panel may extend beyond the right bounds of the video frame (into the right-hand control area), but the right edge of the panel must not extend beyond the right bounds of the viewport.
    val maxX = viewportWidth ?: frame.right
    return ScreenPoint(
        // Left Boundary = The greater of the left edge of the image and the left edge of the screen: when zoomed in, the left edge of the image is outside the screen (<0),
        // The mouse cannot move off the screen (touching the left edge of the screen triggers a left push); when not zoomed in, the left edge of the screen remains within the display area.
        // (Black border), the mouse is still restricted to the screen
        x = proposed.x.coerceIn(max(frame.left, 0f), maxX),
        y = proposed.y.coerceIn(minY, maxY),
    )
}

/** The conversion result, which gradually shifts to the right: video left offset + panel anchor X shown. */
internal data class VirtualMousePush(
    val overlapPx: Float,
    val anchorX: Float,
)

/**
 * "ToDesk-style" progressive push when the virtual mouse is dragged to the right edge: anchor pinned to the right edge (panel right edge aligned with
 * The right edge of the viewport remains fully visible without scrolling off the screen—this also holds true in zoomed-in mode—and the cumulative overshoot from continuing to drag to the right amounts to
 * The video is shifted entirely to the left (cropped on the left side); when scrolled to the end, the cursor just reaches the right column of the video; dragging to the left first causes the video to bounce back,
 * The panel moves to the left only after the drag ends.
 *
 * Push Limit = The translation required to translate the right edge of the viewport to the left edge of the panel: When not zoomed in, the right edge of the viewport is in the viewport
 * Right edge (exactly `controlWidth`, consistent with previous behavior); after zooming or translating, the right edge of the view may fall outside the viewport,
 * The upper limit automatically expands—as the mouse approaches the right edge, the screen continuously translates in, ensuring the cursor never translates off the screen.
 * (User feedback: When zoomed in, the mouse cursor moves off the screen immediately, which is not as expected.)
 *
 * @param rawAnchorX The cumulative anchor X value not constrained by the right edge (accumulated over the entire drag delta,
 *   Caller-side persistence—if overshoot is discarded by `clamp`, the overlap per frame is reduced to only the delta for that frame,
 *   (The video jitters in place and can never be pushed away: User feedback indicates it gets stuck at the right edge and the far-right column cannot be clicked.)
 * @param unpushedFrameRight The right edge of the screen when not pushed (screenFrame(controlArea=0).right)
 */
internal fun computeVirtualMousePush(
    rawAnchorX: Float,
    viewportWidth: Float,
    controlWidth: Float,
    unpushedFrameRight: Float = viewportWidth,
): VirtualMousePush {
    if (viewportWidth <= 0f || controlWidth <= 0f) return VirtualMousePush(0f, rawAnchorX)
    val maxAnchorX = (viewportWidth - controlWidth).coerceAtLeast(0f)
    val pushCap = (unpushedFrameRight - maxAnchorX).coerceAtLeast(0f)
    val cappedRaw = rawAnchorX.coerceAtMost(maxAnchorX + pushCap)
    return VirtualMousePush(
        overlapPx = (cappedRaw - maxAnchorX).coerceIn(0f, pushCap),
        anchorX = cappedRaw.coerceAtMost(maxAnchorX),
    )
}

/** Open the right-side control area when the controller touches the right edge of the screen to prevent the panel from obscuring the remote right edge. */
internal fun shouldDockVirtualMouseRight(
    anchorX: Float,
    controlWidth: Float,
    viewportWidth: Float,
): Boolean = anchorX + controlWidth >= viewportWidth
