package dev.sk2andy.materialbrowser.browser

import dev.sk2andy.firefoxsync.ZenSpacesCodec
import dev.sk2andy.firefoxsync.ZenSpacesSnapshot
import dev.sk2andy.firefoxsync.ZenTabRecord
import dev.sk2andy.materialbrowser.browser.integration.BrowserUriPolicy

data class ZenMaterializedTab(
    val zenTabId: String,
    val url: String,
    val title: String,
    val profileId: String,
    val spaceId: String,
)

data class ZenSpaceMaterialization(
    val createdSpaces: List<BrowserSpace>,
    val updatedSpaces: List<BrowserSpace>,
    val newTabs: List<ZenMaterializedTab>,
    val skippedTabsForLimit: Int,
) {
    val changed: Boolean get() = createdSpaces.isNotEmpty() || updatedSpaces.isNotEmpty() || newTabs.isNotEmpty()
}

/**
 * Turns Zen spaces into Candy spaces and Zen pinned tabs into pinned Candy tabs. Read-only
 * direction: existing Candy tabs are never navigated or removed, tabs are matched by Zen id so a
 * later sync does not duplicate them, and the global tab limit is respected with pinned tabs first.
 * Essential tabs land once per profile in that profile's first space.
 */
object ZenSpaceMaterializeRules {
    fun materialize(
        snapshot: ZenSpacesSnapshot,
        profiles: List<BrowserProfile>,
        spaces: List<BrowserSpace>,
        tabs: List<BrowserTab>,
        defaultProfileId: String,
        maxTabs: Int = MAX_TABS,
        newSpaceId: (zenSpaceId: String) -> String,
    ): ZenSpaceMaterialization {
        val localProfileIds = profiles.filterNot(BrowserProfile::isSynced).mapTo(hashSetOf(), BrowserProfile::id)
        val fallbackProfileId = defaultProfileId.takeIf { it in localProfileIds } ?: localProfileIds.firstOrNull()
            ?: return ZenSpaceMaterialization(emptyList(), emptyList(), emptyList(), 0)
        val created = mutableListOf<BrowserSpace>()
        val updated = mutableListOf<BrowserSpace>()
        val working = spaces.toMutableList()
        val spaceIdByZen = hashMapOf<String, String>()

        snapshot.orderedSpaces().forEach { zenSpace ->
            val profileId = ZenContainerProfileRules.profileIdFor(zenSpace.containerGuid, profiles, fallbackProfileId)
            val name = BrowserSpaceRules.sanitizeName(zenSpace.name).ifEmpty { "Space" }
            val emoji = BrowserSpaceRules.sanitizeEmoji(zenSpace.icon.orEmpty().takeIf { it.length <= 8 } ?: "")
            val existing = working.firstOrNull { it.zenSpaceId == zenSpace.id && it.profileId == profileId }
            if (existing != null) {
                val target = existing.copy(name = name, emoji = emoji)
                if (target != existing) {
                    updated += target
                    working[working.indexOf(existing)] = target
                }
                spaceIdByZen[zenSpace.id] = existing.id
                return@forEach
            }
            if (!BrowserSpaceRules.canAdd(working, profileId)) return@forEach
            val space = BrowserSpace(
                id = newSpaceId(zenSpace.id),
                profileId = profileId,
                name = name,
                emoji = emoji,
                zenSpaceId = zenSpace.id,
            )
            created += space
            working += space
            spaceIdByZen[zenSpace.id] = space.id
        }

        val knownZenTabIds = tabs.mapNotNull(BrowserTab::zenTabId).toHashSet()
        var capacity = (maxTabs - tabs.size).coerceAtLeast(0)
        var skipped = 0
        val newTabs = mutableListOf<ZenMaterializedTab>()
        fun offer(tab: ZenTabRecord, profileId: String, spaceId: String) {
            if (tab.id in knownZenTabIds) return
            val url = BrowserUriPolicy.normalizeHttpUrl(tab.url) ?: return
            if (capacity <= 0) {
                skipped++
                return
            }
            capacity--
            knownZenTabIds += tab.id
            newTabs += ZenMaterializedTab(tab.id, url, tab.staticLabel ?: tab.title, profileId, spaceId)
        }
        snapshot.orderedSpaces().forEach { zenSpace ->
            val spaceId = spaceIdByZen[zenSpace.id] ?: return@forEach
            val profileId = working.first { it.id == spaceId }.profileId
            snapshot.pinnedTabs(zenSpace.id).forEach { tab -> offer(tab, profileId, spaceId) }
        }
        val essentialsByProfile = snapshot.tabs.values
            .filter(ZenTabRecord::essential)
            .sortedBy { it.id }
            .groupBy { tab -> ZenContainerProfileRules.profileIdFor(tab.containerGuid, profiles, fallbackProfileId) }
        essentialsByProfile.forEach { (profileId, essentials) ->
            val spaceId = BrowserSpaceRules.spacesFor(working, profileId).firstOrNull()?.id ?: return@forEach
            val ordered = snapshot.layout?.essentials?.values?.flatten().orEmpty()
            essentials.sortedBy { tab -> ordered.indexOf(tab.id).let { if (it < 0) Int.MAX_VALUE else it } }
                .forEach { tab -> offer(tab, profileId, spaceId) }
        }
        return ZenSpaceMaterialization(created, updated, newTabs, skipped)
    }

    fun isBuiltinContainer(guid: String?): Boolean = guid != null && ZenSpacesCodec.isBuiltinContainerGuid(guid)
}
