package dev.macodes.data

enum class HostSortOrder {
    NAME,
    RECENTLY_USED,
}

data class HostFilterState(
    val selectedTags: Set<String> = emptySet(),
    val selectedProtocols: Set<ConnectionMode> = emptySet(),
    val selectedProviders: Set<CloudProvider> = emptySet(),
    val pinnedOnly: Boolean = false,
    val sortOrder: HostSortOrder = HostSortOrder.NAME,
) {
    val hasActiveFilters: Boolean
        get() =
            selectedTags.isNotEmpty() ||
                selectedProtocols.isNotEmpty() ||
                selectedProviders.isNotEmpty() ||
                pinnedOnly
}

data class FolderCounts(
    val hostCount: Int,
    val subfolderCount: Int,
)

data class HostOrganizationView(
    val breadcrumbs: List<HostFolder>,
    val visibleFolders: List<HostFolder>,
    val visibleHosts: List<Host>,
    val allTags: List<String>,
)

fun buildFolderBreadcrumbs(
    folders: List<HostFolder>,
    currentFolderId: String?,
): List<HostFolder> {
    if (currentFolderId == null) return emptyList()
    val byId = folders.associateBy { it.id }
    val trail = ArrayList<HostFolder>()
    val visited = HashSet<String>()
    var cursor = byId[currentFolderId]
    while (cursor != null && visited.add(cursor.id)) {
        trail.add(cursor)
        cursor = cursor.parentId?.let(byId::get)
    }
    return trail.asReversed()
}

fun wouldCreateFolderCycle(
    folders: List<HostFolder>,
    folderId: String,
    candidateParentId: String?,
): Boolean {
    if (candidateParentId == null) return false
    if (candidateParentId == folderId) return true
    val byId = folders.associateBy { it.id }
    val visited = HashSet<String>()
    var cursor = byId[candidateParentId]
    while (cursor != null && visited.add(cursor.id)) {
        if (cursor.id == folderId) return true
        cursor = cursor.parentId?.let(byId::get)
    }
    return false
}

fun collectDescendantFolderIds(
    folders: List<HostFolder>,
    rootFolderId: String,
): Set<String> {
    val byParent = folders.groupBy { it.parentId }
    val collected = LinkedHashSet<String>()
    val queue = ArrayDeque<String>()
    queue.add(rootFolderId)
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        if (!collected.add(id)) continue
        byParent[id].orEmpty().forEach { child ->
            if (child.id !in collected) queue.add(child.id)
        }
    }
    return collected
}

fun collectAllTags(
    hosts: List<Host>,
    definitions: List<HostTag> = emptyList(),
): List<String> {
    val seen = LinkedHashMap<String, String>()
    for (def in definitions) {
        val trimmed = def.name.trim().removePrefix("#")
        if (trimmed.isNotEmpty()) {
            val key = trimmed.lowercase()
            if (key !in seen) seen[key] = trimmed
        }
    }
    for (host in hosts) {
        for (raw in host.tags) {
            val trimmed = raw.trim().removePrefix("#")
            if (trimmed.isNotEmpty()) {
                val key = trimmed.lowercase()
                if (key !in seen) {
                    seen[key] = trimmed
                }
            }
        }
    }
    return seen.values.sortedWith(compareBy { it.lowercase() })
}

fun countFolderContents(
    hosts: List<Host>,
    folders: List<HostFolder>,
    folderId: String,
): FolderCounts {
    val validIds = folders.mapTo(HashSet()) { it.id }
    val directSubfolders = folders.count { it.parentId == folderId }
    val directHosts =
        hosts.count { h ->
            val resolvedFolderId = h.folderId?.takeIf { it in validIds }
            resolvedFolderId == folderId
        }
    return FolderCounts(hostCount = directHosts, subfolderCount = directSubfolders)
}

fun findPotentialDuplicateHosts(
    candidate: Host,
    existingHosts: List<Host>,
): List<Host> {
    val targetHost = candidate.hostname.trim().lowercase()
    if (targetHost.isEmpty()) return emptyList()
    val targetUser = candidate.username.trim().ifBlank { "root" }
    return existingHosts.filter { other ->
        other.id != candidate.id &&
            other.hostname.trim().lowercase() == targetHost &&
            other.port == candidate.port &&
            other.username.trim().ifBlank { "root" } == targetUser
    }
}

fun formatSafeSshCommand(host: Host): String {
    val user = host.username.trim().ifBlank { "root" }
    val target = "$user@${host.hostname.trim()}"
    return if (host.connectionMode == ConnectionMode.MOSH) {
        if (host.port != 22) "mosh --ssh=\"ssh -p ${host.port}\" $target" else "mosh $target"
    } else {
        if (host.port != 22) "ssh -p ${host.port} $target" else "ssh $target"
    }
}

fun filterHostOrganization(
    hosts: List<Host>,
    folders: List<HostFolder>,
    currentFolderId: String?,
    filterState: HostFilterState = HostFilterState(),
    query: String = "",
    tagDefinitions: List<HostTag> = emptyList(),
): HostOrganizationView {
    val validFolderIds = folders.mapTo(HashSet()) { it.id }
    val activeFolderId = currentFolderId?.takeIf { it in validFolderIds }
    val trimmedQuery = query.trim()
    val hasGlobalFilter = trimmedQuery.isNotEmpty() || filterState.hasActiveFilters

    val breadcrumbs = buildFolderBreadcrumbs(folders, activeFolderId)
    val allTags = collectAllTags(hosts, tagDefinitions)
    val breadcrumbNamesByFolderId =
        folders.associate { f ->
            f.id to buildFolderBreadcrumbs(folders, f.id).map { it.name }
        }

    val visibleFolders =
        if (hasGlobalFilter) {
            emptyList()
        } else {
            folders
                .filter { f ->
                    val parent = f.parentId?.takeIf { it in validFolderIds }
                    parent == activeFolderId
                }.sortedWith(compareBy<HostFolder> { it.name.lowercase() }.thenBy { it.createdAt })
        }

    val normalizedSelectedTags =
        filterState.selectedTags.map { it.trim().removePrefix("#").lowercase() }.filter { it.isNotEmpty() }

    val comparator =
        when (filterState.sortOrder) {
            HostSortOrder.NAME ->
                compareByDescending<Host> { it.isPinned }
                    .thenBy { it.name.ifBlank { it.hostname }.lowercase() }
                    .thenBy { it.createdAt }
            HostSortOrder.RECENTLY_USED ->
                compareByDescending<Host> { it.isPinned }
                    .thenByDescending { it.lastConnectedAt }
                    .thenBy { it.name.ifBlank { it.hostname }.lowercase() }
        }

    val visibleHosts =
        hosts
            .filter { h ->
                val resolvedFolderId = h.folderId?.takeIf { it in validFolderIds }
                // Root view displays "All Hosts" across all folders; entering a folder scopes to direct children.
                val inFolderScope = hasGlobalFilter || activeFolderId == null || resolvedFolderId == activeFolderId

                val hostTagKeys = h.tags.mapTo(HashSet()) { it.trim().removePrefix("#").lowercase() }
                val matchesTags = normalizedSelectedTags.all { it in hostTagKeys }

                val matchesProtocol =
                    filterState.selectedProtocols.isEmpty() || h.connectionMode in filterState.selectedProtocols

                val matchesProvider =
                    filterState.selectedProviders.isEmpty() ||
                        (h.cloudProvider != null && h.cloudProvider in filterState.selectedProviders)

                val matchesPinned = !filterState.pinnedOnly || h.isPinned

                val matchesQuery =
                    trimmedQuery.isEmpty() ||
                        h.name.contains(trimmedQuery, ignoreCase = true) ||
                        h.hostname.contains(trimmedQuery, ignoreCase = true) ||
                        h.username.contains(trimmedQuery, ignoreCase = true) ||
                        h.port.toString().contains(trimmedQuery) ||
                        h.system.contains(trimmedQuery, ignoreCase = true) ||
                        h.tags.any { it.contains(trimmedQuery, ignoreCase = true) } ||
                        (
                            h.cloudProvider?.let { p ->
                                p.displayName.contains(trimmedQuery, ignoreCase = true) ||
                                    p.shortBadge.contains(trimmedQuery, ignoreCase = true)
                            } == true
                        ) ||
                        (
                            resolvedFolderId?.let { fid ->
                                breadcrumbNamesByFolderId[fid].orEmpty().any {
                                    it.contains(trimmedQuery, ignoreCase = true)
                                }
                            } == true
                        )

                inFolderScope && matchesTags && matchesProtocol && matchesProvider && matchesPinned && matchesQuery
            }.sortedWith(comparator)

    return HostOrganizationView(
        breadcrumbs = breadcrumbs,
        visibleFolders = visibleFolders,
        visibleHosts = visibleHosts,
        allTags = allTags,
    )
}
