package dev.termish.screen

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.viewinterop.AndroidView
import dev.termish.util.TermLog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Cache decoding capability detection (resolution → maximum frame rate); static reuse eliminates the need for re-checking per session. */
private val decoderProbeCache = HashMap<String, Int>()

/** H.264 decoding MIME (shared for detection and decoding). */
private const val DECODER_MIME = "video/avc"
private const val ANDROID_SOFTWARE_AVC_DECODER = "c2.android.avc.decoder"
private const val ANDROID_EMULATOR_MAX_FPS = 30

private fun isAndroidEmulator(): Boolean =
    Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
        Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
        Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
        Build.HARDWARE.contains("ranchu", ignoreCase = true)

/**
 * Android Implementation: Complete H.264 frames (Annex-B, starting with AUD) obtained through explicit TCP framing are fed directly
 * MediaCodec hardware decoding, with decoded output displayed immediately on screen—no MPEG-TS container, no local HTTP,
 * No ExoPlayer progressive buffering (those three combined result in ~0.4–1s delay; ToDesk-style pass-through
 * The pipeline reduces latency to just network + decoding + one vsync).
 *
 * The relay side has undergone alignment with AUD frames, and the TCP length header preserves the frame bounds; the next IDR can automatically
 * Resume decoding sync; there is no longer any TS container buffering.
 *
 * ⚠️ Lesson learned: A hand-coded MediaCodec pipeline once failed to output frames on OPPO/MTK devices (at the time
 * (From TS stream to NAL feed). This version avoids: explicit CSD configure (SPS/PPS from keyframe blocks
 * Extract) + Start playback from IDR + Independent thread sync mode + Input-handling watchdog checkpoints.
 */
actual class ScreenPlayer actual constructor(
    targetFps: Int,
    private val onReady: () -> Unit,
    private val onError: (ScreenPlayerFailure) -> Unit,
) {
    val decoder = ScreenDecoder(targetFps, onReady, onError)

    /** Actual video dimensions (reported by the decoder): UI aspect ratio layout + remote operation coordinate mapping. */
    actual val videoDims: MutableState<Pair<Int, Int>?> = decoder.videoDims

    actual val lastRenderedAtMillis: Long
        get() = decoder.lastRenderedAtMillis

    actual val renderSurfaceAttached: Boolean
        get() = decoder.hasSurface()

    actual fun start() {
        decoder.start()
    }

    actual fun feed(data: ByteArray) {
        decoder.feedFrame(data)
    }

    actual fun stop() {
        decoder.stop()
    }

    actual fun metrics(): ScreenPlayerMetrics = decoder.metrics()
}

/**
 * MediaCodec H.264 Decoder (separate decoding thread + bounded frame queue).
 *
 * State Machine (All codec operations occur on the decoding thread; the UI only reads and writes thread-safe fields):
 * 1. [start] Start a thread; wait for surface ([attachSurface], SurfaceView callback injection)
 * 2. Discard non-keyframes from the queue until the first keyframe containing SPS/PPS → configure → start playback
 * 3. One input buffer per frame; output is rendered immediately (displayed as soon as it arrives).
 * 4. SPS changes (resolution toggling) → reconfigure; surface destruction → release, rebuild next keyframe
 *
 * Queue level: Decoding can’t keep up (120 fps input, 60 fps decoding) – discard the oldest, keep the newest. Latency is locked at
 * 2–3 frames; if a reference frame is dropped, the screen flickers until the next IDR (keyint=30 ≤ 0.5s), at which point it automatically resumes.
 */
class ScreenDecoder(
    targetFps: Int,
    private val onReady: () -> Unit,
    private val onError: (ScreenPlayerFailure) -> Unit,
) {
    companion object {
        private const val MIME = "video/avc"
        private const val QUEUE_CAPACITY = 4
        private const val TIMEOUT_US = 10_000L
    }

    private val queue = LinkedBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private val configuredFps = targetFps.coerceIn(1, 120)
    private val ptsStepUs = 1_000_000L / configuredFps
    private val receivedFrames = AtomicLong()
    private val renderedFrames = AtomicLong()
    private val droppedFrames = AtomicLong()
    private val decoderBusyFrames = AtomicLong()

    /** Render frame count (resets after each diagnostic timestamp). */
    @Volatile private var statRendered = 0

    /** Actual video dimensions (after clipping; reported via the decoder’s output format; UI layout based on aspectRatio). */
    val videoDims: MutableState<Pair<Int, Int>?> = mutableStateOf(null)

    @Volatile private var running = false

    @Volatile private var firstFrameRendered = false

    @Volatile var lastRenderedAtMillis = 0L
        private set
    private var thread: Thread? = null

    /**
     * Currently active surfaces (the floating window and fullscreen modes are two independent `SurfaceView` instances that may exist simultaneously):
     * (e.g., during the fullscreen collapse animation, when fullscreen mode overlays a floating window, etc.). Use lists instead of single values—
     * Otherwise, a fullscreen `surfaceDestroyed` event would also clear the reference to the floating window; after the decoder is rebuilt, the screen
     * freezes on the last frame before reaching the surface (user feedback: the floating window is frozen).
     */
    private val surfaces = CopyOnWriteArrayList<Surface>()

    fun attachSurface(surface: Surface) {
        if (surface.isValid && !surfaces.contains(surface)) surfaces.add(surface)
    }

    /** Remove only the specified surface (floating windows and fullscreen modes are destroyed independently and do not affect each other). */
    fun detachSurface(surface: Surface) {
        surfaces.remove(surface)
    }

    fun hasSurface(): Boolean = surfaces.any { it.isValid }

    private fun currentSurface(): Surface? {
        // The `Surface` may be obsolete, and the `surfaceDestroyed` callback has not yet been received from the UI thread.
        // First, remove invalid references to prevent MediaCodec from continuing to render to a destroyed SurfaceView.
        surfaces.removeAll { !it.isValid }
        return surfaces.lastOrNull()
    }

    private fun isSurfaceFailure(
        surface: Surface?,
        error: Exception,
    ): Boolean {
        val detail = error.message.orEmpty()
        return surface?.isValid != true ||
            detail.contains("obsolete", ignoreCase = true) ||
            detail.contains("non-initialized", ignoreCase = true) ||
            detail.contains("abandoned", ignoreCase = true)
    }

    fun start() {
        if (running) return
        running = true
        thread =
            Thread({ decodeLoop() }, "screen-decoder").apply {
                priority = Thread.MAX_PRIORITY - 1
                start()
            }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    /** Feed in a complete frame (Annex-B bytes; the TCP protocol layer ensures frame alignment). */
    fun feedFrame(frame: ByteArray) {
        if (!running) return
        receivedFrames.incrementAndGet()
        // Discard the oldest, keep the newest: When the buffer is full, discard the oldest frame (do not backpressure the sender—latency takes precedence over continuity).
        while (!queue.offer(frame)) {
            if (queue.poll() == null) break
            droppedFrames.incrementAndGet()
        }
    }

    fun metrics(): ScreenPlayerMetrics =
        ScreenPlayerMetrics(
            receivedFrames = receivedFrames.get(),
            renderedFrames = renderedFrames.get(),
            droppedFrames = droppedFrames.get(),
            decoderBusyFrames = decoderBusyFrames.get(),
            queueDepth = queue.size,
        )

    private fun decodeLoop() {
        var codec: MediaCodec? = null
        var lastDims: Pair<Int, Int>? = null
        // The output surface bound to the current codec (comparison of changes when toggling between a floating window and fullscreen mode)
        var boundSurface: Surface? = null
        // Latest parameter-set cache: use the cached CSD to configure immediately after a surface switch (rebuild), without relying on the
        // remote repeating SPS/PPS at keyframes (some remotes never repeat → permanent black screen after a rebuild; switch channel and restart
        // the stream as a workaround — user feedback). Overwrite with the new parameter set when SPS changes.
        var cachedSps: ByteArray? = null
        var cachedPps: ByteArray? = null
        var cachedDims: Pair<Int, Int>? = null
        // After rebuilding with the cached parameter set, feed only IDR frames: P frames depend on prior reference frames and produce no image for a new decoder
        var needIdr = false
        var pts = 0L
        var fed = 0
        var dropEvery = 1 // Frame skipping: 1 = no skipping; n = skip 1 frame every n frames (automatically increases when the decoder is full)
        var dropCounter = 0
        // Diagnostics (Sampling per second: input/rendering/input full/frame skipping—identifying decoding throughput bottlenecks)
        var lastStatAt = 0L
        var statFed = 0
        var statFull = 0
        var statSkipped = 0

        fun releaseCodec() {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            codec = null
            boundSurface = null
        }

        fun configureCodec(
            surface: Surface,
            sps: ByteArray?,
            pps: ByteArray?,
            dims: Pair<Int, Int>?,
        ): Boolean {
            val selection =
                try {
                    createDecoder()
                } catch (e: Exception) {
                    TermLog.w("screen") { "decoder creation failed: $e" }
                    onError(ScreenPlayerFailure.Initialization(e.message))
                    running = false
                    return false
                }
            val fmt =
                MediaFormat.createVideoFormat(MIME, dims?.first ?: 1920, dims?.second ?: 1080).apply {
                    if (sps != null) setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(sps))
                    if (pps != null) setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(pps))
                    // Enable only when the codec explicitly declares support; otherwise some vendor implementations enter an exceptional
                    // buffering strategy after accepting the key-value pair.
                    if (selection.lowLatency) {
                        runCatching { setInteger(MediaFormat.KEY_LOW_LATENCY, 1) }
                    }
                    runCatching { setInteger(MediaFormat.KEY_FRAME_RATE, configuredFps) }
                    // KEY_OPERATING_RATE should use the actual streaming frame rate, not the upper limit of decoding capacity.
                    val cap = probeDecoderMaxFps(dims?.first ?: 1920, dims?.second ?: 1080)
                    val operatingRate = if (cap > 0) minOf(configuredFps, cap) else configuredFps
                    runCatching { setInteger(MediaFormat.KEY_OPERATING_RATE, operatingRate) }
                }
            var candidate: MediaCodec? = null
            return try {
                candidate = selection.codec
                candidate.configure(fmt, surface, null, 0)
                candidate.start()
                codec = candidate
                lastDims = dims
                fed = 0
                TermLog.i("screen") {
                    "decoder configured ${dims?.first}x${dims?.second} " +
                        "fps=$configuredFps codec=${selection.name} lowLatency=${selection.lowLatency}"
                }
                true
            } catch (e: Exception) {
                runCatching { candidate?.stop() }
                runCatching { candidate?.release() }
                codec = null
                if (isSurfaceFailure(surface, e)) {
                    TermLog.i("screen") { "surface 在解码器配置期间失效，等待新 surface" }
                } else {
                    TermLog.w("screen") { "decoder configure failed: $e" }
                    onError(ScreenPlayerFailure.Initialization(e.message))
                    running = false
                }
                false
            }
        }

        try {
            while (running) {
                // surface Lifecycle (Revised August 2026: All instances will be rebuilt; seamless rebinding will no longer be supported):
                // setOutputSurface is unreliable on physical devices—it returns success but produces no output, and boundSurface has already
                // been updated, so there is no chance to rebuild → entering fullscreen a second time leaves a permanent black screen; switching
                // frame rate/quality only recovers after an SPS reconfiguration (user feedback). The cost is ≤ one keyframe interval
                // (keyint ≈ 0.5s): the black screen is brief but it recovers—correctness wins over seamless rebinding.
                // - Destroy all (list is empty) → Release the codec
                // - Toggle (s ≠ boundSurface) → Release; rebuild the next keyframe using currentSurface
                //   (SurfaceView callbacks only remove their own surfaces; codec operations are confined to the current thread.)
                // Local snapshot: `codec` is set to null within the `releaseCodec` closure; `val` must be retrieved before checking for null.
                val sc = codec
                if (sc != null) {
                    val s = currentSurface()
                    if (s !== boundSurface || boundSurface?.isValid != true) {
                        TermLog.i("screen") { "surface 变化（$boundSurface → $s），释放解码器待重建" }
                        releaseCodec()
                        needIdr = true
                    }
                }

                val frame = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val ps = H264Stream.extractParameterSets(frame)
                val dims = ps.sps?.let { H264Stream.parseSpsDimensions(it) }

                if (ps.sps != null && ps.pps != null) {
                    // Keyframes with a complete set of parameters: cache + initial configure / reconfiguration due to resolution changes
                    cachedSps = ps.sps
                    cachedPps = ps.pps
                    cachedDims = dims
                    if (codec != null && dims != null && dims != lastDims) {
                        TermLog.i("screen") { "SPS 变化 $lastDims → $dims，重配解码器" }
                        releaseCodec()
                    }
                    if (codec == null) {
                        val surface = currentSurface() ?: continue // etc. UI surface
                        boundSurface = surface
                        if (!configureCodec(surface, ps.sps, ps.pps, dims)) {
                            if (running) continue else return
                        }
                        needIdr = false // The current frame is the IDR; it can be fed directly.
                    }
                } else {
                    if (codec == null && cachedSps != null && cachedPps != null) {
                        // Surface rebuild (cache path): no dependency on the remote repeating SPS/PPS at keyframes,
                        // Configure immediately using the cached parameter set; wait for the next IDR (≤0.5s) to display the screen
                        val surface = currentSurface() ?: continue // etc. UI surface
                        boundSurface = surface
                        TermLog.i("screen") { "surface 变化后用缓存参数集重建解码器" }
                        if (!configureCodec(surface, cachedSps, cachedPps, cachedDims)) {
                            if (running) continue else return
                        }
                        needIdr = true
                    }
                }

                val c = codec ?: continue // No decoder (no parameter set and no cache): Wait for keyframes
                if (needIdr && !H264Stream.containsIdr(frame)) {
                    // Discard non-IDR frames before playback: P frames cannot be used as the start frame for a new decoder.
                    if (!renderOutputs(c, boundSurface)) {
                        releaseCodec()
                        needIdr = true
                    }
                    continue
                }
                needIdr = false
                fed++
                // Adaptive frame skipping: When the input exceeds the decoding limit, skip frames with a ratio (to maintain a consistent rhythm).
                // Queue frame drops are caused by “drop-old-keep-new” rhythm jitter (tested: 60 fps stream, MTK decoding at 35 fps →
                // (one fast, one slow); frames are discarded evenly before feeding in, ensuring a smooth rhythm.
                // Input is busy (cannot access the buffer) → frame rate increases; continuous and smooth → slowly drops
                val idx = c.dequeueInputBuffer(TIMEOUT_US)
                if (idx < 0) {
                    // Decoder full: Discard this frame + increase frame-dropping intensity (drop 1 out of the next n frames)
                    dropEvery = (dropEvery + 1).coerceAtMost(4)
                    dropCounter = 0
                    statFull++
                    decoderBusyFrames.incrementAndGet()
                    droppedFrames.incrementAndGet()
                    if (!renderOutputs(c, boundSurface)) {
                        releaseCodec()
                        needIdr = true
                    }
                    continue
                }
                if (dropEvery > 1) {
                    dropCounter++
                    if (dropCounter % dropEvery != 0) {
                        // Frames skipped: Placeholder release of input buffer (no data provided)
                        c.queueInputBuffer(idx, 0, 0, pts, 0)
                        statSkipped++
                        droppedFrames.incrementAndGet()
                        if (!renderOutputs(c, boundSurface)) {
                            releaseCodec()
                            needIdr = true
                        }
                        continue
                    }
                    if (dropCounter >= dropEvery * 8) dropCounter = 0
                    // 8 consecutive smooth rounds: lower the frame-dropping rate (dropEvery--)
                    if (dropCounter == 0 && dropEvery > 1) dropEvery--
                }
                statFed++
                val buf = c.getInputBuffer(idx)
                if (buf != null && frame.size <= buf.capacity()) {
                    buf.clear()
                    buf.put(frame)
                    c.queueInputBuffer(idx, 0, frame.size, pts, 0)
                    pts += ptsStepUs
                } else {
                    c.queueInputBuffer(idx, 0, 0, pts, 0)
                }
                if (!renderOutputs(c, boundSurface)) {
                    releaseCodec()
                    needIdr = true
                    continue
                }

                // Diagnostics metrics per second: fed/rendered/input-full/skipped—troubleshooting on the physical device
                // “Decoding can’t keep up” is a hardware limitation or a pipeline bottleneck (a flagship device's 1080p hardware decoding
                // performance far exceeds 30 fps; actual tests show only 25–30, which is most likely due to buffering or configuration issues).
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastStatAt >= 1000) {
                    val rendered = statRendered
                    statRendered = 0
                    TermLog.i("screen") {
                        "decoder: fed=$statFed rendered=$rendered " +
                            "inputFull=$statFull skipped=$statSkipped dropEvery=$dropEvery queue=${queue.size}"
                    }
                    lastStatAt = nowMs
                    statFed = 0
                    statRendered = 0
                    statFull = 0
                    statSkipped = 0
                }

                // ⚠️ MTK input-hungry watchdog: Fed 600 frames but still zero output → Reported (historical OPPO/MTK issue)
                if (!firstFrameRendered && fed > 600) {
                    TermLog.w("screen") { "解码器吞输入：fed=$fed 无输出" }
                    onError(ScreenPlayerFailure.NoOutput(fed))
                    running = false
                    break
                }
            }
        } catch (e: InterruptedException) {
            // stop() Interrupt: Exit normally
        } catch (e: Exception) {
            TermLog.w("screen") { "decode loop error: ${e::class.simpleName} ${e.message}" }
            onError(ScreenPlayerFailure.Decoding(e.message ?: e::class.simpleName.orEmpty()))
        } finally {
            releaseCodec()
        }
    }

    private fun renderOutputs(
        codec: MediaCodec,
        surface: Surface?,
    ): Boolean {
        val info = MediaCodec.BufferInfo()
        return try {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return true
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        TermLog.i("screen") { "decoder format: $f" }
                        val dims =
                            runCatching {
                                val w =
                                    if (f.containsKey("crop-right") && f.containsKey("crop-left")) {
                                        f.getInteger("crop-right") + 1 - f.getInteger("crop-left")
                                    } else {
                                        f.getInteger(MediaFormat.KEY_WIDTH)
                                    }
                                val h =
                                    if (f.containsKey("crop-bottom") && f.containsKey("crop-top")) {
                                        f.getInteger("crop-bottom") + 1 - f.getInteger("crop-top")
                                    } else {
                                        f.getInteger(MediaFormat.KEY_HEIGHT)
                                    }
                                w to h
                            }.getOrNull()
                        if (dims != null) videoDims.value = dims
                    }
                    idx >= 0 -> {
                        if (info.size > 0) {
                            codec.releaseOutputBuffer(idx, true)
                            statRendered++
                            renderedFrames.incrementAndGet()
                            lastRenderedAtMillis = System.currentTimeMillis()
                            if (!firstFrameRendered) {
                                firstFrameRendered = true
                                TermLog.i("screen") { "first frame rendered (direct)" }
                                onReady()
                            }
                        } else {
                            codec.releaseOutputBuffer(idx, false)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            if (isSurfaceFailure(surface, e)) {
                TermLog.i("screen") { "surface 渲染期间失效，释放解码器等待重建" }
                false
            } else {
                throw e
            }
        }
    }

    /**
     * The Android Emulator’s goldfish H.264 hardware bridge may cause blocking indefinitely after the first frame
     * dequeueInputBuffer (even if a timeout is set). The emulator does not need to optimize power consumption; it should always use
     * Android’s built-in software decoder is more stable; on physical devices, let the system continue with the hardware decoder selection.
     */
    private data class DecoderSelection(
        val codec: MediaCodec,
        val name: String,
        val lowLatency: Boolean,
    )

    private fun createDecoder(): DecoderSelection {
        if (isAndroidEmulator()) {
            runCatching { MediaCodec.createByCodecName(ANDROID_SOFTWARE_AVC_DECODER) }
                .onSuccess {
                    TermLog.i("screen") { "emulator uses software decoder $ANDROID_SOFTWARE_AVC_DECODER" }
                    return DecoderSelection(it, ANDROID_SOFTWARE_AVC_DECODER, lowLatency = false)
                }.onFailure {
                    TermLog.w("screen") { "software decoder unavailable, fallback to default: ${it.message}" }
                }
        }
        val codec = MediaCodec.createDecoderByType(MIME)
        val lowLatency =
            Build.VERSION.SDK_INT >= 30 &&
                runCatching {
                    codec
                        .codecInfo
                        .getCapabilitiesForType(MIME)
                        .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                }.getOrDefault(false)
        return DecoderSelection(codec, codec.name, lowLatency)
    }
}

@Composable
actual fun ScreenVideoSurface(
    player: ScreenPlayer?,
    modifier: Modifier,
    alignBottom: Boolean,
) {
    if (player == null) {
        Box(modifier.background(ComposeColor.Black))
        return
    }
    // Aspect ratio adjustment: Fill the area (with a black background) before the decoder reports the actual dimensions; after reporting, center the content according to the ratio.
    // (Replaces RESIZE_MODE_FIT in ExoPlayer’s PlayerView; SurfaceView does not letterbox on its own.)
    // When the remote keyboard pops up, align it to the bottom (alignBottom): place the toolbar at the bottom of the video and leave a black border at the top.
    val dims = player.decoder.videoDims.value
    Box(
        modifier.background(ComposeColor.Black),
        contentAlignment = if (alignBottom) Alignment.BottomCenter else Alignment.Center,
    ) {
        val surfaceModifier =
            if (dims != null) {
                Modifier.aspectRatio(dims.first.toFloat() / dims.second.toFloat())
            } else {
                Modifier
            }
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(
                        object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                player.decoder.attachSurface(holder.surface)
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int,
                            ) {
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                player.decoder.detachSurface(holder.surface)
                            }
                        },
                    )
                }
            },
            modifier = surfaceModifier,
        )
    }
}

/**
 * Detect the maximum frame rate of the hardware decoder at a specified resolution (MediaCodecList capability query).
 * Take the maximum across all AVC hardware decoders; return 0 if no info/on failure (unknown—the caller does not clamp).
 * Flagship devices typically support 1080p H.264 at 60+; if the detected value is lower than the streaming profile, it indicates that the decoder truly cannot keep up,
 * The menu will hide items that exceed this limit (to avoid pushing data in vain—which wastes bandwidth and overloads the decoder with a queue).
 */
actual fun probeDecoderMaxFps(
    width: Int,
    height: Int,
): Int {
    val key = "$width x $height"
    decoderProbeCache[key]?.let { return it }
    // The emulator actually decodes using c2.android.avc.decoder; MediaCodecList will still expose the
    // goldfish/ranchu bridge "hardware decode" capabilities, which are often over-reported (120fps+). If this value is used for the
    // quality-level menu and auto-resume, the software decoder is driven until it saturates. Maintain the same origin as `createDecoder`;
    // The emulator is capped at 30 fps; physical devices will still support 60/120 fps based on hardware capabilities.
    if (isAndroidEmulator()) {
        decoderProbeCache[key] = ANDROID_EMULATOR_MAX_FPS
        return ANDROID_EMULATOR_MAX_FPS
    }
    var best = 0
    try {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (info in list.codecInfos) {
            if (info.isEncoder || !info.supportedTypes.any { it.equals(DECODER_MIME, ignoreCase = true) }) continue
            if (Build.VERSION.SDK_INT >= 29 && !info.isHardwareAccelerated) continue
            runCatching {
                val caps = info.getCapabilitiesForType(DECODER_MIME)
                val videoCapabilities = caps.videoCapabilities ?: return@runCatching
                val fps =
                    videoCapabilities
                        .getSupportedFrameRatesFor(width, height)
                        .upper
                        .toInt()
                if (fps > best) best = fps
            }
        }
    } catch (_: Throwable) {
        // Detection failure does not block: return 0 = unknown
    }
    decoderProbeCache[key] = best
    return best
}
