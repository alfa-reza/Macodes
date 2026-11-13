package dev.termish.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.termish.data.Host
import dev.termish.screen.ScreenControlPacket
import dev.termish.screen.ScreenSession
import dev.termish.screen.ScreenUiState
import dev.termish.screen.ScreenVideoSurface
import dev.termish.ui.theme.ScreenControlDimens
import dev.termish.ui.theme.ScreenControlTokens
import dev.termish.ui.theme.Sizes
import dev.termish.ui.theme.StatusColors
import dev.termish.util.TermLog
import dev.termish.util.monospaceFontFamily
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Screen tab (remote screen): Fullscreen playback of H.264 streaming, black background + Fit scaling;
 * Permanent frame-rate/resolution badge in the upper-right corner; connection and error states match the terminal screen.
 */
@Composable
fun ScreenContent(
    host: Host,
    session: ScreenSession?,
    state: ScreenUiState,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    /** One-click installation when the service is missing (button on the guidance card). */
    onInstallService: (String?) -> Unit = { _ -> },
    /** Fullscreen mode: The back button in the top-left corner shows “Collapse” (without switching tabs); otherwise, it shows “Back.” */
    onClose: (() -> Unit)? = null,
    /** Status bar height (px, recorded before immersive mode hides it): header content shifts down, back button and terminal screen
     * Alignment with the tab bar (user feedback: the fullscreen back button is positioned higher than on the terminal screen). */
    statusBarInsetTop: Int = 0,
    /** Streaming parameter toggling (frame rate/image quality): Changes take effect after rebuilding the session following remote configuration. */
    onStreamConfigChange: (fps: Int, scale: String) -> Unit = { _, _ -> },
    /** Local updates to frame rate settings (UI feedback before async rebuild). */
    onFpsIndex: (Int) -> Unit = {},
    /** Whether the video surface uses a real SurfaceView (false during the expand animation; black placeholder—
     * When the container is scaled from a floating window size to fullscreen, the video surface does not scale properly on some devices,
     * a floating-window-sized “bright spot” / black screen where the picture freezes; the surface stays full-size once the animation ends) */
    videoEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val s = LocalAppStrings.current
    val density = LocalDensity.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // Remote keyboard toolbar (expand by clicking the ⌨ button in the lower-right corner): on-screen keyboard + a row of function keys.
    // Layout: Screen (weight 1f) + Toolbar + Hidden Input Field — imePadding places the toolbar
    // Position it above the keyboard; the top of the toolbar = the bottom of the screen (user requirement: do not cover the screen)
    var keyboardOpen by remember { mutableStateOf(false) }
    val keyFocus = remember { FocusRequester() }
    // Height when the keyboard is fully expanded (fixed value): the Spacer doesn't animate during the collapse animation,
    // The video does not jump back up after moving down with the animation (user feedback: it moves down first, then back up).
    var imeHeightFixed by remember { mutableStateOf(0) }
    // Prefetch (WindowInsets.ime is a @Composable property and cannot be accessed directly within a LaunchedEffect)
    val imeInsets = WindowInsets.ime
    // System keyboard visibility listener: When the soft keyboard is closed using its built-in close button or a gesture (IME height returns to zero),
    // Sync the toolbar closure to return it to its initial state (otherwise, the toolbar remains open and the video skips—user feedback).
    val imeHeightForSync = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeHeightForSync) {
        if (keyboardOpen && imeHeightForSync <= 0) {
            keyboardOpen = false
        }
    }
    // Zero-width space sentinel: TextField is never truly empty—the soft keyboard’s backspace key leaves no text when the text is empty
    // Changes and onValueChange are not triggered (existing characters in Mac input fields cannot be deleted—user feedback).
    // Sentinel ensures Backspace always has something to delete: delete sentinel → forward BACKSPACE + immediately restore
    // ⚠️ The selection must be after the sentinel (TextRange(1)): The TextFieldValue cursor is positioned at the beginning by default,
    // New input is inserted before the sentinel, and the end is incrementally truncated → Each time, only the sentinel is sent (the user sees “a space was sent”).
    var keyState by remember { mutableStateOf(TextFieldValue(KEY_SENTINEL, TextRange(KEY_SENTINEL.length))) }
    // Text preceding the start of an IME composition (compare it to the text displayed on the screen at the end of the composition to determine the incremental change—keyState during the composition)
    // (Updated to composition text; a direct diff would lose on-screen content: single letters/first Chinese characters are omitted.)
    var preComposeText by remember { mutableStateOf("") }
    // Sticky modifier keys (⌘/⌃/⌥/⇧—tapped to activate, remain active until the next key is pressed; tapped again to cancel)
    var activeMods by remember { mutableStateOf(0) }
    var capsOn by remember { mutableStateOf(false) }
    // Fullscreen gestures (single-finger translate + two-finger scale on the desktop): zoomScale zoom level, panOffset offset from the top-left corner of the screen
    var zoomScale by remember { mutableStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var videoViewportSize by remember { mutableStateOf(IntSize.Zero) }
    var virtualMouseOpen by remember { mutableStateOf(false) }
    var rightControlAreaPx by remember { mutableFloatStateOf(0f) }
    // Cumulative anchor X not constrained by the right boundary (uninitialized; syncs with the current anchor on the first drag):
    // Overshoot past the right edge must be persisted; otherwise only the current frame's delta is kept, and the video cannot be pushed left.
    // (User feedback: It gets stuck when dragged to the right edge; the cursor can’t reach the rightmost column of the video—alignment with the overall left-shift effect of ToDesk.)
    var virtualMouseRawX by remember { mutableFloatStateOf(Float.NaN) }
    var virtualMouseAnchorPosition by remember { mutableStateOf<Offset?>(null) }
    var virtualMouseLeftHeld by remember { mutableStateOf(false) }
    var virtualMouseHeldCursor by remember { mutableStateOf<ScreenPoint?>(null) }

    val mousePanelWidthPx = with(density) { ScreenControlDimens.MousePanelWidth.toPx() }
    val mousePanelHeightPx = with(density) { ScreenControlDimens.MousePanelHeight.toPx() }
    val mouseCursorPanelOffsetXPx = with(density) { ScreenControlDimens.MouseCursorPanelOffsetX.toPx() }
    val mouseCursorPanelOffsetYPx = with(density) { ScreenControlDimens.MouseCursorPanelOffsetY.toPx() }
    val mouseCloseSpacePx =
        with(density) {
            (ScreenControlDimens.MouseCloseGap + ScreenControlDimens.MouseCloseButton).toPx()
        }
    val mouseControlWidthPx = mouseCursorPanelOffsetXPx + mousePanelWidthPx + mouseCloseSpacePx
    val mouseControlHeightPx = mouseCursorPanelOffsetYPx + mousePanelHeightPx
    val mousePanelTopInsetPx = statusBarInsetTop + with(density) { Sizes.HeaderCompact.toPx() }
    // rightControlAreaPx is now a state updated in onMovePanel (gradual push)

    fun releaseVirtualMouseLeft() {
        if (!virtualMouseLeftHeld) return
        virtualMouseHeldCursor?.let { cursor ->
            state.controlSender?.invoke(
                ScreenControlPacket.TYPE_VIRTUAL_LEFT_UP,
                cursor.x,
                cursor.y,
                0,
            )
        }
        virtualMouseLeftHeld = false
        virtualMouseHeldCursor = null
        TermLog.i("screen") { "virtual mouse left drag ended" }
    }

    fun closeVirtualMouse() {
        releaseVirtualMouseLeft()
        if (!virtualMouseOpen) return
        virtualMouseOpen = false
        rightControlAreaPx = 0f
        virtualMouseRawX = Float.NaN
        virtualMouseAnchorPosition = null
        TermLog.i("screen") { "virtual mouse closed" }
    }

    LaunchedEffect(state.controlMode, state.connected) {
        if (!state.controlMode || !state.connected) closeVirtualMouse()
    }

    // Tap Ripple (Remote Operation Feedback): Press to trigger a rippling circle animation, similar to ToDesk/Sunflower.
    data class RippleFx(
        val x: Float,
        val y: Float,
        val id: Int,
    )

    var ripple by remember { mutableStateOf<RippleFx?>(null) }
    var rippleSeq by remember { mutableIntStateOf(0) }
    val rippleProgress = remember { Animatable(0f) }
    LaunchedEffect(ripple?.id) {
        if (ripple != null) {
            rippleProgress.snapTo(0f)
            rippleProgress.animateTo(1f, animationSpec = tween(450))
        }
    }

    /**
     * Touch point → Normalized screen coordinates (0–1): The video is shown centered using the “Fit” option (letterbox),
     * After applying local scaling and translating, this represents the actual display region—use this mapping in operation mode,
     * Coordinates match those on the remote screen regardless of streaming resolution or scale.
     */

    fun screenFrame(
        st: ScreenUiState,
        controlAreaPx: Float = rightControlAreaPx,
    ): ScreenRect {
        val size = videoViewportSize.takeIf { it.width > 0 && it.height > 0 } ?: viewportSize
        val vw = size.width.toFloat()
        val vh = size.height.toFloat()
        val dims = st.player?.videoDims?.value
        val dw = dims?.first?.toFloat() ?: vw
        val dh = dims?.second?.toFloat() ?: vh
        return fittedScreenRect(
            containerWidth = vw,
            containerHeight = vh,
            videoWidth = dw,
            videoHeight = dh,
            zoom = zoomScale,
            panX = panOffset.x - controlAreaPx,
            panY = panOffset.y,
            alignBottom = keyboardOpen && imeHeightFixed > 0,
        )
    }

    fun toScreenNorm(
        p: Offset,
        st: ScreenUiState,
    ): Pair<Float, Float> {
        val frame = screenFrame(st)
        if (frame.width <= 0f || frame.height <= 0f) return 0f to 0f
        val nx = (p.x - frame.left) / frame.width
        val ny = (p.y - frame.top) / frame.height
        return nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
    }

    LaunchedEffect(
        virtualMouseOpen,
        viewportSize,
        videoViewportSize,
        state.player?.videoDims?.value,
        zoomScale,
        panOffset,
    ) {
        if (!virtualMouseOpen || viewportSize.width <= 0 || viewportSize.height <= 0) return@LaunchedEffect
        val frame = screenFrame(state)
        val current = virtualMouseAnchorPosition
        val proposed =
            when {
                rightControlAreaPx > 0f && current != null -> ScreenPoint(frame.right, current.y)
                current != null -> ScreenPoint(current.x, current.y)
                else ->
                    ScreenPoint(
                        x = (viewportSize.width - mouseControlWidthPx) / 2f,
                        y = (viewportSize.height - mouseControlHeightPx) / 2f,
                    )
            }
        val clamped =
            clampVirtualMouseAnchor(
                proposed = proposed,
                frame = frame,
                viewportHeight = viewportSize.height.toFloat(),
                controlHeight = mouseControlHeightPx,
                topInset = mousePanelTopInsetPx,
                // Consistent with onMovePanel: the right edge of the panel aligns with the right edge of the viewport (fully visible, zoomed state)
                // (Similarly), avoid panel flickering caused by anchor jumps when the effect restarts
                viewportWidth =
                    (viewportSize.width.toFloat() - mouseControlWidthPx).coerceAtLeast(0f),
            )
        virtualMouseAnchorPosition = Offset(clamped.x, clamped.y)
        // After re-anchoring, sync back to rawX: maintain the formula overlap = rawX - maxAnchorX,
        // Otherwise, after the effect restarts (rotate/report first-frame size), the first drag causes rawX to drift and the video to jump.
        virtualMouseRawX = clamped.x + rightControlAreaPx
    }
    Column(
        modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { viewportSize = it },
        ) {
            // When docked on the right, simply shift the entire original-sized image to the left and crop the left side; the player container width remains unchanged,
            // Therefore, it will not be resized to fit; the freed-up black area on the right is reserved specifically for the virtual mouse.
            // ⚠️ Keep containers full-width and do not apply `clipToBounds`: portions that are out of bounds after left-alignment should be
            // clipped by the screen bounds, not by the Compose tree. “SurfaceView clipped by its ancestor” causes the surface content to be
            // scaled on some devices (user feedback: the picture shrinks when swiping right), so clip to the screen bounds with SurfaceView;
            // this is the most established path across all devices.
            val fullVideoWidthPx = viewportSize.width.coerceAtLeast(1).toFloat()
            val visibleVideoWidthPx = (fullVideoWidthPx - rightControlAreaPx).coerceAtLeast(1f)
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxHeight()
                    .width(with(density) { fullVideoWidthPx.toDp() }),
            ) {
                Box(
                    Modifier
                        .offset { IntOffset(-rightControlAreaPx.roundToInt(), 0) }
                        .fillMaxHeight()
                        .width(with(density) { fullVideoWidthPx.toDp() })
                        .onSizeChanged { videoViewportSize = it },
                ) {
                    if (videoEnabled) {
                        state.player?.let { p ->
                            ScreenVideoSurface(
                                p,
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        // Using the top-left corner as the scale origin: “translation” refers to the offset from the top-left corner of the screen; the bounds have the same meaning as “panOffset.”
                                        transformOrigin = TransformOrigin(0f, 0f)
                                        scaleX = zoomScale
                                        scaleY = zoomScale
                                        translationX = panOffset.x
                                        translationY = panOffset.y
                                    },
                                // The video only reaches the bottom once the keyboard is fully in place (sync with Spacer to avoid pop-up animations).
                                // (Pull down first, then push up—user feedback)
                                alignBottom = keyboardOpen && imeHeightFixed > 0,
                            )
                        }
                    }
                }
            }

            // Fullscreen gesture layer: View mode = single-finger translate + two-finger scale for the local view;
            // Operation mode = Touch → Mouse (single-finger left click/drag, two-finger scroll).
            // Declared before the connecting/error/header overlays: those are declared later and take touch precedence;
            // The gesture layer only accepts touches from regions it does not cover (i.e., the entire screen during normal play).
            if (onClose != null) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(with(density) { visibleVideoWidthPx.toDp() })
                        // ⚠️ `key` must contain both the `state` instance and `controlMode`: switching quality recreates
                        // `ScreenSession`/`ScreenUiState`. If the gesture coroutine is not restarted it keeps
                        // capturing the old state and sends touch events to the closed `controlSender`; the virtual mouse
                        // callback still works after recomposition, showing up as “finger input broken, mouse fine”.
                        .pointerInput(state, state.controlMode, videoViewportSize) {
                            if (state.controlMode) {
                                // Remote operation: single finger = left mouse button (down/drag/up); two-finger vertical swipe = scroll wheel.
                                // Coordinates are normalized to the screen display region; events are dispatched immediately without a queue (loss tolerance).
                                val slop = viewConfiguration.touchSlop
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    val sender = state.controlSender
                                    // Tap ripple feedback (ToDesk-style): an expanding circle at the press point
                                    val rippleId = (rippleSeq++)
                                    ripple = RippleFx(down.position.x, down.position.y, rippleId)
                                    var multi = false
                                    var moved = false
                                    var lastPos = down.position
                                    var lastN = toScreenNorm(down.position, state)
                                    // Two-finger gesture state: pinch distance (local scale) + center-of-mass vertical displacement (scroll wheel)
                                    var prevDist = -1f
                                    var prevCentroid = Offset.Zero
                                    var scrollAccum = 0f
                                    // Press = left-button down (tap = down+up click; drag = drag the canvas,
                                    // Do not mock mouse movement—user gesture definition: a single-finger swipe is simply a swipe.
                                    sender?.invoke(ScreenControlPacket.TYPE_DOWN, lastN.first, lastN.second, 0)
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.isEmpty()) {
                                            // Lift all = Left-click up (Tap = Tap; swiping to lift has no side effects)
                                            sender?.invoke(ScreenControlPacket.TYPE_UP, lastN.first, lastN.second, 0)
                                            break
                                        }
                                        if (pressed.size >= 2) {
                                            multi = true
                                            // Two-finger pinch = local zoom (center-of-mass anchored); when the distance remains stable
                                            // Two-finger centroid vertical swipe = scroll wheel (50px per row)
                                            val p1 = pressed[0].position
                                            val p2 = pressed[1].position
                                            val dist = (p2 - p1).getDistance()
                                            val centroid = (p1 + p2) / 2f
                                            if (prevDist > 0f) {
                                                val factor = dist / prevDist
                                                // Threshold 0.03: Natural finger jitter (<3%) does not count as a pinch.
                                                if (abs(factor - 1f) > 0.03f) {
                                                    // Pinch → Local scale: After scaling, tap the coordinate path
                                                    // toScreenNorm automatic mapping (including zoom/pan),
                                                    // Make the small button more precise (user feedback: it’s too small during operation)
                                                    val old = zoomScale
                                                    val new = (old * factor).coerceIn(1f, MAX_SCREEN_ZOOM)
                                                    if (new != old) {
                                                        panOffset = centroid - (centroid - panOffset) * (new / old)
                                                        zoomScale = new
                                                        if (videoViewportSize.width > 0 && videoViewportSize.height > 0) {
                                                            val maxX = videoViewportSize.width * (zoomScale - 1f)
                                                            val maxY = videoViewportSize.height * (zoomScale - 1f)
                                                            panOffset =
                                                                Offset(
                                                                    panOffset.x.coerceIn(-maxX, 0f),
                                                                    panOffset.y.coerceIn(-maxY, 0f),
                                                                )
                                                        }
                                                    }
                                                    scrollAccum = 0f
                                                } else {
                                                    // Centroid vertical displacement → scroll wheel
                                                    scrollAccum += centroid.y - prevCentroid.y
                                                    val delta = (scrollAccum / 50f).toInt()
                                                    if (delta != 0) {
                                                        sender?.invoke(ScreenControlPacket.TYPE_SCROLL, lastN.first, lastN.second, -delta)
                                                        scrollAccum -= delta * 50f
                                                    }
                                                }
                                            }
                                            prevDist = dist
                                            prevCentroid = centroid
                                            event.changes.forEach { it.consume() }
                                        } else {
                                            // Single finger: after slop = drag the canvas (pan the viewport; zoomed-in view to see
                                            // Elsewhere; when zoom=1, clamping to zero is naturally invalid). Do not mock the mouse
                                            // Gestures (User gesture definition: a single finger means swiping)
                                            if (multi) {
                                                multi = false
                                                moved = false
                                                lastPos = pressed[0].position
                                            }
                                            val p = pressed[0].position
                                            val d = p - lastPos
                                            if (!moved && d.getDistance() > slop) {
                                                moved = true
                                                lastPos = p
                                            }
                                            if (moved) {
                                                panOffset =
                                                    Offset(
                                                        panOffset.x + d.x,
                                                        panOffset.y + d.y,
                                                    )
                                                if (videoViewportSize.width > 0 && videoViewportSize.height > 0) {
                                                    val maxX = videoViewportSize.width * (zoomScale - 1f)
                                                    val maxY = videoViewportSize.height * (zoomScale - 1f)
                                                    panOffset =
                                                        Offset(
                                                            panOffset.x.coerceIn(-maxX, 0f),
                                                            panOffset.y.coerceIn(-maxY, 0f),
                                                        )
                                                }
                                                lastPos = p
                                                pressed[0].consume()
                                            }
                                        }
                                    }
                                }
                            } else {
                                detectTransformGestures(panZoomLock = true) { centroid, pan, zoom, _ ->
                                    val old = zoomScale
                                    val new = (old * zoom).coerceIn(1f, MAX_SCREEN_ZOOM)
                                    val effective = new / old
                                    // Two-finger center-of-gravity scale and translate: Content at the center of gravity remains stationary with incremental translation
                                    panOffset = centroid - (centroid - panOffset) * effective + pan
                                    zoomScale = new
                                    // Constrained within the viewport: When zoomed in, the view can only be translated to the bounds; when scale=1, it resets to zero.
                                    if (videoViewportSize.width > 0 && videoViewportSize.height > 0) {
                                        val maxX = videoViewportSize.width * (zoomScale - 1f)
                                        val maxY = videoViewportSize.height * (zoomScale - 1f)
                                        panOffset =
                                            Offset(
                                                panOffset.x.coerceIn(-maxX, 0f),
                                                panOffset.y.coerceIn(-maxY, 0f),
                                            )
                                    }
                                }
                            }
                        },
                )
            }

            // Tap the ripple layer (remote operation mode): Press the expanding circle at the specified location; it fades out with an animation.
            // Declare after the UI/gesture layer (overlaying it) and before error states/headers
            if (state.controlMode) {
                ripple?.let { r ->
                    Canvas(Modifier.fillMaxSize()) {
                        val p = rippleProgress.value
                        val radius = 14.dp.toPx() * (0.3f + p * 1.2f)
                        drawCircle(
                            color = Color.White.copy(alpha = (1f - p) * 0.85f),
                            radius = radius,
                            center = Offset(r.x, r.y),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }

            // Connecting: Centered indicator (includes the waiting state where a connection is established but the first frame has not yet arrived; also shows when the session is null in fullscreen mode)
            if (session == null || !state.connected || !state.videoReady) {
                ConnectingIndicator(
                    visible = state.error == null && !state.videoReady,
                    text = s.screen.connecting,
                    containerColor = Color(0xFF101216),
                    contentColor = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // Service missing: Guide user through one-click installation (including specific reason + installation log). At this point, uiState.error
            // Also configured—no longer renders generic error states, avoiding red error messages + a “Reconnect” button
            // Duplicates the “Install” operation (user feedback from v1.5.0)
            if (state.serviceMissing) {
                ScreenServiceGuide(
                    installing = state.installing,
                    installLog = state.installLog,
                    ffmpegMissing = state.ffmpegMissing,
                    needsSudoPassword = state.needsSudoPassword,
                    onInstall = onInstallService,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                // Error state: Card (same style as the install wizard: icon + text + primary/secondary buttons)
                state.error?.let { msg ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                // Error icon: Light-colored, container with corner radius (same as the installation guide)
                                Box(
                                    Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(StatusColors.Error.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.ErrorOutline,
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp),
                                        tint = StatusColors.Error,
                                    )
                                }
                                Text(
                                    msg,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                )
                                if (state.ffmpegMissing) {
                                    Text(
                                        s.screen.ffmpegHint,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                                Button(
                                    onClick = onReconnect,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(s.screen.reconnect)
                                }
                                TextButton(onClick = onBack) {
                                    Text(
                                        s.terminalCancel,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Screen status tooltip (screen off/locked, can be resumed): Small, semi-transparent text at the bottom center of the screen,
            // Auto-clears when the first frame arrives (ScreenSession onReady)
            state.screenHint?.let { hint ->
                Text(
                    hint,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            // Control tooltip for the controlled end: Linux control backend unavailable / macOS lacks accessibility permissions.
            if (state.controlMode && state.controlUnsupported) {
                Text(
                    s.screen.controlUnsupportedHint,
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 48.dp, start = 24.dp, end = 24.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFB45309).copy(alpha = 0.85f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            } else if (state.controlMode && state.controlPermissionMissing) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = ScreenControlDimens.PermissionBannerSide)
                            .padding(bottom = ScreenControlDimens.PermissionBannerBottom)
                            .clip(RoundedCornerShape(ScreenControlDimens.PermissionBannerCorner))
                            .background(ScreenControlTokens.PermissionBackground)
                            .padding(
                                horizontal = ScreenControlDimens.PermissionBannerHorizontal,
                                vertical = ScreenControlDimens.PermissionBannerVertical,
                            ),
                ) {
                    Text(
                        s.screen.controlPermissionHint,
                        color = ScreenControlTokens.PermissionText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onReconnect) {
                        Text(s.screen.reconnect, color = ScreenControlTokens.PermissionText)
                    }
                }
            }

            if (virtualMouseOpen && state.controlMode) {
                val frame = screenFrame(state)
                virtualMouseAnchorPosition?.let { anchor ->
                    val cursor =
                        cursorAtVirtualMouseAnchor(
                            anchor = ScreenPoint(anchor.x, anchor.y),
                            frame = frame,
                        )
                    val moveVirtualMouse: (Offset) -> ScreenPoint? = { delta ->
                        val current = virtualMouseAnchorPosition
                        if (current == null) {
                            null
                        } else {
                            val viewportRight = viewportSize.width.toFloat()
                            val maxAnchorX =
                                (viewportRight - mouseControlWidthPx).coerceAtLeast(0f)
                            val unpushedFrame = screenFrame(state, 0f)
                            // rawX accumulates delta throughout the entire process (not consumed by the right-edge clamp): exceeds maxAnchorX
                            // = video left shift. Push limit = the amount required to push the right edge of the picture to the left edge of the panel
                            // Translate: When not zoomed in, it is exactly the width of the panel; when zoomed in, the right edge of the view is outside the viewport, and the upper limit
                            // Auto-scaling — the screen continuously translates in as the mouse approaches the right edge, so the cursor never moves off the screen
                            val rawX =
                                (if (virtualMouseRawX.isNaN()) current.x else virtualMouseRawX) +
                                    delta.x
                            // Gradual push past the left/right bounds (symmetrically): the overshoot when the panel is dragged past the screen bounds is
                            // converted into a reverse horizontal translation — pushing right = the screen moves left (content on the right is squeezed in),
                            // Left push = screen shifts to the right (hidden content on the left is pushed out when zoomed in)
                            val leftEdge = unpushedFrame.left.coerceAtLeast(0f)
                            val overLeft = leftEdge - rawX
                            val anchorX: Float
                            val overlapPx: Float
                            if (overLeft > 0f && panOffset.x < 0f) {
                                val maxPanX =
                                    (videoViewportSize.width * (zoomScale - 1f)).coerceAtLeast(0f)
                                panOffset =
                                    Offset(
                                        (panOffset.x + overLeft).coerceIn(-maxPanX, 0f),
                                        panOffset.y,
                                    )
                                virtualMouseRawX = leftEdge
                                anchorX = leftEdge
                                overlapPx = 0f
                            } else {
                                val push =
                                    computeVirtualMousePush(
                                        rawX,
                                        viewportRight,
                                        mouseControlWidthPx,
                                        unpushedFrame.right,
                                    )
                                virtualMouseRawX =
                                    rawX.coerceIn(
                                        leftEdge,
                                        maxAnchorX + (unpushedFrame.right - maxAnchorX).coerceAtLeast(0f),
                                    )
                                anchorX = push.anchorX
                                overlapPx = push.overlapPx
                            }
                            rightControlAreaPx = overlapPx
                            val targetFrame = screenFrame(state, overlapPx)
                            val clamped =
                                clampVirtualMouseAnchor(
                                    proposed = ScreenPoint(anchorX, current.y + delta.y),
                                    frame = targetFrame,
                                    viewportHeight = viewportSize.height.toFloat(),
                                    controlHeight = mouseControlHeightPx,
                                    topInset = mousePanelTopInsetPx,
                                    viewportWidth = maxAnchorX,
                                )
                            virtualMouseAnchorPosition = Offset(clamped.x, clamped.y)
                            cursorAtVirtualMouseAnchor(clamped, targetFrame)
                        }
                    }
                    VirtualMouseCursor(
                        frame = frame,
                        cursor = cursor,
                    )
                    VirtualMousePanel(
                        position =
                            Offset(
                                anchor.x + mouseCursorPanelOffsetXPx,
                                anchor.y + mouseCursorPanelOffsetYPx,
                            ),
                        strings = s.screen,
                        onClose = ::closeVirtualMouse,
                        onLeftClick = {
                            state.controlSender?.invoke(
                                ScreenControlPacket.TYPE_CLICK,
                                cursor.x,
                                cursor.y,
                                0,
                            )
                            val hotspot = cursorHotspot(cursor, screenFrame(state))
                            ripple = RippleFx(hotspot.x, hotspot.y, rippleSeq++)
                            TermLog.i("screen") { "virtual mouse left click" }
                        },
                        onLeftDragStart = {
                            if (!virtualMouseLeftHeld) {
                                virtualMouseLeftHeld = true
                                virtualMouseHeldCursor = cursor
                                state.controlSender?.invoke(
                                    ScreenControlPacket.TYPE_VIRTUAL_LEFT_DOWN,
                                    cursor.x,
                                    cursor.y,
                                    0,
                                )
                                TermLog.i("screen") { "virtual mouse left drag started" }
                            }
                        },
                        onLeftDrag = { delta ->
                            moveVirtualMouse(delta)?.let { movedCursor ->
                                virtualMouseHeldCursor = movedCursor
                                state.controlSender?.invoke(
                                    ScreenControlPacket.TYPE_VIRTUAL_LEFT_DRAG,
                                    movedCursor.x,
                                    movedCursor.y,
                                    0,
                                )
                            }
                        },
                        onLeftDragEnd = ::releaseVirtualMouseLeft,
                        onRightClick = {
                            state.controlSender?.invoke(
                                ScreenControlPacket.TYPE_RIGHT_CLICK,
                                cursor.x,
                                cursor.y,
                                0,
                            )
                            TermLog.i("screen") { "virtual mouse right click" }
                        },
                        onScroll = { direction ->
                            state.controlSender?.invoke(
                                ScreenControlPacket.TYPE_SCROLL,
                                cursor.x,
                                cursor.y,
                                direction,
                            )
                            TermLog.i("screen") { "virtual mouse scroll direction=$direction" }
                        },
                        onMovePanel = { delta ->
                            moveVirtualMouse(delta)?.let { movedCursor ->
                                if (virtualMouseLeftHeld) {
                                    virtualMouseHeldCursor = movedCursor
                                    state.controlSender?.invoke(
                                        ScreenControlPacket.TYPE_VIRTUAL_LEFT_DRAG,
                                        movedCursor.x,
                                        movedCursor.y,
                                        0,
                                    )
                                }
                            }
                        },
                    )
                }
            }

            // Fullscreen header bar: Same style as the terminal screen tab bar (semi-transparent black background + Back IconButton + hostname +
            // (frame rate subscript), the back button is no longer exposed at the very top (user feedback: it should have the same height as the back button on the terminal screen),
            // (Fullscreen should have a header). ⚠️ Declared last (topmost): the video surface and the error state are both fillMaxSize overlays;
            // declared earlier they would be covered (v1.4.0 regression: invisible and untappable).
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .zIndex(100f)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    // Move content down to the status bar height (with the background filling the top): Align the back button with the terminal screen tab bar
                    .padding(top = with(density) { statusBarInsetTop.toDp() }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { (onClose ?: onBack)() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = if (onClose != null) s.screen.collapse else s.screen.back,
                        tint = Color.White.copy(alpha = 0.9f),
                    )
                }
                // Hostname (same position as the tab bar title)
                Text(
                    host.name.ifBlank { host.hostname },
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                // Mode Toggling: Default operation mode (touch → mouse + keyboard); can toggle to view-only mode (no keyboard)
                Text(
                    if (state.controlMode) s.screen.controlActive else s.screen.viewMode,
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (state.controlMode) Color(0xFF2563EB).copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.45f),
                            ).padding(horizontal = 8.dp, vertical = 5.dp)
                            .clickable {
                                // ⚠️ Do not reset local scale: After a user scales in, the operation mode should remain scaled in.
                                // Status (previously enforced zoom=1; user feedback: “It’s zoomed in from the start,”
                                // "The button has gotten smaller again")
                                state.controlMode = !state.controlMode
                            },
                )
                // Frame rate/image quality toggle button (top right): Tap to open the pop-up menu and select a setting,
                // How to enable: Configure the remote relay configuration + rebuild the streaming session
                StreamQualitySwitcher(
                    fps = state.streamFps,
                    quality = state.streamQuality,
                    videoDims = state.player?.videoDims?.value,
                    maxFps = state.decoderMaxFps,
                    deliveredFps = state.fps,
                    bitrateKbps = state.bitrateKbps,
                    jitterMillis = state.jitterMillis,
                    droppedPermille = state.droppedPermille,
                    onSelect = onStreamConfigChange,
                    onFpsIndex = { state.streamFps = it },
                    onQualityIndex = { state.streamQuality = it },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            // Keyboard button (bottom-right corner of the screen; shows when in operation mode and the keyboard is collapsed): Tap to show the system keyboard and toolbar.
            // navigationBarsPadding: Avoid being obscured by the bottom navigation bar (gesture bar).
            // View mode: no keyboard (view only)
            if (!keyboardOpen && state.controlMode) {
                Column(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(ScreenControlDimens.FloatingMargin),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!virtualMouseOpen) {
                        VirtualMouseButton(
                            contentDescription = s.screen.virtualMouse,
                            onClick = {
                                virtualMouseOpen = true
                                rightControlAreaPx = 0f
                                TermLog.i("screen") { "virtual mouse opened" }
                            },
                        )
                        Spacer(Modifier.height(ScreenControlDimens.FloatingSpacing))
                    }
                    Box(
                        Modifier
                            .size(ScreenControlDimens.FloatingButton)
                            .clip(RoundedCornerShape(ScreenControlDimens.FloatingButton))
                            .background(ScreenControlTokens.FloatingBackground)
                            .clickable {
                                closeVirtualMouse()
                                keyboardOpen = true
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "⌨",
                            color = Color.White,
                            fontSize = 20.sp,
                        )
                    }
                }
            }
        }

        // Remote Keyboard Toolbar (Shows in operation mode and when the keyboard pops up, above the keyboard; ⌨ is pinned to the far right and does not scroll)
        if (keyboardOpen && state.controlMode) {
            RemoteKeyBar(
                activeMods = activeMods,
                capsOn = capsOn,
                onKeyboardToggle = { keyboardOpen = false },
                onModToggle = { mod ->
                    activeMods = if (activeMods and mod != 0) activeMods and mod.inv() else activeMods or mod
                    // Tapping a toolbar button steals focus from the TextField: return focus immediately, otherwise subsequent
                    // Keyed-in letters have nowhere to go (user feedback: input doesn’t work after clicking the toolbar).
                    keyFocus.requestFocus()
                },
                onCapsToggle = {
                    capsOn = !capsOn
                    state.keySender?.invoke(ScreenControlPacket.KeyCode.CAPS, 0, "")
                    keyFocus.requestFocus()
                },
                onKey = { keyCode, mods ->
                    state.keySender?.invoke(keyCode, mods, "")
                    keyFocus.requestFocus()
                },
            )
            // Hidden input field: Bring up the on-screen keyboard when focus is gained; onValueChange diff → text/delete keys.
            // Do not send while composing (Chinese Pinyin); send the final text once it appears on screen (after composition is complete).
            Box(Modifier.fillMaxWidth().height(1.dp)) {
                val keySenderLocal = state.keySender
                BasicTextField(
                    value = keyState,
                    // Turn off auto-correction: Gboard English input no longer enters the composing state (otherwise, letters
                    // Backlog until a space is pressed to display on screen—user feedback indicates that input of letters has no effect), real-time letter-by-letter input
                    // Forwarding; the behavior of Chinese pinyin compositions remains unchanged (the composing state is not sent; the final text is displayed on screen).
                    // ⚠️ singleLine + imeAction: Pressing Enter in a multi-line TextField creates a line break (text
                    // \n) Do not trigger an action—user feedback indicates that pressing Enter does not work; pressing Enter after a single row = IME action
                    keyboardOptions =
                        KeyboardOptions(
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Go,
                        ),
                    singleLine = true,
                    // Soft keyboard Enter key: IME action (Go/Done/Next/line break) → Remote Return
                    keyboardActions =
                        KeyboardActions(
                            onAny = {
                                keySenderLocal?.invoke(ScreenControlPacket.KeyCode.RETURN, 0, "")
                                true
                            },
                        ),
                    onValueChange = { newState ->
                        val oldState = keyState
                        val composing = newState.composition != null
                        val sender = state.keySender ?: return@BasicTextField
                        val old = oldState.text
                        val new = newState.text
                        when {
                            composing && oldState.composition == null -> {
                                // Start of composition: Record the text before composition (used to calculate the incremental increment at the end of composition)
                                preComposeText = old
                                keyState = newState
                            }
                            composing -> {
                                // In compositions: Do not send intermediate Pinyin states; only update the status.
                                keyState = newState
                            }
                            !composing && oldState.composition != null -> {
                                // Composition complete: Displayed content = Final text – Text before composition (remove "sentinel").
                                // Chinese pinyin and English word compositions display multiple characters on the screen at once; diffing them one by one will result in characters being lost.
                                keyState = newState
                                val full = new.removePrefix(KEY_SENTINEL)
                                val pre = preComposeText.removePrefix(KEY_SENTINEL)
                                val committed = if (full.startsWith(pre)) full.removePrefix(pre) else full
                                if (committed.isNotEmpty()) {
                                    TermLog.i("screen") { "key commit: ${committed.length} chars" }
                                    sender(0, 0, committed)
                                    keyState = TextFieldValue(KEY_SENTINEL, TextRange(KEY_SENTINEL.length))
                                }
                            }
                            new.length > old.length -> {
                                // New characters (appended after the sentinel): Real-time character-by-character forwarding (no backlog of English characters)
                                keyState = newState
                                val added = new.substring(old.length)
                                TermLog.i("screen") { "key add: ${added.length} chars" }
                                sender(0, 0, added)
                            }
                            new.length < old.length -> {
                                if (!new.contains(KEY_SENTINEL)) {
                                    // Sentinel removed (press Backspace on an empty TextField): forwarding BACKSPACE + resume sentinel
                                    keyState = TextFieldValue(KEY_SENTINEL, TextRange(KEY_SENTINEL.length))
                                    sender(ScreenControlPacket.KeyCode.BACKSPACE, 0, "")
                                } else {
                                    keyState = newState
                                    val n = old.length - new.length
                                    repeat(n) { sender(ScreenControlPacket.KeyCode.BACKSPACE, 0, "") }
                                }
                            }
                            else -> keyState = newState
                        }
                    },
                    textStyle = TextStyle(fontSize = 1.sp, color = Color.Transparent),
                    cursorBrush = SolidColor(Color.Transparent),
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .focusRequester(keyFocus)
                            // Soft keyboard Backspace key: no change in `onValueChange` when the TextField is empty
                            // Does not trigger (cannot delete in Mac input fields—user feedback). IME’s
                            // KEYCODE_DEL travels through the KeyEvent channel; catch and forward directly
                            .onPreviewKeyEvent { event: KeyEvent ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace) {
                                    keySenderLocal?.invoke(ScreenControlPacket.KeyCode.BACKSPACE, 0, "")
                                    true
                                } else {
                                    false
                                }
                            },
                )
            }
            LaunchedEffect(keyboardOpen) {
                if (keyboardOpen) {
                    // Focus changes and input connections are established async; the direct use of `requestFocus` is often ignored.
                    // (Same handling for the terminal screen): Latency for a split second before explicitly bringing up the keyboard.
                    keyFocus.requestFocus()
                    delay(150)
                    keyboardController?.show()
                    // Record the height after the keyboard is fully expanded: Use a fixed value for `Spacer`,
                    // the viewport doesn't animate during the system-keyboard collapse animation (video doesn't follow)
                    delay(400)
                    imeHeightFixed = imeInsets.getBottom(density)
                } else {
                    imeHeightFixed = 0
                    keyboardController?.hide()
                }
            }
        }
        // Keyboard height adjustment (imePadding does not take effect under adjustNothing): When the keyboard pops up,
        // Place a placeholder to position the toolbar above the keyboard—the top of the toolbar = the bottom of the screen (without overlapping the screen).
        // Use a fixed height (recorded when the keyboard is fully expanded): the viewport doesn't animate during the collapse animation,
        // Keep the video in place; when the animation ends, return it directly to the center (user feedback: move it down first, then back up).
        if (keyboardOpen && imeHeightFixed > 0) {
            Spacer(Modifier.fillMaxWidth().height(with(LocalDensity.current) { imeHeightFixed.toDp() }))
        }
    }
}

/**
 * Remote keyboard toolbar: A single row that can be swiped horizontally.
 * ⌘/⌃/⌥/⇧ Sticky Modifiers (tap to activate highlighting; the next keystroke triggers it; tap again to cancel),
 * ⌘Q/⌘C/⌘V preset compositions; special keys retain their current sticky modifiers.
 */
@Composable
private fun RemoteKeyBar(
    activeMods: Int,
    capsOn: Boolean,
    onKeyboardToggle: () -> Unit,
    onModToggle: (Int) -> Unit,
    onCapsToggle: () -> Unit,
    onKey: (keyCode: Int, mods: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalAppStrings.current
    Row(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.9f))
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Horizontal swipeable key area (weight 1f, fills the entire area; scrolling applies only to the key area)
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteKey("⌘", activeMods and ScreenControlPacket.MOD_COMMAND != 0) {
                onModToggle(ScreenControlPacket.MOD_COMMAND)
            }
            RemoteKey("⌃", activeMods and ScreenControlPacket.MOD_CONTROL != 0) {
                onModToggle(ScreenControlPacket.MOD_CONTROL)
            }
            RemoteKey("⌥", activeMods and ScreenControlPacket.MOD_OPTION != 0) {
                onModToggle(ScreenControlPacket.MOD_OPTION)
            }
            RemoteKey("⇧", activeMods and ScreenControlPacket.MOD_SHIFT != 0) {
                onModToggle(ScreenControlPacket.MOD_SHIFT)
            }
            RemoteKey("⇪", capsOn, onClick = onCapsToggle)
            RemoteKey("⌘Q", false) { onKey(ScreenControlPacket.KeyCode.Q, ScreenControlPacket.MOD_COMMAND) }
            RemoteKey("⌘C", false) { onKey(ScreenControlPacket.KeyCode.C, ScreenControlPacket.MOD_COMMAND) }
            RemoteKey("⌘V", false) { onKey(ScreenControlPacket.KeyCode.V, ScreenControlPacket.MOD_COMMAND) }
            RemoteKey("tab", false) { onKey(ScreenControlPacket.KeyCode.TAB, activeMods) }
            RemoteKey("esc", false) { onKey(ScreenControlPacket.KeyCode.ESC, activeMods) }
            RemoteKey("⏎", false) { onKey(ScreenControlPacket.KeyCode.RETURN, activeMods) }
            RemoteKey("space", false) { onKey(ScreenControlPacket.KeyCode.SPACE, activeMods) }
            RemoteKey("⌫", false) { onKey(ScreenControlPacket.KeyCode.BACKSPACE, activeMods) }
            RemoteKey("↑", false) { onKey(ScreenControlPacket.KeyCode.UP, activeMods) }
            RemoteKey("↓", false) { onKey(ScreenControlPacket.KeyCode.DOWN, activeMods) }
            RemoteKey("←", false) { onKey(ScreenControlPacket.KeyCode.LEFT, activeMods) }
            RemoteKey("→", false) { onKey(ScreenControlPacket.KeyCode.RIGHT, activeMods) }
        }
        // ⌨ Fixed button (far right of the toolbar; does not scroll with the keypad): Tap to collapse the keyboard
        Box(
            Modifier
                .height(38.dp)
                .widthIn(min = 44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2563EB).copy(alpha = 0.85f))
                .clickable(onClick = onKeyboardToggle)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "⌨",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 17.sp,
            )
        }
    }
}

/** Remote keyboard keys (rounded blocks; highlighted in blue when active). */
@Composable
private fun RemoteKey(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(38.dp)
            .widthIn(min = 38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Color(0xFF2563EB).copy(alpha = 0.85f) else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** Toggle frame rates (30/60/120) and image quality levels: Top-right corner of the fullscreen header. */
@Composable
private fun StreamQualitySwitcher(
    fps: Int,
    quality: Int,
    /** The actual resolution decoded by the decoder; show this first to avoid discrepancies between the configuration settings and the actual streaming. */
    videoDims: Pair<Int, Int>?,
    /** Decoder frame-rate ceiling (0 = unknown, show all); hide tiers the decoder can't sustain. */
    maxFps: Int,
    deliveredFps: Int,
    bitrateKbps: Int,
    jitterMillis: Int,
    droppedPermille: Int,
    onSelect: (Int, String) -> Unit,
    /** Frame rate updates locally and immediately (the UI provides feedback before async rebuild—user feedback: clicking the frame rate menu has no effect). */
    onFpsIndex: (Int) -> Unit,
    onQualityIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalAppStrings.current
    val qualities =
        listOf(
            "960:-2" to s.screen.qualityLow,
            "1280:-2" to s.screen.qualityMid,
            "1920:-2" to s.screen.qualityHigh,
            "native" to s.screen.qualityUltra,
        )
    val fpsOptions = listOf(30, 60, 120).filter { maxFps <= 0 || it <= maxFps }
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier) {
        // Current Category Capsule (tap to expand menu)
        val qualityOrActualSize =
            videoDims?.let { (width, height) -> "$width×$height" }
                ?: qualities.getOrElse(quality) { qualities[1] }.second
        Text(
            "$fps fps · $qualityOrActualSize",
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
                    .clickable { menuOpen = true },
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            if (deliveredFps > 0 || bitrateKbps > 0) {
                DropdownMenuItem(
                    text = {
                        Text(
                            s.screen.streamDiagnostics(
                                deliveredFps,
                                bitrateKbps,
                                jitterMillis,
                                droppedPermille,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = {},
                    enabled = false,
                )
                HorizontalDivider()
            }
            // Frame Rate Group
            fpsOptions.forEach { f ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "$f fps",
                            fontWeight = if (f == fps) FontWeight.Bold else null,
                            color = if (f == fps) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        // Update locally immediately + rebuild remotely after writing configuration (mirroring the image quality menu)
                        onFpsIndex(f)
                        onSelect(f, qualities[quality].first)
                    },
                )
            }
            HorizontalDivider()
            // Image Quality Group: Ultra HD preserves more remote details, but the default remains Standard Definition to control bandwidth and encoding load.
            qualities.forEachIndexed { i, (scale, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            fontWeight = if (i == quality) FontWeight.Bold else null,
                            color = if (i == quality) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        // Ultra HD prioritizes text and fine lines: when switching directly from 120 fps to Ultra HD, first reduce to
                        // 60 fps. Users can still manually switch back to 120 fps later (which will increase the bitrate on the remote end).
                        val selectedFps = fpsForQualitySelection(fps, i)
                        onFpsIndex(selectedFps)
                        onQualityIndex(i)
                        onSelect(selectedFps, scale)
                    },
                )
            }
        }
    }
}

/** For "Ultra HD," prioritize clarity during the first resolution selection; there are no restrictions on other settings or subsequent manual frame rates selected by the user. */
internal fun fpsForQualitySelection(
    currentFps: Int,
    qualityIndex: Int,
): Int = if (qualityIndex == 3 && currentFps > 60) 60 else currentFps

/** Streaming Service Installation Guide Card: When a service is missing, guide users through one-click installation based on the specific reason (e.g., FFmpeg missing / service not running),
 * Real-time logging during installation; retain the end of the log after a failure to allow for retries. */
@Composable
internal fun ScreenServiceGuide(
    installing: Boolean,
    installLog: String,
    /** Reason for failure: true = ffmpeg is missing on the remote server; false = the service is not running (no port has a listener). */
    ffmpegMissing: Boolean,
    /** The relay version is too old (prompt for an upgrade rather than a first-time installation). */
    needsUpgrade: Boolean = false,
    /** Linux is missing ffmpeg, and sudo requires interactive password authentication. */
    needsSudoPassword: Boolean = false,
    onInstall: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalAppStrings.current
    Box(
        modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Icon: A light-colored, container with a corner radius in the theme color, for a more refined visual appearance
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Monitor,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    s.screen.serviceTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                // Specific reason + action description (replaces generic, lengthy text and provides more accurate info)
                Text(
                    when {
                        needsUpgrade -> s.screen.serviceUpgradeHint
                        ffmpegMissing -> s.screen.serviceHintFfmpeg
                        else -> s.screen.serviceHintNotRunning
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (installing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            s.screen.installingService,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Install real-time logging: Show the last 8 rows; visible when the process is hung and no new progress is being made.
                    if (installLog.isNotBlank()) {
                        Text(
                            installLog.lines().takeLast(8).joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = monospaceFontFamily(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(10.dp),
                        )
                    }
                } else {
                    val sudoPassword = remember { mutableStateOf("") }
                    if (needsSudoPassword) {
                        OutlinedTextField(
                            value = sudoPassword.value,
                            onValueChange = { sudoPassword.value = it },
                            label = { Text(s.screen.serviceSudoPasswordLabel) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            s.screen.serviceSudoPasswordHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Button(
                        onClick = {
                            onInstall(if (needsSudoPassword) sudoPassword.value else null)
                        },
                        enabled = !needsSudoPassword || sudoPassword.value.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(s.screen.installService)
                    }
                    // Last installation failure: error message + log tail (can be retried; the button is still there)
                    if (installLog.isNotBlank()) {
                        Text(
                            s.screen.installFailed,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            installLog.lines().takeLast(6).joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = monospaceFontFamily(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(10.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Maximum scale level for fullscreen view (upper limit for two-finger scale). */
private const val MAX_SCREEN_ZOOM = 4f

/** The input field’s sentinel character (zero-width space) is hidden: Ensure the Backspace key always has content to delete. */
private const val KEY_SENTINEL = "\u200B"

/** Default floating window size (dp). */
const val PIP_DEFAULT_W = 160f
const val PIP_DEFAULT_H = 100f

/** Scale range (dp). */
private const val PIP_MIN_W = 120f
private const val PIP_MIN_H = 75f
private const val PIP_MAX_W = 360f
private const val PIP_MAX_H = 240f

/**
 * Terminal screen (picture-in-picture): **Dragable + scale by dragging the bottom-right corner + ✕ to close**; tap to go fullscreen.
 * [drag]/[pipW]/[pipH] Held by the caller (remember within the session key block): When expanding/collapsing to fullscreen
 * Destroying and rebuilding this component will not reset its position or size (rememberSaveable is only resumed when the Activity is rebuilt,
 * Normal exit compositions are not saved—v1.4.0 regression: After returning from fullscreen mode, the floating window returns to its default size in the top-right corner.
 */
@Composable
fun ScreenPiP(
    state: ScreenUiState,
    onClick: () -> Unit,
    onClose: () -> Unit,
    /** Drag offset (persisted within the session). */
    drag: MutableState<Offset>,
    /** Window width (dp, preserved within the session). */
    pipW: MutableState<Float>,
    /** Window height (dp, preserved within the session). */
    pipH: MutableState<Float>,
    modifier: Modifier = Modifier,
    /** Canvas size (clipped bounds, px). */
    canvasSize: IntSize = IntSize.Zero,
) {
    var ownSize by remember { mutableStateOf(IntSize.Zero) }
    // Constrain the bounds to read the latest value without restarting the gesture: When scaling causes a size change, if pointerInput is
    // When `ownSize` is set as the key, it restarts and interrupts the ongoing drag gesture (v1.4.0 regression: scaling cannot be dragged).
    val ownSizeState = rememberUpdatedState(ownSize)
    val player = state.player
    val s = LocalAppStrings.current

    Box(
        modifier
            .offset { IntOffset(drag.value.x.roundToInt(), drag.value.y.roundToInt()) }
            .size(pipW.value.dp, pipH.value.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .onSizeChanged { ownSize = it }
            // Unified gesture handling: Single-finger drag to move (if the starting point is in the handle area at the bottom right, the size will be scaled);
            // Pinch with two fingers to scale up or down (v1.4.0 regression: the handle cannot be dragged + horizontal scale is intercepted by the system).
            // Do not use `detectDragGestures`: independent hand gestures will be intercepted by parent node events;
            // Two-finger scaling requires a custom gesture (`transform` cannot distinguish the handle's starting point).
            .pointerInput(canvasSize) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val sz0 = ownSizeState.value
                    val handlePx = 26.dp.toPx()
                    val p = down.position
                    // ✕ / fullscreen badge areas (top-left/top-right) get their own clickable; not part of the gesture layer
                    val iconPx = 24.dp.toPx()
                    if ((p.x < iconPx && p.y < iconPx) || (p.x > sz0.width - iconPx && p.y < iconPx)) {
                        return@awaitEachGesture
                    }
                    // Single finger starting in the bottom-right handle area (26dp) → single-finger drag scales
                    var resizing = p.x >= sz0.width - handlePx && p.y >= sz0.height - handlePx
                    var moved = false
                    var multiTouch = false
                    var total = Offset.Zero
                    var prevDist = -1f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            // Lift all: no movement and no multiple fingers → Tap to enter fullscreen mode. Replace "video face" with "clickable":
                            // "clickable" receives the event before this gesture; a two-finger pinch-to-zoom may be mistakenly interpreted as a tap (v1.4.0)
                            if (!moved && !multiTouch) onClick()
                            break
                        }
                        if (pressed.size >= 2) {
                            multiTouch = true
                            // Two-finger: Pinch to scale up/down. Use the center of mass of the two fingers as the anchor (the absolute position of the center of mass remains fixed,
                            // The window expands toward the finger—User Feedback Center noted “strange expansion direction”)
                            val p1 = pressed[0].position
                            val p2 = pressed[1].position
                            val dist = (p2 - p1).getDistance()
                            if (prevDist > 0f && dist > 0f) {
                                val zoom = dist / prevDist
                                val oldW = pipW.value
                                val oldH = pipH.value
                                val oldWpx = oldW * density
                                val oldHpx = oldH * density
                                // Relative position of the center of mass within the floating window (content at the center of mass remains stationary before and after scaling)
                                val centroid = (p1 + p2) / 2f
                                val rx = (centroid.x / oldWpx).coerceIn(0f, 1f)
                                val ry = (centroid.y / oldHpx).coerceIn(0f, 1f)
                                pipW.value = (oldW * zoom).coerceIn(PIP_MIN_W, PIP_MAX_W)
                                pipH.value = (oldH * zoom).coerceIn(PIP_MIN_H, PIP_MAX_H)
                                val newWpx = pipW.value * density
                                val newHpx = pipH.value * density
                                // Center-of-Mass Anchoring: Top-left corner = Absolute position of center of mass - Relative position × New size
                                drag.value =
                                    Offset(
                                        drag.value.x + centroid.x - rx * newWpx,
                                        drag.value.y + centroid.y - ry * newHpx,
                                    )
                                // After scaling, keep the content within the canvas (to prevent it from being dragged off the screen).
                                // ⚠️ Margin must be preserved for conditional protection: when maxDown ∈ (0, 4)
                                // coerceIn(0, maxDown-4) will throw an exception and crash if min > max (user feedback)
                                if (canvasSize.width > 0) {
                                    val maxLeft = (canvasSize.width - newWpx).toFloat()
                                    val maxDown = (canvasSize.height - newHpx).toFloat()
                                    if (maxLeft > 4f && maxDown > 4f) {
                                        drag.value =
                                            Offset(
                                                drag.value.x.coerceIn(-maxLeft + 4f, 0f),
                                                drag.value.y.coerceIn(0f, maxDown - 4f),
                                            )
                                    }
                                }
                                event.changes.forEach { it.consume() }
                            }
                            prevDist = dist
                        } else {
                            val change = pressed[0]
                            val delta = change.position - change.previousPosition
                            total += delta
                            if (!moved && total.x * total.x + total.y * total.y > slop * slop) moved = true
                            if (moved) {
                                if (resizing) {
                                    // Single-finger handle: delta is in px, pip size is in dp; convert by dividing by density
                                    val oldW = pipW.value
                                    val oldH = pipH.value
                                    pipW.value = (oldW + delta.x / density).coerceIn(PIP_MIN_W, PIP_MAX_W)
                                    pipH.value = (oldH + delta.y / density).coerceIn(PIP_MIN_H, PIP_MAX_H)
                                    // Center-anchored: Consistent with two-finger scale (center remains fixed)
                                    drag.value =
                                        Offset(
                                            drag.value.x - (pipW.value - oldW) / 2f * density,
                                            drag.value.y - (pipH.value - oldH) / 2f * density,
                                        )
                                } else {
                                    // Movement: Confined to the canvas (starts at the top-right corner; can move anywhere within the canvas)
                                    val sz = ownSizeState.value
                                    if (canvasSize.width > 0 && sz.width > 0) {
                                        val margin = 4f
                                        // Initial position = top-right corner of the canvas (caller’s TopEnd + padding):
                                        // Align left to the left edge and down to the bottom of the canvas; do not align right or up (already flush with the edge).
                                        val maxLeft = (canvasSize.width - sz.width).toFloat()
                                        val maxDown = (canvasSize.height - sz.height).toFloat()
                                        if (maxLeft > margin && maxDown > margin) {
                                            drag.value =
                                                Offset(
                                                    (drag.value.x + delta.x).coerceIn(-maxLeft + margin, 0f),
                                                    (drag.value.y + delta.y).coerceIn(0f, maxDown - margin),
                                                )
                                        }
                                    }
                                }
                                change.consume()
                            }
                        }
                    }
                }
            },
    ) {
        if (player != null) {
            // No "clickable": Tap = fullscreen, determined uniformly by parent gestures (tap events are received before gesture events,
            // (A two-finger pinch-to-zoom gesture may occasionally be misinterpreted as a fullscreen tap—reintroduced in v1.4.0)
            ScreenVideoSurface(player, Modifier.fillMaxSize())
        } else if (!state.serviceMissing && state.error == null) {
            // Loading (connecting/waiting for first frame): display-only; tap to enter fullscreen for details
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "…",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                )
            }
        }
        // Service missing: hint in the floating window center (display-only; tap to enter fullscreen for the full install guide/buttons).
        // Provide specific reasons (e.g., ffmpeg missing, outdated relay version, service not running) and show progress during installation.
        if (state.serviceMissing) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 6.dp),
                ) {
                    Icon(
                        Icons.Filled.Monitor,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        when {
                            state.relayNeedsUpgrade -> s.screen.serviceUpgradeHint
                            state.ffmpegMissing -> s.screen.serviceHintFfmpeg
                            else -> s.screen.pipNeedInstall
                        },
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    if (state.installing) {
                        Text(
                            s.screen.installingService,
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        } else {
            // Error tooltips (connection failure/decoding failure/first frame timeout, etc.): Display only; tap to enter fullscreen mode and reconnect
            state.error?.let { msg ->
                Box(
                    Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = Color(0xFFFF6B6B),
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            msg,
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        // Screen status tooltips (screen off/locked/unstable network connection, can be resumed): a small bar at the top of the floating window.
        // Offset from ✕/fullscreen badges (32dp horizontal padding); auto-clears when the first frame arrives
        state.screenHint?.let { hint ->
            Text(
                hint,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 4.dp, start = 32.dp, end = 32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        // Close button (top-left corner): ✕ Closes the screen session. Icon with corner radius and semi-transparent capsule background + centered icon,
        // Same style as the fullscreen page back button (black 0.55 / corner radius 8dp / white 0.9)
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(5.dp)
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = s.settingsClose,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(16.dp),
            )
        }

        // Fullscreen badge (top-right): tap = fullscreen (same as tapping the video), same capsule background as the close button
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Fullscreen,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(16.dp),
            )
        }

        // Scale handle in the lower-right corner (visual): Scaling the window by dragging is handled uniformly via the "floating window" gesture—
        // The starting point falls within this 26dp region, which triggers scale (independent gestures are preempted by parent node events).
        // Exclude system-triggered gestures: When your finger is near the right edge of the screen, horizontal scaling is interrupted by the edge-triggered gesture.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(26.dp)
                .excludeSystemBackGesture(),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val p =
                    Path().apply {
                        moveTo(size.width, size.height)
                        lineTo(size.width - 13f, size.height)
                        lineTo(size.width, size.height - 13f)
                        close()
                    }
                drawPath(p, Color.White.copy(alpha = 0.55f))
            }
        }
    }
}
