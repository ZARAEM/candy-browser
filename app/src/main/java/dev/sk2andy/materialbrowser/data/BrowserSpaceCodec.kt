package dev.sk2andy.materialbrowser.data

import dev.sk2andy.materialbrowser.browser.BrowserSpace
import dev.sk2andy.materialbrowser.browser.BrowserSpaceRules
import dev.sk2andy.materialbrowser.browser.BrowserSpaceSnapshot
import org.json.JSONArray
import org.json.JSONObject

/** Pure JSON codec for the spaces store so the format is testable without Android. */
internal object BrowserSpaceCodec {
    const val CURRENT_VERSION = 1
    const val MAX_JSON_BYTES = 256 * 1024

    fun encode(snapshot: BrowserSpaceSnapshot): String {
        val spaces = JSONArray()
        snapshot.spaces.forEach { space ->
            spaces.put(
                JSONObject()
                    .put("id", space.id)
                    .put("profileId", space.profileId)
                    .put("name", space.name)
                    .put("emoji", space.emoji)
                    .put("accentHue", space.accentHue)
                    .put("zenSpaceId", space.zenSpaceId),
            )
        }
        val active = JSONObject()
        snapshot.activeSpaceIds.toSortedMap().forEach { (profileId, spaceId) -> active.put(profileId, spaceId) }
        return JSONObject()
            .put("version", CURRENT_VERSION)
            .put("spaces", spaces)
            .put("activeSpaceIds", active)
            .toString()
    }

    fun decode(raw: String): BrowserSpaceSnapshot {
        if (raw.length > MAX_JSON_BYTES) return BrowserSpaceSnapshot.EMPTY
        return runCatching {
            val root = JSONObject(raw)
            val version = root.optInt("version", 0)
            if (version !in 1..CURRENT_VERSION) return@runCatching BrowserSpaceSnapshot.EMPTY
            val array = root.optJSONArray("spaces") ?: JSONArray()
            val spaces = buildList {
                for (index in 0 until minOf(array.length(), BrowserSpaceRules.MAX_SPACES * 2)) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val profileId = item.optString("profileId").trim()
                    if (id.isEmpty() || id.length > 64 || profileId.isEmpty() || profileId.length > 128) continue
                    add(
                        BrowserSpace(
                            id = id,
                            profileId = profileId,
                            name = BrowserSpaceRules.sanitizeName(item.optString("name")),
                            emoji = BrowserSpaceRules.sanitizeEmoji(item.optString("emoji")),
                            accentHue = item.optInt("accentHue", -1).takeIf { hue -> hue in 0..359 },
                            zenSpaceId = item.optString("zenSpaceId").trim().takeIf { it.isNotEmpty() && it.length <= 64 },
                        ),
                    )
                }
            }
            val active = root.optJSONObject("activeSpaceIds") ?: JSONObject()
            val activeSpaceIds = active.keys().asSequence().associateWith { key -> active.optString(key) }
                .filterValues(String::isNotEmpty)
            BrowserSpaceSnapshot(spaces, activeSpaceIds)
        }.getOrDefault(BrowserSpaceSnapshot.EMPTY)
    }
}
