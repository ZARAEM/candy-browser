package dev.sk2andy.materialbrowser.browser

/**
 * A named group of tabs inside one profile, Candy's counterpart of a Zen space. Tabs carry a
 * `spaceId`; tabs without one belong to the profile's first space.
 */
data class BrowserSpace(
    val id: String,
    val profileId: String,
    val name: String,
    val emoji: String,
    val accentHue: Int? = null,
    val zenSpaceId: String? = null,
)

data class BrowserSpaceSnapshot(
    val spaces: List<BrowserSpace>,
    val activeSpaceIds: Map<String, String>,
) {
    companion object {
        val EMPTY = BrowserSpaceSnapshot(emptyList(), emptyMap())
    }
}

object BrowserSpaceRules {
    const val MAX_SPACES_PER_PROFILE = 16
    const val MAX_SPACES = 96
    const val MAX_NAME_LENGTH = 40
    const val DEFAULT_EMOJI = "🗂️"

    fun spacesFor(spaces: List<BrowserSpace>, profileId: String): List<BrowserSpace> =
        spaces.filter { it.profileId == profileId }

    /** The space a tab belongs to: its own, else the profile's first space, else none. */
    fun spaceIdFor(tab: BrowserTab, spaces: List<BrowserSpace>): String? =
        tab.spaceId?.takeIf { id -> spaces.any { it.id == id && it.profileId == tab.profileId } }
            ?: spacesFor(spaces, tab.profileId).firstOrNull()?.id

    /** Active space for a profile: the remembered one when it still exists, else the first. */
    fun activeSpaceId(spaces: List<BrowserSpace>, activeSpaceIds: Map<String, String>, profileId: String): String? {
        val candidates = spacesFor(spaces, profileId)
        if (candidates.isEmpty()) return null
        return activeSpaceIds[profileId]?.takeIf { id -> candidates.any { it.id == id } } ?: candidates.first().id
    }

    fun tabsInSpace(tabs: List<BrowserTab>, spaces: List<BrowserSpace>, profileId: String, spaceId: String?): List<BrowserTab> =
        tabs.filter { tab -> tab.profileId == profileId && (spaceId == null || spaceIdFor(tab, spaces) == spaceId) }

    fun sanitizeName(value: String): String = value.trim().take(MAX_NAME_LENGTH)

    fun sanitizeEmoji(value: String): String = value.trim().take(8).ifEmpty { DEFAULT_EMOJI }

    fun canAdd(spaces: List<BrowserSpace>, profileId: String): Boolean =
        spaces.size < MAX_SPACES && spacesFor(spaces, profileId).size < MAX_SPACES_PER_PROFILE

    fun nextDefaultName(spaces: List<BrowserSpace>, profileId: String): String =
        "Space ${spacesFor(spaces, profileId).size + 1}"

    /** Drops spaces of unknown profiles, duplicates and excess entries deterministically. */
    fun sanitize(snapshot: BrowserSpaceSnapshot, profileIds: Set<String>): BrowserSpaceSnapshot {
        val seen = hashSetOf<String>()
        val perProfile = hashMapOf<String, Int>()
        val spaces = snapshot.spaces.filter { space ->
            val count = perProfile.getOrDefault(space.profileId, 0)
            val keep = space.id.isNotEmpty() &&
                space.profileId in profileIds &&
                seen.add(space.id) &&
                count < MAX_SPACES_PER_PROFILE
            if (keep) perProfile[space.profileId] = count + 1
            keep
        }.take(MAX_SPACES).map { space ->
            space.copy(name = sanitizeName(space.name).ifEmpty { "Space" }, emoji = sanitizeEmoji(space.emoji))
        }
        val activeSpaceIds = snapshot.activeSpaceIds.filter { (profileId, spaceId) ->
            spaces.any { it.profileId == profileId && it.id == spaceId }
        }
        return BrowserSpaceSnapshot(spaces, activeSpaceIds)
    }
}
