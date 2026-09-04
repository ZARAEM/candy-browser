package dev.sk2andy.firefoxsync

/**
 * Decoded records of Zen Browser's custom `spaces` Firefox Sync engine. Field names mirror
 * `ZenSpacesSyncModel.sys.mjs` so a Zen desktop and Candy agree on the wire shape. Opaque JSON
 * values that Zen owns (space themes, live-folder configuration) are carried as canonical JSON
 * text and never interpreted here.
 */
sealed interface ZenSpacesRecord {
    val id: String
}

/** A Firefox container. Built-in containers use the fixed ids `builtin-1` to `builtin-4`. */
data class ZenContainerRecord(
    override val id: String,
    val name: String,
    val icon: String,
    val color: String,
) : ZenSpacesRecord

data class ZenSpaceRecord(
    override val id: String,
    val name: String,
    val icon: String?,
    val themeJson: String?,
    val containerGuid: String?,
    val children: List<String>,
) : ZenSpacesRecord

/** A pinned or essential tab. Essentials carry no workspace; pinned tabs carry no essential flag. */
data class ZenTabRecord(
    override val id: String,
    val url: String,
    val title: String,
    val icon: String,
    val containerGuid: String?,
    val essential: Boolean,
    val workspaceUuid: String?,
    val folderId: String?,
    val staticLabel: String?,
    val hasStaticIcon: Boolean,
    val defaultContainer: Boolean,
) : ZenSpacesRecord

data class ZenFolderRecord(
    override val id: String,
    val name: String,
    val icon: String?,
    val workspaceUuid: String?,
    val parentFolderId: String?,
    val liveJson: String?,
    val children: List<String>,
) : ZenSpacesRecord

data class ZenSplitRecord(
    override val id: String,
    val gridType: String,
    val tabs: List<String>,
    val workspaceUuid: String?,
    val folderId: String?,
) : ZenSpacesRecord

/** Singleton record: space order plus essential tab order keyed by container guid or `default`. */
data class ZenLayoutRecord(
    val spaces: List<String>,
    val essentials: Map<String, List<String>>,
) : ZenSpacesRecord {
    override val id: String get() = ZenSpacesCodec.LAYOUT_RECORD_ID
}

data class ZenTombstoneRecord(override val id: String) : ZenSpacesRecord

/** Everything currently stored in the `spaces` collection, indexed for lookups. */
data class ZenSpacesSnapshot(
    val containers: Map<String, ZenContainerRecord>,
    val spaces: Map<String, ZenSpaceRecord>,
    val tabs: Map<String, ZenTabRecord>,
    val folders: Map<String, ZenFolderRecord>,
    val splits: Map<String, ZenSplitRecord>,
    val layout: ZenLayoutRecord?,
) {
    /** Spaces in the order Zen shows them: layout order first, then unknown-to-layout by name. */
    fun orderedSpaces(): List<ZenSpaceRecord> {
        val ordered = layout?.spaces.orEmpty().mapNotNull(spaces::get)
        val remaining = spaces.values.filterNot { it in ordered }.sortedWith(compareBy(ZenSpaceRecord::name, ZenSpaceRecord::id))
        return ordered + remaining
    }

    /** Pinned tabs of one space in strip order, following the space's child sequence. */
    fun pinnedTabs(spaceId: String): List<ZenTabRecord> =
        spaces[spaceId]?.children.orEmpty().mapNotNull(tabs::get).filterNot(ZenTabRecord::essential)

    /** Essential tabs for one container guid (or `default`) in layout order. */
    fun essentialTabs(containerKey: String): List<ZenTabRecord> =
        layout?.essentials?.get(containerKey).orEmpty().mapNotNull(tabs::get).filter(ZenTabRecord::essential)

    companion object {
        val EMPTY = ZenSpacesSnapshot(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), layout = null)
    }
}
