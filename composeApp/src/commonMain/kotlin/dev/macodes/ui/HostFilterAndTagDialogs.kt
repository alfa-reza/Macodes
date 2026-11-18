package dev.macodes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.macodes.data.CloudProvider
import dev.macodes.data.ConnectionMode
import dev.macodes.data.HostFilterState
import dev.macodes.data.HostSortOrder
import dev.macodes.data.HostTag
import dev.macodes.data.newId
import dev.macodes.ui.theme.Alphas
import dev.macodes.ui.theme.Corners
import dev.macodes.ui.theme.Sizes
import dev.macodes.ui.theme.Spacing

internal val HOST_TAG_PALETTE: List<Color> =
    listOf(
        Color(0xFF2DD4BF), // Teal
        Color(0xFF818CF8), // Indigo
        Color(0xFF34D399), // Emerald
        Color(0xFFFBBF24), // Amber
        Color(0xFFF87171), // Coral
        Color(0xFF38BDF8), // Sky
    )

internal fun hostTagAccentColor(colorIndex: Int?): Color? = colorIndex?.let { idx -> HOST_TAG_PALETTE.getOrNull(idx) }

internal fun cloudProviderDisplayLabel(
    provider: CloudProvider?,
    ws: WorkspaceStrings,
): String =
    when (provider) {
        null -> ws.hostOrg.providerNotSpecified
        CloudProvider.OTHER -> ws.hostOrg.providerOther
        else -> provider.displayName
    }

@Composable
internal fun HostTagBadge(
    tagName: String,
    colorIndex: Int?,
    modifier: Modifier = Modifier,
) {
    val accent = hostTagAccentColor(colorIndex)
    val bgColor =
        accent?.copy(alpha = Alphas.SUBTLE) ?: MaterialTheme.colorScheme.surfaceContainerHighest
    val borderColor =
        accent?.copy(alpha = Alphas.BORDER_ACCENT) ?: MaterialTheme.colorScheme.outlineVariant
    val textColor =
        accent ?: MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Corners.Sm),
        color = bgColor,
        border = BorderStroke(Sizes.BorderThin, borderColor),
    ) {
        Text(
            text = "#$tagName",
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun HostFilterBottomSheet(
    initialFilter: HostFilterState,
    availableTags: List<HostTag>,
    ws: WorkspaceStrings,
    s: AppStrings,
    onDismiss: () -> Unit,
    onManageTags: () -> Unit,
    onApply: (HostFilterState) -> Unit,
) {
    var draft by remember(initialFilter) { mutableStateOf(initialFilter) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val chipColors =
        FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.Lg)
                    .padding(bottom = Spacing.Xl)
                    .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.Lg),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = ws.hostOrg.filterTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(
                    onClick = { draft = HostFilterState() },
                    modifier = Modifier.heightIn(min = Sizes.TouchTarget),
                ) {
                    Text(ws.hostOrg.filterClearAll)
                }
            }

            // Sort order
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                Text(
                    text = ws.hostOrg.filterSortLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                ) {
                    listOf(
                        HostSortOrder.NAME to ws.hostOrg.sortByName,
                        HostSortOrder.RECENTLY_USED to ws.hostOrg.sortRecentlyUsed,
                    ).forEach { (order, label) ->
                        val selected = draft.sortOrder == order
                        FilterChip(
                            selected = selected,
                            onClick = { draft = draft.copy(sortOrder = order) },
                            label = { Text(label) },
                            leadingIcon = if (selected) ({ FilterCheckIcon() }) else null,
                            colors = chipColors,
                            modifier = Modifier.heightIn(min = Sizes.TouchTarget),
                        )
                    }
                    FilterChip(
                        selected = draft.pinnedOnly,
                        onClick = { draft = draft.copy(pinnedOnly = !draft.pinnedOnly) },
                        label = { Text(ws.hostOrg.filterPinnedOnly) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.PushPin,
                                contentDescription = null,
                                modifier = Modifier.size(Spacing.Lg),
                            )
                        },
                        colors = chipColors,
                        modifier = Modifier.heightIn(min = Sizes.TouchTarget),
                    )
                }
            }

            // Protocol (OR within category)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                Text(
                    text = ws.hostOrg.filterProtocolLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                ) {
                    listOf(
                        ConnectionMode.SSH to s.editModeSsh,
                        ConnectionMode.MOSH to "Mosh",
                    ).forEach { (mode, label) ->
                        val selected = mode in draft.selectedProtocols
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val next =
                                    if (selected) {
                                        draft.selectedProtocols - mode
                                    } else {
                                        draft.selectedProtocols + mode
                                    }
                                draft = draft.copy(selectedProtocols = next)
                            },
                            label = { Text(label) },
                            leadingIcon = if (selected) ({ FilterCheckIcon() }) else null,
                            colors = chipColors,
                            modifier = Modifier.heightIn(min = Sizes.TouchTarget),
                        )
                    }
                }
            }

            // Cloud Provider (OR within category)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                Text(
                    text = ws.hostOrg.filterProviderLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                ) {
                    CloudProvider.entries.forEach { provider ->
                        val selected = provider in draft.selectedProviders
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val next =
                                    if (selected) {
                                        draft.selectedProviders - provider
                                    } else {
                                        draft.selectedProviders + provider
                                    }
                                draft = draft.copy(selectedProviders = next)
                            },
                            label = { Text(cloudProviderDisplayLabel(provider, ws)) },
                            leadingIcon = if (selected) ({ FilterCheckIcon() }) else null,
                            colors = chipColors,
                            modifier = Modifier.heightIn(min = Sizes.TouchTarget),
                        )
                    }
                }
            }

            // Tags (AND across selected tags) + Manage Tags shortcut
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = ws.hostOrg.filterTagsLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            onDismiss()
                            onManageTags()
                        },
                        modifier = Modifier.heightIn(min = Sizes.TouchTarget),
                    ) {
                        Text(ws.hostOrg.manageTagsAction)
                    }
                }
                if (availableTags.isEmpty()) {
                    Text(
                        text = ws.hostOrg.tagsEmpty,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                    ) {
                        availableTags.forEach { tag ->
                            val selected = draft.selectedTags.any { it.equals(tag.name, ignoreCase = true) }
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val next =
                                        if (selected) {
                                            draft.selectedTags
                                                .filterNot {
                                                    it.equals(tag.name, ignoreCase = true)
                                                }.toSet()
                                        } else {
                                            draft.selectedTags + tag.name
                                        }
                                    draft = draft.copy(selectedTags = next)
                                },
                                label = { Text("#${tag.name}") },
                                leadingIcon = if (selected) ({ FilterCheckIcon() }) else null,
                                colors = chipColors,
                                modifier = Modifier.heightIn(min = Sizes.TouchTarget),
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = Sizes.TouchTarget),
                ) {
                    Text(s.terminalCancel)
                }
                Spacer(Modifier.width(Spacing.Sm))
                Button(
                    onClick = { onApply(draft) },
                    modifier = Modifier.heightIn(min = Sizes.TouchTarget),
                ) {
                    Text(ws.hostOrg.filterApply)
                }
            }
        }
    }
}

@Composable
internal fun HostTagManagerDialog(
    tags: List<HostTag>,
    ws: WorkspaceStrings,
    s: AppStrings,
    onUpsertTag: (HostTag) -> Unit,
    onDeleteTag: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editingTag by remember { mutableStateOf<HostTag?>(null) }
    var creatingTag by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<HostTag?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(ws.hostOrg.tagsTitle)
                IconButton(
                    onClick = { creatingTag = true },
                    modifier = Modifier.size(Sizes.TouchTarget),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = ws.hostOrg.tagNew)
                }
            }
        },
        text = {
            if (tags.isEmpty()) {
                Text(
                    text = ws.hostOrg.tagsEmpty,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Xs),
                ) {
                    tags.forEach { tag ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = Sizes.TouchTarget)
                                    .clip(RoundedCornerShape(Corners.Md))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(horizontal = Spacing.Md, vertical = Spacing.Xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val accent = hostTagAccentColor(tag.colorIndex)
                            Box(
                                modifier =
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(accent ?: MaterialTheme.colorScheme.onSurfaceVariant),
                            )
                            Spacer(Modifier.width(Spacing.Sm))
                            Text(
                                text = "#${tag.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { editingTag = tag },
                                modifier = Modifier.size(Sizes.TouchTarget),
                            ) {
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = ws.hostOrg.tagRename,
                                    modifier = Modifier.size(Sizes.IconSmall),
                                )
                            }
                            IconButton(
                                onClick = { pendingDelete = tag },
                                modifier = Modifier.size(Sizes.TouchTarget),
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = ws.hostOrg.tagDelete,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(Sizes.IconSmall),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = Sizes.TouchTarget),
            ) {
                Text(s.navBack)
            }
        },
    )

    if (creatingTag || editingTag != null) {
        HostTagEditDialog(
            existing = editingTag,
            ws = ws,
            s = s,
            onSave = { tag ->
                onUpsertTag(tag)
                creatingTag = false
                editingTag = null
            },
            onDismiss = {
                creatingTag = false
                editingTag = null
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(ws.hostOrg.tagDelete) },
            text = { Text(ws.hostOrg.tagDeleteConfirm(target.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteTag(target.id)
                        pendingDelete = null
                    },
                ) {
                    Text(s.hostsDelete, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(s.terminalCancel)
                }
            },
        )
    }
}

@Composable
private fun HostTagEditDialog(
    existing: HostTag?,
    ws: WorkspaceStrings,
    s: AppStrings,
    onSave: (HostTag) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var colorIndex by remember(existing) { mutableStateOf(existing?.colorIndex) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) ws.hostOrg.tagNew else ws.hostOrg.tagRename) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(ws.hostOrg.tagNameLabel) },
                    singleLine = true,
                    shape = RoundedCornerShape(Corners.Md),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = ws.hostOrg.tagColorLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .border(
                                    width = if (colorIndex == null) 2.dp else 1.dp,
                                    color =
                                        if (colorIndex == null) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                    shape = CircleShape,
                                ).clickable { colorIndex = null },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (colorIndex == null) {
                            Icon(Icons.Filled.Check, contentDescription = ws.hostOrg.tagColorDefault, modifier = Modifier.size(16.dp))
                        }
                    }
                    HOST_TAG_PALETTE.forEachIndexed { idx, color ->
                        val selected = colorIndex == idx
                        Box(
                            modifier =
                                Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (selected) 2.dp else 0.dp,
                                        color = if (selected) Color.White else Color.Transparent,
                                        shape = CircleShape,
                                    ).clickable { colorIndex = idx },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.trim().removePrefix("#").isNotEmpty(),
                onClick = {
                    onSave(
                        HostTag(
                            id = existing?.id ?: newId(),
                            name = name.trim().removePrefix("#"),
                            colorIndex = colorIndex,
                        ),
                    )
                },
            ) {
                Text(s.editSave)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s.terminalCancel) }
        },
    )
}

@Composable
internal fun HostAssignTagsDialog(
    targetCount: Int,
    initialSelectedTags: Set<String>,
    availableTags: List<HostTag>,
    ws: WorkspaceStrings,
    s: AppStrings,
    onCreateTag: (String) -> Unit,
    onApplySingleHost: ((Set<String>) -> Unit)? = null,
    onApplyBulk: ((addTags: Set<String>, removeTags: Set<String>) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var selected by remember(initialSelectedTags) { mutableStateOf(initialSelectedTags) }
    var removed by remember { mutableStateOf(emptySet<String>()) }
    var newTagName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ws.hostOrg.assignTagsTitle) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Xs),
                ) {
                    OutlinedTextField(
                        value = newTagName,
                        onValueChange = { newTagName = it },
                        label = { Text(ws.hostOrg.tagNew) },
                        singleLine = true,
                        shape = RoundedCornerShape(Corners.Md),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        enabled = newTagName.trim().removePrefix("#").isNotEmpty(),
                        onClick = {
                            val clean = newTagName.trim().removePrefix("#")
                            onCreateTag(clean)
                            selected = selected + clean
                            removed = removed - clean
                            newTagName = ""
                        },
                        modifier = Modifier.size(Sizes.TouchTarget),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = ws.hostOrg.tagNew)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (availableTags.isEmpty()) {
                    Text(
                        text = ws.hostOrg.tagsEmpty,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    availableTags.forEach { tag ->
                        val isChecked = selected.any { it.equals(tag.name, ignoreCase = true) }
                        val isRemoved = removed.any { it.equals(tag.name, ignoreCase = true) }
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = Sizes.TouchTarget)
                                    .clip(RoundedCornerShape(Corners.Md))
                                    .clickable {
                                        if (targetCount <= 1) {
                                            selected =
                                                if (isChecked) {
                                                    selected.filterNot { it.equals(tag.name, ignoreCase = true) }.toSet()
                                                } else {
                                                    selected + tag.name
                                                }
                                        } else {
                                            // Bulk mode cycles: add -> remove -> unchanged
                                            when {
                                                isChecked -> {
                                                    selected = selected.filterNot { it.equals(tag.name, ignoreCase = true) }.toSet()
                                                    removed = removed + tag.name
                                                }
                                                isRemoved -> {
                                                    removed = removed.filterNot { it.equals(tag.name, ignoreCase = true) }.toSet()
                                                }
                                                else -> {
                                                    selected = selected + tag.name
                                                }
                                            }
                                        }
                                    },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = null,
                            )
                            Spacer(Modifier.width(Spacing.Sm))
                            Text(
                                text = "#${tag.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                color =
                                    if (isRemoved) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                modifier = Modifier.weight(1f),
                            )
                            if (targetCount > 1 && isChecked) {
                                Text(
                                    text = ws.hostOrg.bulkTagsAdd,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else if (targetCount > 1 && isRemoved) {
                                Text(
                                    text = ws.hostOrg.bulkTagsRemove,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (targetCount <= 1) {
                        onApplySingleHost?.invoke(selected)
                    } else {
                        onApplyBulk?.invoke(selected, removed)
                    }
                    onDismiss()
                },
            ) {
                Text(s.editSave)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s.terminalCancel) }
        },
    )
}

@Composable
private fun FilterCheckIcon() {
    Icon(
        imageVector = Icons.Filled.Check,
        contentDescription = null,
        modifier = Modifier.size(Spacing.Lg),
    )
}
