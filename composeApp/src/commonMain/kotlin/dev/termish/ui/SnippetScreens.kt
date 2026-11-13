package dev.termish.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termish.data.HostRepository
import dev.termish.data.Snippet
import dev.termish.data.TagGroup
import dev.termish.data.newId
import dev.termish.ui.theme.Spacing
import dev.termish.ui.theme.TerminalTheme
import dev.termish.util.monospaceFontFamily
import kotlinx.datetime.Clock

/**
 * Command Snippet Management Page (second-level settings page): List + Search + Tag Filtering + Create/Edit + Tag Management Entry.
 * The snippet is for a global library (that can be reused across hosts), and tags must be selected from a tag group (do not provide them as free input).
 */
@Composable
fun SnippetManageScreen(
    repository: HostRepository,
    onBack: () -> Unit,
) {
    val s = LocalAppStrings.current
    var snippets by remember { mutableStateOf(repository.listSnippets()) }
    var tags by remember { mutableStateOf(repository.listTagGroups()) }
    var query by remember { mutableStateOf("") }
    var filterTagId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Snippet?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var showTagPage by remember { mutableStateOf(false) }

    val q = query.trim()
    val filtered =
        snippets
            .filter { filterTagId == null || it.tagIds.contains(filterTagId) }
            .filter {
                q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.content.contains(q, ignoreCase = true)
            }.sortedByDescending { it.updatedAt }

    Scaffold(
        topBar = {
            TermishLargeHeader(
                title = s.snippetsTitle,
                onBack = onBack,
                actions = {
                    IconButton(onClick = { showTagPage = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = s.tagsTitle)
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editing = null
                    showEditor = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp),
            ) { Icon(Icons.Default.Add, s.snippetsAdd) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Search box
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                placeholder = { Text(s.snippetsSearch) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = s.navBack)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            // Tag filtering chips (horizontal scrolling; “All” + each tag group)
            if (tags.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = filterTagId == null,
                        onClick = { filterTagId = null },
                        label = { Text(s.snippetsTagAll) },
                    )
                    tags.forEach { t ->
                        FilterChip(
                            selected = filterTagId == t.id,
                            onClick = { filterTagId = if (filterTagId == t.id) null else t.id },
                            label = { Text(t.name) },
                        )
                    }
                }
            }
            if (snippets.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            s.snippetsEmpty,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            s.snippetsEmptyHint,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            } else if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        s.hostsNoMatch,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { snippet ->
                        SnippetRow(
                            snippet = snippet,
                            tags = tags,
                            onClick = {
                                editing = snippet
                                showEditor = true
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    }
                }
            }
        }
    }

    // Overlay system Back: close the topmost layer first (tag page > edit page); do not fall through to AppRoot and exit the page.
    // (Compose return chain LIFO: enabled handlers registered later are triggered first; here, both levels occur after AppRoot)
    PlatformBackHandler(enabled = showTagPage) {
        showTagPage = false
        tags = repository.listTagGroups()
    }
    PlatformBackHandler(enabled = showEditor && !showTagPage) {
        showEditor = false
        snippets = repository.listSnippets()
        tags = repository.listTagGroups()
    }

    // Edit Page (New/Edit): Secondary Overlay
    AnimatedVisibility(
        visible = showEditor,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(240)),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(200)),
    ) {
        SnippetEditPage(
            repository = repository,
            existing = editing,
            onBack = {
                showEditor = false
                snippets = repository.listSnippets()
                tags = repository.listTagGroups()
            },
        )
    }
    // Tag Management Tab
    AnimatedVisibility(
        visible = showTagPage,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(240)),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(200)),
    ) {
        TagManagePage(
            repository = repository,
            onBack = {
                showTagPage = false
                tags = repository.listTagGroups()
            },
        )
    }
}

/** Snippet list rows: Name + Content preview + "chips" tag. */
@Composable
private fun SnippetRow(
    snippet: Snippet,
    tags: List<TagGroup>,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                snippet.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Content preview (monospace font, gray)
            Text(
                snippet.content.replace("\n", " ").take(24),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = monospaceFontFamily(),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val named = snippet.tagIds.mapNotNull { id -> tags.firstOrNull { it.id == id }?.name }
        if (named.isNotEmpty()) {
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                named.take(3).forEach { name ->
                    Text(
                        name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** Snippet new/editing form: Name / Command content / Multiple tag selection (selection from tag groups; do not enter manually). */
@Composable
private fun SnippetEditPage(
    repository: HostRepository,
    existing: Snippet?,
    onBack: () -> Unit,
) {
    val s = LocalAppStrings.current
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var content by remember { mutableStateOf(existing?.content ?: "") }
    var tagIds by remember { mutableStateOf(existing?.tagIds ?: emptyList()) }
    val tags = remember { repository.listTagGroups() }
    var pendingDelete by remember { mutableStateOf(false) }

    fun save() {
        val trimmedName = name.trim()
        val trimmedContent = content.trim()
        if (trimmedName.isEmpty() || trimmedContent.isEmpty()) return
        repository.upsertSnippet(
            Snippet(
                id = existing?.id ?: newId(),
                name = trimmedName,
                content = trimmedContent,
                tagIds = tagIds,
                updatedAt = Clock.System.now().toEpochMilliseconds(),
            ),
        )
        onBack()
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = s.navBack)
            }
            Text(
                if (existing == null) s.snippetsAdd else s.snippetEdit,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = monospaceFontFamily(),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (existing != null) {
                IconButton(onClick = { pendingDelete = true }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = s.snippetDelete,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            TextButton(onClick = { save() }, enabled = name.isNotBlank() && content.isNotBlank()) {
                Text(s.editSave, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(s.snippetName) },
                singleLine = true,
            )
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                label = { Text(s.snippetContent) },
                minLines = 3,
                textStyle = LocalTextStyle.current.copy(fontFamily = monospaceFontFamily()),
            )
            // Tags: Select multiple tags from the tag group (chips); no free-form input allowed
            Text(
                s.snippetTags,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
            )
            if (tags.isEmpty()) {
                Text(
                    s.tagsHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    tags.forEach { t ->
                        FilterChip(
                            selected = t.id in tagIds,
                            onClick = {
                                tagIds = if (t.id in tagIds) tagIds - t.id else tagIds + t.id
                            },
                            label = { Text(t.name) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (pendingDelete) {
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text(s.snippetDeleteConfirm) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = false
                    repository.deleteSnippet(existing!!.id)
                    onBack()
                }) { Text(s.snippetDelete, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) { Text(s.terminalCancel) }
            },
        )
    }
}

/** Tag Group Management Page: Add, delete, and edit (renaming updates all instances across the library; deletion cascades to clean up references). */
@Composable
fun TagManagePage(
    repository: HostRepository,
    onBack: () -> Unit,
) {
    val s = LocalAppStrings.current
    var tags by remember { mutableStateOf(repository.listTagGroups()) }
    var newName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<TagGroup?>(null) }
    val snippetCount = { id: String -> repository.listSnippets().count { id in it.tagIds } }

    fun addTag() {
        val name = newName.trim()
        if (name.isEmpty()) return
        // Do not create duplicate tags with the same name (case-insensitive), and avoid fragmentation of tag groups.
        if (tags.none { it.name.equals(name, ignoreCase = true) }) {
            repository.upsertTagGroup(TagGroup(newId(), name))
        }
        newName = ""
        tags = repository.listTagGroups()
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = s.navBack)
            }
            Text(
                s.tagsTitle,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = monospaceFontFamily(),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                s.tagsHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 12.dp),
            )
            if (tags.isEmpty()) {
                Text(
                    s.tagsEmpty,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            tags.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        t.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { pendingDelete = t }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = s.tagDelete,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            }
            // New tag row
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    modifier = Modifier.weight(1f),
                    label = { Text(s.tagName) },
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { addTag() }, enabled = newName.isNotBlank()) {
                    Text(s.tagAdd)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    pendingDelete?.let { tag ->
        val affected = snippetCount(tag.id)
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(s.tagDelete) },
            text = {
                Text(
                    if (affected > 0) s.tagDeleteConfirm(affected) else s.tagDeleteConfirm(0),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    repository.deleteTagGroup(tag.id)
                    pendingDelete = null
                    tags = repository.listTagGroups()
                }) { Text(s.tagDelete, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(s.terminalCancel) }
            },
        )
    }
}

/**
 * Terminal snippet insertion panel (triggered by “{}” on the keyboard toolbar): Search + tag filtering + list.
 * Tap = Insert the current input row (without pressing Enter; manually press Enter after filling in parameters); Long press = Execute or delete directly.
 * Bytes are sent directly to the remote end via `sendText`, naturally bypassing the IME composing state.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SnippetInsertSheet(
    repository: HostRepository,
    /** Terminal theme: The panel floats on top of the terminal canvas, and its color scheme follows the terminal’s (matching the Git panel; do not use the app theme). */
    theme: TerminalTheme,
    /** content = snippet content; run = true indicates direct execution (with a carriage return). */
    onUse: (content: String, run: Boolean) -> Unit,
    onDismiss: () -> Unit,
    allowRun: Boolean = true,
) {
    val s = LocalAppStrings.current
    var snippets by remember { mutableStateOf(repository.listSnippets()) }
    var tags by remember { mutableStateOf(repository.listTagGroups()) }
    var query by remember { mutableStateOf("") }
    var filterTagId by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<Snippet?>(null) }
    var pendingDelete by remember { mutableStateOf<Snippet?>(null) }

    /** Switch for the empty "New Snippet" dialog box. */
    var adding by remember { mutableStateOf(false) }

    val q = query.trim()
    val filtered =
        snippets
            .filter { filterTagId == null || it.tagIds.contains(filterTagId) }
            .filter {
                q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.content.contains(q, ignoreCase = true)
            }.sortedByDescending { it.updatedAt }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = theme.background(),
        contentColor = theme.foreground(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // Adjust the length based on screen ratio (no overflow on small screens, no squashed text on tablets); wrap text when content is sparse to avoid taking up screen space.
                .fillMaxHeight(0.72f)
                // Global `adjustNothing`: the keyboard does not resize the window; lists must yield space themselves,
                // Otherwise, the bottom half of the search results is obscured by the IME.
                .imePadding()
                .padding(horizontal = Spacing.Lg),
        ) {
            Text(
                s.snippetInsertTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = theme.foreground(),
            )
            Text(
                s.snippetInsertHint,
                style = MaterialTheme.typography.bodySmall,
                color = theme.foreground().copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(s.snippetsSearch, color = theme.foreground().copy(alpha = 0.45f)) },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = theme.foreground().copy(alpha = 0.6f))
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedTextColor = theme.foreground(),
                        unfocusedTextColor = theme.foreground(),
                        focusedBorderColor = theme.foreground().copy(alpha = 0.5f),
                        unfocusedBorderColor = theme.foreground().copy(alpha = 0.25f),
                        cursorColor = theme.cursor(),
                    ),
            )
            if (tags.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = filterTagId == null,
                        onClick = { filterTagId = null },
                        label = { Text(s.snippetsTagAll) },
                    )
                    tags.forEach { t ->
                        FilterChip(
                            selected = filterTagId == t.id,
                            onClick = { filterTagId = if (filterTagId == t.id) null else t.id },
                            label = { Text(t.name) },
                        )
                    }
                }
            }
            if (snippets.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            s.snippetsEmpty,
                            style = MaterialTheme.typography.bodyMedium,
                            color = theme.foreground().copy(alpha = 0.4f),
                        )
                        Text(
                            s.snippetsEmptyHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.foreground().copy(alpha = 0.35f),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        // New entry point for empty state: You can create one directly even without a command (no need to go through the settings page).
                        TextButton(onClick = { adding = true }) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text(s.snippetsAdd)
                        }
                    }
                }
            } else if (filtered.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        s.hostsNoMatch,
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.foreground().copy(alpha = 0.4f),
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(filtered, key = { it.id }) { snippet ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onUse(snippet.content, false) },
                                    onLongClick = { menuFor = snippet },
                                ).padding(horizontal = Spacing.Xs, vertical = Spacing.Sm),
                        ) {
                            // Place the name at the top (mnemonic is supplementary info); use a monospaced font with the main content width—the skimming target is the command itself.
                            Text(
                                snippet.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.foreground().copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                snippet.content.replace("\n", " ⏎ "),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = monospaceFontFamily(),
                                color = theme.foreground(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = Spacing.Xs),
                            )
                            val named = snippet.tagIds.mapNotNull { id -> tags.firstOrNull { it.id == id }?.name }
                            if (named.isNotEmpty()) {
                                Row(
                                    Modifier.padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    named.take(3).forEach { name ->
                                        Text(
                                            name,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = theme.foreground(),
                                            modifier =
                                                Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(theme.foreground().copy(alpha = 0.12f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = theme.foreground().copy(alpha = 0.1f))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // Long press the menu: Execute directly / Delete (must be rendered within the sheet; otherwise, it will be obscured by the dialog layer)
        menuFor?.let { snippet ->
            DropdownMenu(
                expanded = true,
                onDismissRequest = { menuFor = null },
            ) {
                if (allowRun) {
                    DropdownMenuItem(
                        text = { Text(s.snippetRun) },
                        onClick = {
                            menuFor = null
                            onUse(snippet.content, true)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(s.snippetDelete) },
                    onClick = {
                        menuFor = null
                        pendingDelete = snippet
                    },
                )
            }
        }

        pendingDelete?.let { snippet ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                containerColor = theme.background(),
                titleContentColor = theme.foreground(),
                textContentColor = theme.foreground(),
                title = { Text(s.snippetDeleteConfirm) },
                confirmButton = {
                    TextButton(onClick = {
                        repository.deleteSnippet(snippet.id)
                        pendingDelete = null
                        snippets = repository.listSnippets()
                    }) { Text(s.snippetDelete, color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { pendingDelete = null },
                    ) { Text(s.terminalCancel, color = theme.foreground()) }
                },
            )
        }

        // Empty-state add: Name + Content (lightweight form; list refreshes instantly after saving and can be used right away)
        if (adding) {
            var newName by remember { mutableStateOf("") }
            var newContent by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { adding = false },
                containerColor = theme.background(),
                titleContentColor = theme.foreground(),
                textContentColor = theme.foreground(),
                title = { Text(s.snippetsAdd) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text(s.snippetName) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = theme.foreground(),
                                    unfocusedTextColor = theme.foreground(),
                                    focusedBorderColor = theme.foreground().copy(alpha = 0.5f),
                                    unfocusedBorderColor = theme.foreground().copy(alpha = 0.25f),
                                    cursorColor = theme.cursor(),
                                ),
                        )
                        OutlinedTextField(
                            value = newContent,
                            onValueChange = { newContent = it },
                            label = { Text(s.snippetContent) },
                            minLines = 3,
                            textStyle = LocalTextStyle.current.copy(fontFamily = monospaceFontFamily()),
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = theme.foreground(),
                                    unfocusedTextColor = theme.foreground(),
                                    focusedBorderColor = theme.foreground().copy(alpha = 0.5f),
                                    unfocusedBorderColor = theme.foreground().copy(alpha = 0.25f),
                                    cursorColor = theme.cursor(),
                                ),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = newName.isNotBlank() && newContent.isNotBlank(),
                        onClick = {
                            repository.upsertSnippet(
                                Snippet(
                                    id = newId(),
                                    name = newName.trim(),
                                    content = newContent.trim(),
                                    tagIds = emptyList(),
                                    updatedAt = Clock.System.now().toEpochMilliseconds(),
                                ),
                            )
                            adding = false
                            snippets = repository.listSnippets()
                        },
                    ) { Text(s.editSave, color = theme.cursor()) }
                },
                dismissButton = {
                    TextButton(onClick = { adding = false }) { Text(s.terminalCancel, color = theme.foreground()) }
                },
            )
        }
    }
}
