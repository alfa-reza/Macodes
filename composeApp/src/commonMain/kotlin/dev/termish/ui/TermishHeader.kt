package dev.termish.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.termish.util.monospaceFontFamily

/**
 * Global, compact header (same style as the terminal screen, approximately 48dp + bottom separator line):
 * Optional back arrow + subtitle + right-side action area.
 *
 * @param statusBarPadding Whether to automatically avoid the status bar; pass `false` if the terminal screen’s outer layer has already handled this.
 */
@Composable
fun TermishHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    statusBarPadding: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val s = LocalAppStrings.current
    Column(modifier.fillMaxWidth().background(containerColor)) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (statusBarPadding) Modifier.statusBarsPadding() else Modifier)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = s.navBack,
                        tint = contentColor,
                    )
                }
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = monospaceFontFamily(),
                color = contentColor,
            )
            Spacer(Modifier.weight(1f))
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                actions()
            }
        }
        HorizontalDivider(color = contentColor.copy(alpha = 0.15f))
    }
}

/**
 * Main Header (iOS Style): For use on top-level tab pages.
 * 28sp bolded main heading + ample white space, no bottom border, for a cleaner visual appearance.
 */
@Composable
fun TermishLargeHeader(
    title: String,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    /** When non-null, render a back arrow before the title (unified back affordance across second-level pages). */
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = if (onBack != null) 4.dp else 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = LocalAppStrings.current.navBack,
                    tint = contentColor,
                )
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            fontFamily = monospaceFontFamily(),
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            actions()
        }
    }
}
