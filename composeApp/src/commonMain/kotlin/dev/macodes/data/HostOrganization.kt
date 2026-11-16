package dev.macodes.data

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

fun collectAllTags(hosts: List<Host>): List<String> {
    val seen = LinkedHashMap<String, String>()
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

fun filterHostOrganization(
    hosts: List<Host>,
    folders: List<HostFolder>,
    currentFolderId: String?,
    selectedTag: String?,
    query: String,
): HostOrganizationView {
    val validFolderIds = folders.mapTo(HashSet()) { it.id }
    val activeFolderId = currentFolderId?.takeIf { it in validFolderIds }
    val trimmedQuery = query.trim()
    val hasGlobalFilter = trimmedQuery.isNotEmpty() || !selectedTag.isNullOrBlank()

    val breadcrumbs = buildFolderBreadcrumbs(folders, activeFolderId)
    val allTags = collectAllTags(hosts)

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

    val visibleHosts =
        hosts
            .filter { h ->
                val resolvedFolderId = h.folderId?.takeIf { it in validFolderIds }
                val inFolderScope = hasGlobalFilter || resolvedFolderId == activeFolderId
                val matchesTag =
                    selectedTag.isNullOrBlank() ||
                        h.tags.any { it.trim().removePrefix("#").equals(selectedTag, ignoreCase = true) }
                val matchesQuery =
                    trimmedQuery.isEmpty() ||
                        h.name.contains(trimmedQuery, ignoreCase = true) ||
                        h.hostname.contains(trimmedQuery, ignoreCase = true) ||
                        h.username.contains(trimmedQuery, ignoreCase = true) ||
                        h.system.contains(trimmedQuery, ignoreCase = true) ||
                        h.tags.any { it.contains(trimmedQuery, ignoreCase = true) }
                inFolderScope && matchesTag && matchesQuery
            }.sortedWith(compareBy<Host> { it.createdAt }.thenBy { it.name.lowercase() })

    return HostOrganizationView(
        breadcrumbs = breadcrumbs,
        visibleFolders = visibleFolders,
        visibleHosts = visibleHosts,
        allTags = allTags,
    )
}
