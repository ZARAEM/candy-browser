package dev.sk2andy.materialbrowser.browser

import dev.sk2andy.firefoxsync.ZenLayoutRecord
import dev.sk2andy.firefoxsync.ZenSpaceRecord
import dev.sk2andy.firefoxsync.ZenSpacesCodec
import dev.sk2andy.firefoxsync.ZenTabRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZenSpaceMaterializeRulesTest {
    private val profiles = listOf(
        DEFAULT_BROWSER_PROFILE,
        BrowserProfile("p-work", "💼", name = "Work", zenContainerGuid = "builtin-2"),
    )

    private fun tab(id: String, container: String? = null, space: String? = "ws-1", essential: Boolean = false, url: String = "https://$id.example/") =
        ZenTabRecord(id, url, id, "", container, essential, if (essential) null else space, null, null, false, false)

    private val snapshot = ZenSpacesCodec.assemble(
        listOf(
            ZenSpaceRecord("ws-1", "Research", "🔬", null, "builtin-2", listOf("tab-1", "tab-2")),
            ZenSpaceRecord("ws-2", "Home", null, null, null, listOf("tab-3")),
            tab("tab-1", "builtin-2"), tab("tab-2", "builtin-2"), tab("tab-3", space = "ws-2"),
            tab("ess-1", "builtin-2", essential = true), tab("ess-2", essential = true, url = "ftp://nope"),
            ZenLayoutRecord(listOf("ws-2", "ws-1"), mapOf("builtin-2" to listOf("ess-1"), "default" to listOf("ess-2"))),
        ),
    )

    @Test
    fun `spaces map to container profiles and pinned tabs become new pinned tabs`() {
        val result = ZenSpaceMaterializeRules.materialize(
            snapshot = snapshot,
            profiles = profiles,
            spaces = emptyList(),
            tabs = emptyList(),
            defaultProfileId = DEFAULT_PROFILE_ID,
            newSpaceId = { "space-$it" },
        )
        assertEquals(listOf("space-ws-2", "space-ws-1"), result.createdSpaces.map { it.id })
        assertEquals(listOf(DEFAULT_PROFILE_ID, "p-work"), result.createdSpaces.map { it.profileId })
        assertEquals("🔬", result.createdSpaces[1].emoji)
        assertEquals("🗂️", result.createdSpaces[0].emoji)
        assertEquals(listOf("tab-3", "tab-1", "tab-2", "ess-1"), result.newTabs.map { it.zenTabId })
        assertEquals("space-ws-1", result.newTabs.first { it.zenTabId == "ess-1" }.spaceId)
        assertEquals(0, result.skippedTabsForLimit)
    }

    @Test
    fun `re-running matches existing spaces and tabs and honours the tab limit`() {
        val first = ZenSpaceMaterializeRules.materialize(snapshot, profiles, emptyList(), emptyList(), DEFAULT_PROFILE_ID, newSpaceId = { "space-$it" })
        val existingTabs = first.newTabs.map { BrowserTab(id = "t-${it.zenTabId}", lastAccessedAt = 1L, profileId = it.profileId, spaceId = it.spaceId, zenTabId = it.zenTabId, url = it.url) }
        val again = ZenSpaceMaterializeRules.materialize(
            snapshot = ZenSpacesCodec.assemble(snapshot.spaces.values.map { if (it.id == "ws-1") it.copy(name = "Research 2") else it } + snapshot.tabs.values + listOfNotNull(snapshot.layout)),
            profiles = profiles,
            spaces = first.createdSpaces,
            tabs = existingTabs,
            defaultProfileId = DEFAULT_PROFILE_ID,
            newSpaceId = { error("no new spaces") },
        )
        assertTrue(again.createdSpaces.isEmpty() && again.newTabs.isEmpty())
        assertEquals(listOf("Research 2"), again.updatedSpaces.map { it.name })

        val limited = ZenSpaceMaterializeRules.materialize(snapshot, profiles, emptyList(), emptyList(), DEFAULT_PROFILE_ID, maxTabs = 2, newSpaceId = { "space-$it" })
        assertEquals(2, limited.newTabs.size)
        assertEquals(2, limited.skippedTabsForLimit)
    }

    @Test
    fun `without local profiles nothing is materialized`() {
        val result = ZenSpaceMaterializeRules.materialize(snapshot, listOf(BrowserProfile("synced:x", "💻", syncedDeviceId = "x")), emptyList(), emptyList(), "candy", newSpaceId = { it })
        assertTrue(!result.changed)
    }
}
