package dev.sk2andy.materialbrowser.browser

import dev.sk2andy.firefoxsync.ZenContainerRecord
import dev.sk2andy.firefoxsync.ZenSpacesCodec

data class ZenContainerReconciliation(
    val created: List<BrowserProfile>,
    val updated: List<BrowserProfile>,
    val unchangedIds: List<String>,
    val skippedForLimit: List<String>,
) {
    val changed: Boolean get() = created.isNotEmpty() || updated.isNotEmpty()
}

/**
 * Maps Zen containers onto Candy profiles. A container becomes a local, isolated profile tagged
 * with its Zen guid, so later syncs update the same profile instead of creating duplicates. Zen
 * tombstones never delete Candy profiles; they only leave the guid unmatched.
 */
object ZenContainerProfileRules {
    private val iconEmojis = mapOf(
        "fingerprint" to "🪪",
        "briefcase" to "💼",
        "dollar" to "💵",
        "cart" to "🛒",
        "circle" to "⚪",
        "gift" to "🎁",
        "vacation" to "🏖️",
        "food" to "🍔",
        "fruit" to "🍎",
        "pet" to "🐾",
        "tree" to "🌳",
        "chill" to "❄️",
        "fence" to "🚧",
    )
    private val colorHues = mapOf(
        "blue" to 210,
        "turquoise" to 180,
        "green" to 120,
        "yellow" to 50,
        "orange" to 30,
        "red" to 0,
        "pink" to 330,
        "purple" to 270,
    )
    const val DEFAULT_EMOJI = "📦"

    fun emojiFor(iconName: String): String = iconEmojis[iconName.trim().lowercase()] ?: DEFAULT_EMOJI

    fun accentHueFor(color: String): Int? = colorHues[color.trim().lowercase()]

    fun profileName(container: ZenContainerRecord): String =
        container.name.trim().take(MAX_PROFILE_NAME_LENGTH).ifEmpty { container.id.take(MAX_PROFILE_NAME_LENGTH) }

    fun reconcile(
        containers: Collection<ZenContainerRecord>,
        existingProfiles: List<BrowserProfile>,
        maxProfiles: Int = MAX_PROFILES,
        isolationSupported: Boolean,
        newProfileId: (ZenContainerRecord) -> String,
    ): ZenContainerReconciliation {
        val byGuid = existingProfiles.filterNot(BrowserProfile::isSynced).associateBy { it.zenContainerGuid }
        val created = mutableListOf<BrowserProfile>()
        val updated = mutableListOf<BrowserProfile>()
        val unchanged = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        var localCount = existingProfiles.count { !it.isSynced }
        containers
            .filter { it.id.isNotEmpty() && (ZenSpacesCodec.isBuiltinContainerGuid(it.id) || it.id.length <= 64) }
            .sortedWith(compareBy({ !ZenSpacesCodec.isBuiltinContainerGuid(it.id) }, { it.id }))
            .forEach { container ->
                val existing = byGuid[container.id]
                if (existing != null) {
                    val target = existing.copy(
                        name = profileName(container),
                        emoji = emojiFor(container.icon),
                        accentHue = accentHueFor(container.color),
                    )
                    if (target == existing) unchanged += existing.id else updated += target
                    return@forEach
                }
                if (localCount >= maxProfiles) {
                    skipped += container.id
                    return@forEach
                }
                localCount++
                created += BrowserProfile(
                    id = newProfileId(container),
                    emoji = emojiFor(container.icon),
                    isolationEnabled = isolationSupported,
                    name = profileName(container),
                    accentHue = accentHueFor(container.color),
                    zenContainerGuid = container.id,
                )
            }
        return ZenContainerReconciliation(created, updated, unchanged, skipped)
    }

    /** Resolves the Candy profile for a synced tab: its container's profile, else the fallback. */
    fun profileIdFor(containerGuid: String?, profiles: List<BrowserProfile>, fallbackProfileId: String): String =
        containerGuid
            ?.let { guid -> profiles.firstOrNull { !it.isSynced && it.zenContainerGuid == guid } }
            ?.id
            ?: fallbackProfileId
}
