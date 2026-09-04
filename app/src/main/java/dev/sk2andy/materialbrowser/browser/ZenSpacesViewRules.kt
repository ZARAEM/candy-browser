package dev.sk2andy.materialbrowser.browser

import dev.sk2andy.firefoxsync.ZenContainerRecord
import dev.sk2andy.firefoxsync.ZenFolderRecord
import dev.sk2andy.firefoxsync.ZenSpaceRecord
import dev.sk2andy.firefoxsync.ZenSpacesCodec
import dev.sk2andy.firefoxsync.ZenSpacesSnapshot
import dev.sk2andy.firefoxsync.ZenTabRecord

/** One row in the Zen spaces viewer, already flattened for a lazy list. */
sealed interface ZenSpaceItem {
    val depth: Int

    data class Tab(val record: ZenTabRecord, override val depth: Int) : ZenSpaceItem
    data class Folder(val record: ZenFolderRecord, override val depth: Int) : ZenSpaceItem
    data class Split(val id: String, val gridType: String, val tabs: List<ZenTabRecord>, override val depth: Int) : ZenSpaceItem
}

data class ZenSpaceView(
    val space: ZenSpaceRecord,
    val container: ZenContainerRecord?,
    val items: List<ZenSpaceItem>,
)

data class ZenEssentialsView(
    val containerKey: String,
    val container: ZenContainerRecord?,
    val tabs: List<ZenTabRecord>,
)

data class ZenSpacesView(
    val spaces: List<ZenSpaceView>,
    val essentials: List<ZenEssentialsView>,
) {
    val isEmpty: Boolean get() = spaces.isEmpty() && essentials.isEmpty()
}

/** Pure projection of a [ZenSpacesSnapshot] into ordered, nested view rows. */
object ZenSpacesViewRules {
    const val MAX_DEPTH = 8
    const val MAX_ITEMS_PER_SPACE = 2_000

    fun build(snapshot: ZenSpacesSnapshot): ZenSpacesView {
        val spaces = snapshot.orderedSpaces().map { space ->
            val items = mutableListOf<ZenSpaceItem>()
            val seen = hashSetOf<String>()
            appendChildren(snapshot, space.children, depth = 0, seen = seen, into = items)
            ZenSpaceView(
                space = space,
                container = space.containerGuid?.let(snapshot.containers::get),
                items = items,
            )
        }
        val essentialKeys = snapshot.layout?.essentials?.keys.orEmpty()
        val orderedKeys = essentialKeys.sortedWith(compareBy({ it != ZenSpacesCodec.DEFAULT_ESSENTIALS_KEY }, { it }))
        val essentials = orderedKeys.mapNotNull { key ->
            val tabs = snapshot.essentialTabs(key)
            if (tabs.isEmpty()) null else ZenEssentialsView(key, snapshot.containers[key], tabs)
        }
        val listedEssentialIds = essentials.flatMap { it.tabs }.mapTo(hashSetOf()) { it.id }
        val orphanEssentials = snapshot.tabs.values
            .filter { it.essential && it.id !in listedEssentialIds }
            .sortedBy { it.id }
        val withOrphans = if (orphanEssentials.isEmpty()) {
            essentials
        } else {
            essentials + ZenEssentialsView(ZenSpacesCodec.DEFAULT_ESSENTIALS_KEY, null, orphanEssentials)
        }
        return ZenSpacesView(spaces = spaces, essentials = withOrphans)
    }

    private fun appendChildren(
        snapshot: ZenSpacesSnapshot,
        children: List<String>,
        depth: Int,
        seen: MutableSet<String>,
        into: MutableList<ZenSpaceItem>,
    ) {
        if (depth > MAX_DEPTH) return
        children.forEach { childId ->
            if (into.size >= MAX_ITEMS_PER_SPACE || !seen.add(childId)) return@forEach
            snapshot.tabs[childId]?.let { tab ->
                if (!tab.essential) into += ZenSpaceItem.Tab(tab, depth)
                return@forEach
            }
            snapshot.folders[childId]?.let { folder ->
                into += ZenSpaceItem.Folder(folder, depth)
                appendChildren(snapshot, folder.children, depth + 1, seen, into)
                return@forEach
            }
            snapshot.splits[childId]?.let { split ->
                val tabs = split.tabs.mapNotNull(snapshot.tabs::get)
                if (tabs.isNotEmpty()) into += ZenSpaceItem.Split(split.id, split.gridType, tabs, depth)
            }
        }
    }
}
