package dev.sk2andy.materialbrowser.browser

import dev.sk2andy.firefoxsync.ZenContainerRecord
import dev.sk2andy.firefoxsync.ZenFolderRecord
import dev.sk2andy.firefoxsync.ZenLayoutRecord
import dev.sk2andy.firefoxsync.ZenSpaceRecord
import dev.sk2andy.firefoxsync.ZenSpacesCodec
import dev.sk2andy.firefoxsync.ZenSplitRecord
import dev.sk2andy.firefoxsync.ZenTabRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZenSpacesViewRulesTest {
    private fun tab(id: String, essential: Boolean = false, container: String? = null, space: String? = "ws-1") =
        ZenTabRecord(id, "https://$id.example/", id, "", container, essential, if (essential) null else space, null, null, false, false)

    @Test
    fun `spaces flatten children in order with nested folders and splits`() {
        val snapshot = ZenSpacesCodec.assemble(
            listOf(
                ZenContainerRecord("builtin-2", "Work", "briefcase", "orange"),
                ZenSpaceRecord("ws-1", "Work", null, null, "builtin-2", listOf("tab-1", "folder-1", "split-1", "missing", "tab-1")),
                ZenFolderRecord("folder-1", "Docs", null, "ws-1", null, null, listOf("tab-2", "folder-2")),
                ZenFolderRecord("folder-2", "Deep", null, "ws-1", "folder-1", null, listOf("tab-3")),
                ZenSplitRecord("split-1", "grid", listOf("tab-4", "tab-5"), "ws-1", null),
                tab("tab-1"), tab("tab-2"), tab("tab-3"), tab("tab-4"), tab("tab-5"),
                ZenLayoutRecord(listOf("ws-1"), emptyMap()),
            ),
        )
        val view = ZenSpacesViewRules.build(snapshot)
        val space = view.spaces.single()
        assertEquals("Work", space.container?.name)
        assertEquals(
            listOf("tab-1@0", "folder-1@0", "tab-2@1", "folder-2@1", "tab-3@2", "split-1@0"),
            space.items.map { item ->
                when (item) {
                    is ZenSpaceItem.Tab -> "${item.record.id}@${item.depth}"
                    is ZenSpaceItem.Folder -> "${item.record.id}@${item.depth}"
                    is ZenSpaceItem.Split -> "${item.id}@${item.depth}"
                }
            },
        )
        assertEquals(listOf("tab-4", "tab-5"), (space.items.last() as ZenSpaceItem.Split).tabs.map { it.id })
        assertTrue(view.essentials.isEmpty())
    }

    @Test
    fun `essentials group by container in layout order and orphans fall back to default`() {
        val snapshot = ZenSpacesCodec.assemble(
            listOf(
                ZenContainerRecord("builtin-1", "Personal", "fingerprint", "blue"),
                ZenSpaceRecord("ws-1", "Home", null, null, null, emptyList()),
                tab("e-1", essential = true, container = "builtin-1"),
                tab("e-2", essential = true),
                tab("e-3", essential = true),
                ZenLayoutRecord(listOf("ws-1"), mapOf("builtin-1" to listOf("e-1"), "default" to listOf("e-2"))),
            ),
        )
        val view = ZenSpacesViewRules.build(snapshot)
        assertEquals(listOf("default", "builtin-1", "default"), view.essentials.map { it.containerKey })
        assertEquals(listOf("e-2"), view.essentials[0].tabs.map { it.id })
        assertEquals("Personal", view.essentials[1].container?.name)
        assertEquals(listOf("e-3"), view.essentials[2].tabs.map { it.id })
        assertTrue(view.spaces.single().items.isEmpty())
    }

    @Test
    fun `folder cycles and depth are bounded`() {
        val snapshot = ZenSpacesCodec.assemble(
            listOf(
                ZenSpaceRecord("ws-1", "Loop", null, null, null, listOf("folder-a")),
                ZenFolderRecord("folder-a", "A", null, "ws-1", null, null, listOf("folder-b")),
                ZenFolderRecord("folder-b", "B", null, "ws-1", "folder-a", null, listOf("folder-a", "tab-1")),
                tab("tab-1"),
            ),
        )
        val items = ZenSpacesViewRules.build(snapshot).spaces.single().items
        assertEquals(3, items.size)
        assertEquals(2, (items.last() as ZenSpaceItem.Tab).depth)
    }
}
