package dev.macodes.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostOrganizationTest {
    private val prod = HostFolder(id = "f-prod", name = "Production", createdAt = 1L)
    private val eu = HostFolder(id = "f-eu", name = "EU", parentId = "f-prod", createdAt = 2L)
    private val staging = HostFolder(id = "f-stg", name = "Staging", createdAt = 3L)
    private val folders = listOf(prod, eu, staging)

    private val hosts =
        listOf(
            Host(
                id = "h1",
                name = "edge-gw",
                hostname = "10.0.0.1",
                tags = listOf("prod", "edge", "Ubuntu"),
                createdAt = 1L,
            ),
            Host(
                id = "h2",
                name = "api-eu",
                hostname = "10.0.1.10",
                tags = listOf("Prod", "api"),
                folderId = "f-eu",
                createdAt = 2L,
            ),
            Host(
                id = "h3",
                name = "db-prod",
                hostname = "10.0.1.20",
                tags = listOf("db"),
                folderId = "f-prod",
                createdAt = 3L,
            ),
        )

    @Test
    fun buildsBreadcrumbsAndDetectsCycles() {
        assertEquals(listOf("f-prod", "f-eu"), buildFolderBreadcrumbs(folders, "f-eu").map { it.id })
        assertTrue(buildFolderBreadcrumbs(folders, null).isEmpty())

        assertTrue(wouldCreateFolderCycle(folders, "f-prod", "f-prod"))
        assertTrue(wouldCreateFolderCycle(folders, "f-prod", "f-eu"))
        assertFalse(wouldCreateFolderCycle(folders, "f-eu", "f-stg"))
        assertFalse(wouldCreateFolderCycle(folders, "f-prod", null))
    }

    @Test
    fun filtersByFolderTagAndQuery() {
        val rootView =
            filterHostOrganization(
                hosts = hosts,
                folders = folders,
                currentFolderId = null,
                selectedTag = null,
                query = "",
            )
        assertEquals(listOf("f-prod", "f-stg"), rootView.visibleFolders.map { it.id })
        assertEquals(listOf("h1"), rootView.visibleHosts.map { it.id })
        assertEquals(listOf("api", "db", "edge", "prod", "Ubuntu"), rootView.allTags)

        val prodView =
            filterHostOrganization(
                hosts = hosts,
                folders = folders,
                currentFolderId = "f-prod",
                selectedTag = null,
                query = "",
            )
        assertEquals(listOf("f-eu"), prodView.visibleFolders.map { it.id })
        assertEquals(listOf("h3"), prodView.visibleHosts.map { it.id })
        assertEquals(FolderCounts(hostCount = 1, subfolderCount = 1), countFolderContents(hosts, folders, "f-prod"))

        // Selecting a tag searches globally across folders and hides folder rows
        val tagView =
            filterHostOrganization(
                hosts = hosts,
                folders = folders,
                currentFolderId = null,
                selectedTag = "prod",
                query = "",
            )
        assertTrue(tagView.visibleFolders.isEmpty())
        assertEquals(listOf("h1", "h2"), tagView.visibleHosts.map { it.id })
    }
}
