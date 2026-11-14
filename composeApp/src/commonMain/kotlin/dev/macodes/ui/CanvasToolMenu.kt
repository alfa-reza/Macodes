package dev.macodes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.macodes.ui.theme.TerminalTheme

/**
 * Menu entry in the lower-right corner of the canvas (expandable):
 * - Standby: brand-green circular button with a “+” icon; tap to expand the function menu (slides up from the button)
 * - Menu items: Capsule buttons (icon + tag); tapping executes the action and collapses the menu; simply add subsequent features inside.
 * - Hidden entirely during recording (recording state is taken over by the big center button; see BigVoiceStopButton)
 */
@Composable
fun CanvasToolMenu(
    /** Is the menu expanded? */
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    /** Menu items (listed from bottom to top, with the first one closest to the button). */
    items: List<CanvasMenuAction>,
    theme: TerminalTheme,
    modifier: Modifier = Modifier,
) {
    val brand = MaterialTheme.colorScheme.primary
    val onBrand = MaterialTheme.colorScheme.onPrimary
    // Expand arrow rotation: + → ×
    val rotation by animateFloatAsState(
        targetValue = if (menuOpen) 45f else 0f,
        animationSpec = tween(160),
        label = "menuRotate",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Function Menu: Slide out from the bottom up
        AnimatedVisibility(
            visible = menuOpen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(tween(160)),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(tween(120)),
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items.forEach { action ->
                    CanvasMenuActionItem(action, theme)
                }
            }
        }

        // Main Button
        Box(
            Modifier
                .size(44.dp)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(brand)
                .clickable { onMenuOpenChange(!menuOpen) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = onBrand,
                modifier = Modifier.size(22.dp).rotate(rotation),
            )
        }
    }
}

/** Single menu action: capsule button (icon + tag). */
@Composable
private fun CanvasMenuActionItem(
    action: CanvasMenuAction,
    theme: TerminalTheme,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .clickable(onClick = action.onClick)
            .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(action.badge ?: MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                action.icon,
                contentDescription = null,
                tint =
                    if (action.badge !=
                        null
                    ) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            action.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Menu action definitions (expandable: add future features here). */
data class CanvasMenuAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    /** Icon background color; null = brand green. */
    val badge: Color? = null,
    val onClick: () -> Unit,
)

/** Voice menu action icons (reused). */
val VoiceMenuIcon: ImageVector get() = Icons.Filled.Mic
