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
                port = 22,
                username = "ubuntu",
                tags = listOf("prod", "edge", "Ubuntu"),
                cloudProvider = CloudProvider.AWS,
                isPinned = false,
                lastConnectedAt = 100L,
                createdAt = 1L,
            ),
            Host(
                id = "h2",
                name = "api-eu",
                hostname = "10.0.1.10",
                port = 2222,
                username = "deploy",
                connectionMode = ConnectionMode.MOSH,
                tags = listOf("Prod", "api"),
                folderId = "f-eu",
                cloudProvider = CloudProvider.GCP,
                isPinned = true,
                lastConnectedAt = 300L,
                createdAt = 2L,
            ),
            Host(
                id = "h3",
                name = "db-prod",
                hostname = "10.0.1.20",
                username = "postgres",
                tags = listOf("db"),
                folderId = "f-prod",
                cloudProvider = CloudProvider.DIGITALOCEAN,
                lastConnectedAt = 200L,
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
        assertEquals(setOf("f-prod", "f-eu"), collectDescendantFolderIds(folders, "f-prod"))
    }

    @Test
    fun filtersByFolderTagProviderProtocolPinAndQuery() {
        // Root view displays root folders and all hosts, with pinned hosts sorted to the top without duplication
        val rootView =
            filterHostOrganization(
                hosts = hosts,
                folders = folders,
                currentFolderId = null,
                filterState = HostFilterState(),
                query = "",
            )
        assertEquals(listOf("f-prod", "f-stg"), rootView.visibleFolders.map { it.id })
        assertEquals(listOf("h2", "h3", "h1"), rootView.visibleHosts.map { it.id })
        assertEquals(listOf("api", "db", "edge", "prod", "Ubuntu"), rootView.allTags)

        // Inside a folder displays direct subfolders and direct hosts only
        val prodView =
            filterHostOrganization(
                hosts = hosts,
                folders = folders,
                currentFolderId = "f-prod",
                filterState = HostFilterState(),
                query = "",
            )
        assertEquals(listOf("f-eu"), prodView.visibleFolders.map { it.id })
        assertEquals(listOf("h3"), prodView.visibleHosts.map { it.id })
        assertEquals(FolderCounts(hostCount = 1, subfolderCount = 1), countFolderContents(hosts, folders, "f-prod"))

        // Multi-tag uses AND; categories combine with AND
        val andTagView =
            filterHostOrganization(
                hosts = hosts,
                folders = folders,
                currentFolderId = null,
                filterState = HostFilterState(selectedTags = setOf("prod", "api")),
                query = "",
            )
        assertTrue(andTagView.visibleFolders.isEmpty())
        assertEquals(listOf("h2"), andTagView.visibleHosts.map { it.id })

        // Multiple values within CloudProvider category use OR
        val providerView =
            filterHostOrganization(
                hosts = hosts,
                folders = folders,
                currentFolderId = null,
                filterState =
                    HostFilterState(
                        selectedProviders = setOf(CloudProvider.AWS, CloudProvider.DIGITALOCEAN),
                        sortOrder = HostSortOrder.RECENTLY_USED,
                    ),
                query = "",
            )
        assertEquals(listOf("h3", "h1"), providerView.visibleHosts.map { it.id })

        // Search spans port, nested folder name, and cloud provider
        assertEquals(
            listOf("h2"),
            filterHostOrganization(hosts, folders, null, HostFilterState(), query = "2222").visibleHosts.map { it.id },
        )
        assertEquals(
            listOf("h2"),
            filterHostOrganization(hosts, folders, null, HostFilterState(), query = "EU").visibleHosts.map { it.id },
        )
        assertEquals(
            listOf("h3"),
            filterHostOrganization(hosts, folders, null, HostFilterState(), query = "DigitalOcean").visibleHosts.map { it.id },
        )
    }

    @Test
    fun detectsPotentialDuplicatesAndFormatsSafeCommands() {
        val candidate =
            Host(
                id = "new-host",
                name = "duplicate",
                hostname = "10.0.1.10",
                port = 2222,
                username = "deploy",
            )
        assertEquals(listOf("h2"), findPotentialDuplicateHosts(candidate, hosts).map { it.id })
        assertEquals("ssh ubuntu@10.0.0.1", formatSafeSshCommand(hosts[0]))
        assertEquals("mosh --ssh=\"ssh -p 2222\" deploy@10.0.1.10", formatSafeSshCommand(hosts[1]))
    }
}
