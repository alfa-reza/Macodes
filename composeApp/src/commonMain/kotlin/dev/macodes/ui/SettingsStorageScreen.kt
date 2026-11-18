package dev.macodes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.macodes.data.HostRepository
import dev.macodes.ui.theme.Alphas
import dev.macodes.ui.theme.Corners
import dev.macodes.ui.theme.Sizes
import dev.macodes.ui.theme.Spacing
import dev.macodes.util.clearAppCache
import dev.macodes.util.formatByteSize
import dev.macodes.util.getAppCacheStats
import kotlinx.coroutines.launch

private enum class PendingHistoryClear {
    QUICK_CONNECT,
    SESSION_LOGS,
    ALL,
}

/**
 * Cache & History Settings subpage:
 * - Clear local app cache & diagnostic logs
 * - Clear Quick Connect history or Session/Connection logs with explicit warning confirmation
 */
@Composable
fun SettingsStorageScreen(
    repository: HostRepository,
    onBack: () -> Unit,
) {
    val s = LocalAppStrings.current
    val extra = s.settingsExtra
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var cacheStats by remember { mutableStateOf(getAppCacheStats()) }
    var quickConnectCount by remember { mutableIntStateOf(repository.listQuickConnectHistory().size) }
    var sessionLogCount by remember { mutableIntStateOf(repository.listSessionLogs().size) }
    var pendingClear by remember { mutableStateOf<PendingHistoryClear?>(null) }

    fun showFeedback(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            MacodesHeader(
                title = extra.storageAndHistoryTitle,
                onBack = onBack,
            )
        },
        snackbarHost = { MacodesSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            SettingsGroup(extra.storageCacheSection) {
                StorageActionRow(
                    title = extra.storageCacheSizeLabel,
                    subtitle = "${extra.storageCacheDesc} · ${formatByteSize(cacheStats.totalBytes)}",
                    icon = Icons.Filled.CleaningServices,
                    actionLabel = extra.storageCacheClear,
                    enabled = cacheStats.totalBytes > 0L,
                    onClick = {
                        clearAppCache()
                        cacheStats = getAppCacheStats()
                        showFeedback(extra.storageCacheCleared)
                    },
                )
            }

            SettingsGroup(extra.storageHistorySection) {
                StorageActionRow(
                    title = extra.storageQuickConnectHistory,
                    subtitle = extra.storageQuickConnectCount(quickConnectCount),
                    icon = Icons.Filled.History,
                    actionLabel = extra.storageWarnConfirmDelete,
                    enabled = quickConnectCount > 0,
                    destructive = true,
                    onClick = { pendingClear = PendingHistoryClear.QUICK_CONNECT },
                )
                SettingsDivider()
                StorageActionRow(
                    title = extra.storageSessionLogs,
                    subtitle = extra.storageSessionLogsCount(sessionLogCount),
                    icon = Icons.Filled.ReceiptLong,
                    actionLabel = extra.storageWarnConfirmDelete,
                    enabled = sessionLogCount > 0,
                    destructive = true,
                    onClick = { pendingClear = PendingHistoryClear.SESSION_LOGS },
                )
            }

            if (quickConnectCount > 0 || sessionLogCount > 0) {
                Spacer(Modifier.height(Spacing.Lg))
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.Lg)
                            .clip(RoundedCornerShape(Corners.Lg))
                            .clickable { pendingClear = PendingHistoryClear.ALL },
                    shape = RoundedCornerShape(Corners.Lg),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = Alphas.SUBTLE),
                        ),
                    border =
                        BorderStroke(
                            Sizes.BorderThin,
                            MaterialTheme.colorScheme.error.copy(alpha = Alphas.BORDER_ACCENT),
                        ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                    ) {
                        Icon(
                            Icons.Filled.DeleteSweep,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(Sizes.IconMedium),
                        )
                        Text(
                            extra.storageClearAllHistory,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.Xl))
        }
    }

    pendingClear?.let { target ->
        val message =
            when (target) {
                PendingHistoryClear.QUICK_CONNECT -> extra.storageWarnClearQuickConnect(quickConnectCount)
                PendingHistoryClear.SESSION_LOGS -> extra.storageWarnClearLogs(sessionLogCount)
                PendingHistoryClear.ALL -> extra.storageWarnClearAll(quickConnectCount, sessionLogCount)
            }
        AlertDialog(
            onDismissRequest = { pendingClear = null },
            icon = {
                Icon(
                    Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(extra.storageWarnClearTitle) },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        when (target) {
                            PendingHistoryClear.QUICK_CONNECT -> {
                                repository.clearQuickConnectHistory()
                                quickConnectCount = 0
                            }
                            PendingHistoryClear.SESSION_LOGS -> {
                                repository.clearSessionLogs()
                                sessionLogCount = 0
                            }
                            PendingHistoryClear.ALL -> {
                                repository.clearQuickConnectHistory()
                                repository.clearSessionLogs()
                                quickConnectCount = 0
                                sessionLogCount = 0
                            }
                        }
                        pendingClear = null
                        showFeedback(extra.storageHistoryCleared)
                    },
                ) {
                    Text(extra.storageWarnConfirmDelete, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = null }) {
                    Text(s.terminalCancel)
                }
            },
        )
    }
}

@Composable
private fun StorageActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    actionLabel: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint =
        if (destructive && enabled) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.Lg, vertical = Spacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
    ) {
        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(Corners.Sm))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(Sizes.IconSmall),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (enabled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(Corners.Sm))
                        .background(
                            if (destructive) {
                                MaterialTheme.colorScheme.error.copy(alpha = Alphas.SUBTLE)
                            } else {
                                MaterialTheme.colorScheme.primary.copy(alpha = Alphas.SUBTLE)
                            },
                        ).padding(horizontal = Spacing.Sm, vertical = Spacing.Xs),
            ) {
                if (destructive) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                )
            }
        }
    }
}
