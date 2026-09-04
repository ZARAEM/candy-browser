package dev.sk2andy.materialbrowser.browser

data class BrowserProfile(
    val id: String,
    val emoji: String,
    val selectedTabId: String? = null,
    val isolationEnabled: Boolean = false,
    val syncedDeviceId: String? = null,
    val syncedDisplayName: String? = null,
    val syncedIconCatalogId: String? = null,
    val syncedIconEmoji: String? = null,
    val syncedIconAccentHue: Int? = null,
    val linkedSyncDeviceId: String? = null,
    val name: String? = null,
    val accentHue: Int? = null,
    val zenContainerGuid: String? = null,
)

/** Label shown beside the emoji: the local name, the synced device name, or nothing. */
val BrowserProfile.displayLabel: String?
    get() = name?.takeIf(String::isNotBlank) ?: syncedDisplayName?.takeIf(String::isNotBlank)

/** Accent hue for chrome tinting: local containers first, then synced device icons. */
val BrowserProfile.effectiveAccentHue: Int?
    get() = accentHue ?: syncedIconAccentHue

const val MAX_PROFILE_NAME_LENGTH = 40

val BrowserProfile.isSynced: Boolean
    get() = syncedDeviceId != null

val BrowserProfile.isSyncLinked: Boolean
    get() = syncedDeviceId != null || linkedSyncDeviceId != null

const val DEFAULT_PROFILE_ID = "candy"
const val DEFAULT_PROFILE_EMOJI = "🍬"
const val MAX_PROFILES = 12

val DEFAULT_BROWSER_PROFILE = BrowserProfile(
    id = DEFAULT_PROFILE_ID,
    emoji = DEFAULT_PROFILE_EMOJI,
)
