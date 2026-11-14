package dev.macodes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.macodes.ui.theme.TerminalTheme
import dev.macodes.util.monospaceFontFamily
import kotlin.math.PI
import kotlin.math.sin

/** UI state of the voice input FAB (driven by the caller). */
enum class VoiceUiState { IDLE, LISTENING, RECOGNIZING }

/** Number of wave bars. */
private const val WAVE_BARS = 13

/** The static weight of each bar (high in the middle, low at both ends; arc-shaped profile). */
private val WAVE_WEIGHTS =
    FloatArray(WAVE_BARS) { i ->
        val x = (i - (WAVE_BARS - 1) / 2f) / ((WAVE_BARS - 1) / 2f)
        (0.35f + 0.65f * (1f - x * x)).toFloat() // Quadratic curve: 1.0 in the middle, 0.35 at both ends
    }

/** Each bar has its own flowing phase (staggered → lateral wave-flow feel). */
private val WAVE_PHASES = FloatArray(WAVE_BARS) { i -> i * 0.55f }

/** Each bar has its own oscillation frequency (varying rates emulating real sound waves). */
private val WAVE_FREQS = FloatArray(WAVE_BARS) { i -> 2.2f + (i % 3) * 0.45f }

/**
 * Reset the small indicator: After the voice button is dragged, it appears in the upper-right corner; tap it to reset the button to the center of the screen horizontally.
 * Compact (26dp) and unobtrusive; does not show when not dragged.
 */
@Composable
fun VoiceResetBadge(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(26.dp)
            .shadow(4.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Refresh,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * Voice recording start button centered on the screen (horizontally): large, brand-green circular button + microphone (tap once to start immediately,
 * (Skip the two steps in the bottom-right menu.) The outer layer handles dragging; **long press = reset to default position**.
 * Tap to instantly toggle to the recording mode component (red button + overlay).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VoiceStartButton(
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(64.dp)
            .shadow(10.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(30.dp),
        )
    }
}

/** Large central button in the recording state (horizontally centered, larger than the menu FAB):
 * - LISTENING: 72dp red circle button + white microphone (tap = end and send)
 * - RECOGNIZING: Going in circles
 * Align it in the central column above the toolbar, alongside the overlay (transcription + sound wave).
 */
@Composable
fun BigVoiceStopButton(
    state: VoiceUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recognizing = state == VoiceUiState.RECOGNIZING
    Box(
        modifier
            .size(72.dp)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(
                if (recognizing) {
                    MaterialTheme.colorScheme.error.copy(
                        alpha = 0.55f,
                    )
                } else {
                    MaterialTheme.colorScheme.error
                },
            ).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (recognizing) {
            CircularProgressIndicator(
                modifier = Modifier.size(30.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.onError,
            )
        } else {
            Icon(
                Icons.Filled.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

/** Bottom-floating layer for voice recording (WeChat/iFlytek style): Does not obscure content in the middle of the terminal. */
@Composable
fun VoiceRecordingOverlayHost(
    visible: Boolean,
    state: VoiceUiState,
    /** Real-time volume 0..1 (recording callback updates every ~200 ms, smooth animation layer). */
    level: Float,
    /** Transcribe text in real-time (interim results, updated as packages are replaced). */
    partialText: String,
    /** The recording has been running for [number] seconds. */
    seconds: Int,
    theme: TerminalTheme,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it / 2 }, animationSpec = tween(220)) + fadeIn(tween(220)),
        exit = slideOutVertically(targetOffsetY = { it / 2 }, animationSpec = tween(180)) + fadeOut(tween(180)),
        modifier = modifier,
    ) {
        VoiceRecordingOverlay(state, level, partialText, seconds, theme)
    }
}

@Composable
private fun VoiceRecordingOverlay(
    state: VoiceUiState,
    level: Float,
    partialText: String,
    seconds: Int,
    theme: TerminalTheme,
) {
    val s = LocalAppStrings.current
    val recognizing = state == VoiceUiState.RECOGNIZING
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(surface.copy(alpha = 0.97f))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Real-time transcription: Text appears as you speak (monospaced, up to 2 rows)
        Text(
            partialText.ifBlank { if (recognizing) s.voice.recognizing else s.voice.listening },
            color = if (partialText.isBlank()) muted else onSurface,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = monospaceFontFamily(),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 480.dp),
        )

        // Flowing Sound Waves: Canvas single-time-driven, with each bar having independent phase/frequency → creating a sense of lateral flow
        if (!recognizing) {
            FlowingWave(level = level, accent = accent, modifier = Modifier.height(36.dp))
        }

        // Status row: Recording timer / Recognition tooltip
        Text(
            if (recognizing) s.voice.recognizing else s.voice.recordingSeconds(seconds),
            color = muted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * Traveling sound waves: The overall amplitude is modulated by volume, and each waveform superimposes sine waves with independent phases and frequencies → resulting in a lateral wave motion.
 * Single infinite transition driver (do not start a separate animation for each item).
 */
@Composable
private fun FlowingWave(
    level: Float,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "wave")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "waveTime",
    )
    Canvas(modifier) {
        val barW = 3.dp.toPx()
        val gap = 5.dp.toPx()
        val totalW = barW * WAVE_BARS + gap * (WAVE_BARS - 1)
        var x = (size.width - totalW) / 2f
        val maxH = size.height
        for (i in 0 until WAVE_BARS) {
            // Volume amplitude + independent sine waves (phase shift → sense of flow)
            val wobble = (sin(time * WAVE_FREQS[i] + WAVE_PHASES[i]) * 0.5f + 0.5f).toFloat()
            val h = maxH * (0.16f + (level * WAVE_WEIGHTS[i] * 0.7f + wobble * 0.14f).coerceIn(0f, 1f))
            drawRoundRect(
                color = accent,
                topLeft = Offset(x, (maxH - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
            x += barW + gap
        }
    }
}
