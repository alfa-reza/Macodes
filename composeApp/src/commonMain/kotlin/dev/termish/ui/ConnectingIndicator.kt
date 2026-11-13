package dev.termish.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.termish.ui.theme.StatusColors

/**
 * “Connecting” unified centered indicator (shared by terminal-screen initial connect/reconnect and SFTP initial connect/reconnect):
 * Spinner + text capsule, fade in and out upon appearance/exit—disappear smoothly upon completion without leaving a floating overlay.
 *
 * - [bottomOverlay]: The height at the bottom of the canvas covered by the floating toolbar (including the navigation bar and keyboard
 *   padding). The indicator is centered within the “visible canvas” (shifted up by half the height of the overlay),
 *   The visual center of gravity will not shift downward due to full-screen centering; the SFTP page has no bottom bar, so setting it to 0 centers the entire page.
 * - Color: By default, use the application theme (SFTP light mode); for dark terminal screens, the color must be specified.
 *   Terminal theme background/foreground; otherwise, a bright capsule will appear on the canvas when the light theme is applied.
 *   Unify the color of spinners and strokes to use semantic colors [StatusColors.Warning].
 */
@Composable
fun ConnectingIndicator(
    visible: Boolean,
    text: String,
    modifier: Modifier = Modifier,
    bottomOverlay: Dp = 0.dp,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(220)),
        modifier = modifier.padding(bottom = bottomOverlay / 2),
    ) {
        Row(
            Modifier
                .background(containerColor.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
                .border(1.dp, StatusColors.Warning.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = StatusColors.Warning,
            )
            Text(
                text,
                color = contentColor,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** A small spinner next to the status dot (visualizes “establishing” on the connection session row; same semantics as the avatar on the home page). */
@Composable
fun StatusSpinner(
    modifier: Modifier = Modifier,
    color: Color = StatusColors.Warning,
) {
    CircularProgressIndicator(
        modifier = modifier,
        strokeWidth = 2.dp,
        color = color,
    )
}
