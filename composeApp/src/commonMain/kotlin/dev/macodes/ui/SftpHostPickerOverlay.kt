package dev.macodes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.macodes.data.ConnectionMode
import dev.macodes.data.Host
import dev.macodes.ui.theme.Corners
import dev.macodes.ui.theme.Sizes
import dev.macodes.ui.theme.Spacing

/**
 * New SFTP connection overlay: appears on top of the current screen (not a stack-based navigation).
 * Header = X + Title; the "hosts" list below follows the same style as the homepage and is clickable only.
 */
@Composable
fun SftpHostPickerOverlay(
    hosts: List<Host>,
    onDismiss: () -> Unit,
    onSelect: (Host) -> Unit,
) {
    val s = LocalAppStrings.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.Xs, vertical = Spacing.Xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    // Explicit tint: The `LocalContentColor` of the Dialog window is the platform default (black)
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = s.navBack,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    s.sftpNewTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                s.sftpSectionTitle,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Spacing.Lg, vertical = Spacing.Xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                items(hosts, key = { it.id }) { host ->
                    SftpHostRow(host = host, onClick = { onSelect(host) })
                }
            }
        }
    }
}

/** Host list rows: Style consistent with the home page cards (avatar + IP + connection details); clickable only. */
@Composable
private fun SftpHostRow(
    host: Host,
    onClick: () -> Unit,
) {
    val s = LocalAppStrings.current
    val sys = host.system.ifBlank { host.hostname }
    // First row: Hostname (alias) takes priority; if empty, fall back to the address—consistent with the host card on the homepage
    // (User feedback: The selector shows the IP address instead of the host name.)
    val title = host.name.ifBlank { host.hostname }
    val address = if (host.port != 22) "${host.hostname}:${host.port}" else host.hostname
    val detail =
        buildString {
            append(if (host.connectionMode == ConnectionMode.MOSH) s.hostsModeMosh else s.hostsModeSsh)
            append(", ")
            append(host.username)
            if (host.system.isNotBlank()) {
                append(", ")
                append(host.system)
            }
            // Include the address in the details row: Hosts with the same name can be distinguished, confirming which one is connected.
            append(", ")
            append(address)
        }
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Corners.Lg))
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(Corners.Lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
        ) {
            Box(
                Modifier.size(Sizes.AvatarCard).clip(RoundedCornerShape(Corners.Md)).background(systemColor(sys)),
                contentAlignment = Alignment.Center,
            ) {
                SystemAvatarIcon(sys, Sizes.IconMedium)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
