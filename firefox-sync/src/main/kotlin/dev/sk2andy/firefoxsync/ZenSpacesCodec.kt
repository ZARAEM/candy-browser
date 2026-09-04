package dev.sk2andy.firefoxsync

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cleartext codec for Zen's `spaces` collection. Record cleartext is `{id, kind, data}`;
 * tombstones are `{id, deleted: true}`. Unknown kinds decode to `null` so the caller can leave
 * them untouched and unacknowledged, exactly as Zen does for records from newer clients.
 */
object ZenSpacesCodec {
    const val COLLECTION = "spaces"
    const val ENGINE_VERSION = 3
    const val LAYOUT_RECORD_ID = "layout"
    const val DEFAULT_ESSENTIALS_KEY = "default"
    const val BUILTIN_CONTAINER_PREFIX = "builtin-"
    const val BUILTIN_CONTAINER_COUNT = 4

    const val KIND_CONTAINER = "container"
    const val KIND_SPACE = "space"
    const val KIND_TAB = "tab"
    const val KIND_FOLDER = "folder"
    const val KIND_SPLIT = "split"
    const val KIND_LAYOUT = "layout"

    const val MAX_TEXT_LENGTH = 4_096
    const val MAX_URL_LENGTH = 8_192
    const val MAX_ICON_LENGTH = 262_144
    const val MAX_OPAQUE_JSON_LENGTH = 65_536
    const val MAX_CHILDREN = 4_096
    const val MAX_ESSENTIAL_GROUPS = 256

    fun isBuiltinContainerGuid(guid: String): Boolean =
        guid.startsWith(BUILTIN_CONTAINER_PREFIX) &&
            guid.removePrefix(BUILTIN_CONTAINER_PREFIX).toIntOrNull()?.let { it in 1..BUILTIN_CONTAINER_COUNT } == true

    fun decode(cleartext: JSONObject): ZenSpacesRecord? {
        val id = cleartext.opt("id") as? String ?: return null
        if (!SyncStorageCodec.isValidId(id)) return null
        if (cleartext.optBoolean("deleted", false)) return ZenTombstoneRecord(id)
        val kind = cleartext.opt("kind") as? String ?: return null
        val data = cleartext.optJSONObject("data") ?: return null
        return runCatching {
            when (kind) {
                KIND_CONTAINER -> decodeContainer(id, data)
                KIND_SPACE -> decodeSpace(id, data)
                KIND_TAB -> decodeTab(id, data)
                KIND_FOLDER -> decodeFolder(id, data)
                KIND_SPLIT -> decodeSplit(id, data)
                KIND_LAYOUT -> if (id == LAYOUT_RECORD_ID) decodeLayout(data) else null
                else -> null
            }
        }.getOrNull()
    }

    fun encode(record: ZenSpacesRecord): JSONObject {
        require(SyncStorageCodec.isValidId(record.id)) { "Invalid record id" }
        val (kind, data) = when (record) {
            is ZenTombstoneRecord -> return JSONObject().put("id", record.id).put("deleted", true)
            is ZenContainerRecord -> KIND_CONTAINER to JSONObject()
                .put("guid", record.id)
                .put("name", record.name)
                .put("icon", record.icon)
                .put("color", record.color)
            is ZenSpaceRecord -> KIND_SPACE to JSONObject()
                .put("uuid", record.id)
                .put("name", record.name)
                .put("icon", record.icon ?: JSONObject.NULL)
                .put("theme", opaque(record.themeJson))
                .put("containerGuid", record.containerGuid ?: JSONObject.NULL)
                .put("children", JSONArray(record.children))
            is ZenTabRecord -> KIND_TAB to JSONObject()
                .put("tabId", record.id)
                .put("url", record.url)
                .put("title", record.title)
                .put("icon", record.icon)
                .put("containerGuid", record.containerGuid ?: JSONObject.NULL)
                .put("essential", record.essential)
                .put("workspaceUuid", record.workspaceUuid ?: JSONObject.NULL)
                .put("folderId", record.folderId ?: JSONObject.NULL)
                .put("staticLabel", record.staticLabel ?: JSONObject.NULL)
                .put("hasStaticIcon", record.hasStaticIcon)
                .put("defaultContainer", record.defaultContainer)
            is ZenFolderRecord -> KIND_FOLDER to JSONObject()
                .put("folderId", record.id)
                .put("name", record.name)
                .put("icon", record.icon ?: JSONObject.NULL)
                .put("workspaceUuid", record.workspaceUuid ?: JSONObject.NULL)
                .put("parentFolderId", record.parentFolderId ?: JSONObject.NULL)
                .put("live", opaque(record.liveJson))
                .put("children", JSONArray(record.children))
            is ZenSplitRecord -> KIND_SPLIT to JSONObject()
                .put("splitId", record.id)
                .put("gridType", record.gridType)
                .put("tabs", JSONArray(record.tabs))
                .put("workspaceUuid", record.workspaceUuid ?: JSONObject.NULL)
                .put("folderId", record.folderId ?: JSONObject.NULL)
            is ZenLayoutRecord -> KIND_LAYOUT to JSONObject()
                .put("spaces", JSONArray(record.spaces))
                .put("essentials", JSONObject().also { essentials ->
                    record.essentials.toSortedMap().forEach { (key, tabs) -> essentials.put(key, JSONArray(tabs)) }
                })
        }
        return JSONObject().put("id", record.id).put("kind", kind).put("data", data)
    }

    /** Zen's `recordDigest`: base64 SHA-256 of the canonical `{kind, data}` projection. */
    fun digest(record: ZenSpacesRecord): String? {
        val encoded = encode(record)
        if (!encoded.has("kind")) return null
        val projection = JSONObject().put("kind", encoded.get("kind")).put("data", encoded.get("data"))
        val bytes = SyncEncoding.utf8(SyncEncoding.canonicalJson(projection))
        return SyncEncoding.base64(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    fun assemble(records: Collection<ZenSpacesRecord>): ZenSpacesSnapshot {
        val containers = linkedMapOf<String, ZenContainerRecord>()
        val spaces = linkedMapOf<String, ZenSpaceRecord>()
        val tabs = linkedMapOf<String, ZenTabRecord>()
        val folders = linkedMapOf<String, ZenFolderRecord>()
        val splits = linkedMapOf<String, ZenSplitRecord>()
        var layout: ZenLayoutRecord? = null
        records.forEach { record ->
            when (record) {
                is ZenContainerRecord -> containers[record.id] = record
                is ZenSpaceRecord -> spaces[record.id] = record
                is ZenTabRecord -> tabs[record.id] = record
                is ZenFolderRecord -> folders[record.id] = record
                is ZenSplitRecord -> splits[record.id] = record
                is ZenLayoutRecord -> layout = record
                is ZenTombstoneRecord -> Unit
            }
        }
        return ZenSpacesSnapshot(containers, spaces, tabs, folders, splits, layout)
    }

    private fun decodeContainer(id: String, data: JSONObject): ZenContainerRecord {
        require(data.strictString("guid", SyncStorageCodec.MAX_ID_LENGTH) == id) { "Container guid mismatch" }
        return ZenContainerRecord(
            id = id,
            name = data.strictString("name", MAX_TEXT_LENGTH),
            icon = data.optionalString("icon", MAX_TEXT_LENGTH).orEmpty(),
            color = data.optionalString("color", MAX_TEXT_LENGTH).orEmpty(),
        )
    }

    private fun decodeSpace(id: String, data: JSONObject): ZenSpaceRecord {
        require(data.strictString("uuid", SyncStorageCodec.MAX_ID_LENGTH) == id) { "Space uuid mismatch" }
        return ZenSpaceRecord(
            id = id,
            name = data.optionalString("name", MAX_TEXT_LENGTH).orEmpty(),
            icon = data.optionalString("icon", MAX_ICON_LENGTH),
            themeJson = opaqueJson(data, "theme"),
            containerGuid = data.optionalString("containerGuid", SyncStorageCodec.MAX_ID_LENGTH),
            children = data.stringList("children", MAX_CHILDREN, SyncStorageCodec.MAX_ID_LENGTH),
        )
    }

    private fun decodeTab(id: String, data: JSONObject): ZenTabRecord {
        require(data.strictString("tabId", SyncStorageCodec.MAX_ID_LENGTH) == id) { "Tab id mismatch" }
        val url = data.strictString("url", MAX_URL_LENGTH)
        require(url.isNotEmpty() && url != "about:blank") { "Tab without a URL" }
        return ZenTabRecord(
            id = id,
            url = url,
            title = data.optionalString("title", MAX_TEXT_LENGTH).orEmpty(),
            icon = data.optionalString("icon", MAX_ICON_LENGTH).orEmpty(),
            containerGuid = data.optionalString("containerGuid", SyncStorageCodec.MAX_ID_LENGTH),
            essential = data.optionalBoolean("essential"),
            workspaceUuid = data.optionalString("workspaceUuid", SyncStorageCodec.MAX_ID_LENGTH),
            folderId = data.optionalString("folderId", SyncStorageCodec.MAX_ID_LENGTH),
            staticLabel = data.optionalString("staticLabel", MAX_TEXT_LENGTH),
            hasStaticIcon = data.optionalBoolean("hasStaticIcon"),
            defaultContainer = data.optionalBoolean("defaultContainer"),
        )
    }

    private fun decodeFolder(id: String, data: JSONObject): ZenFolderRecord {
        require(data.strictString("folderId", SyncStorageCodec.MAX_ID_LENGTH) == id) { "Folder id mismatch" }
        return ZenFolderRecord(
            id = id,
            name = data.optionalString("name", MAX_TEXT_LENGTH).orEmpty(),
            icon = data.optionalString("icon", MAX_ICON_LENGTH),
            workspaceUuid = data.optionalString("workspaceUuid", SyncStorageCodec.MAX_ID_LENGTH),
            parentFolderId = data.optionalString("parentFolderId", SyncStorageCodec.MAX_ID_LENGTH),
            liveJson = opaqueJson(data, "live"),
            children = data.stringList("children", MAX_CHILDREN, SyncStorageCodec.MAX_ID_LENGTH),
        )
    }

    private fun decodeSplit(id: String, data: JSONObject): ZenSplitRecord {
        require(data.strictString("splitId", SyncStorageCodec.MAX_ID_LENGTH) == id) { "Split id mismatch" }
        val tabs = data.stringList("tabs", MAX_CHILDREN, SyncStorageCodec.MAX_ID_LENGTH)
        require(tabs.size >= 2) { "Split needs at least two tabs" }
        return ZenSplitRecord(
            id = id,
            gridType = data.optionalString("gridType", MAX_TEXT_LENGTH)?.takeIf(String::isNotEmpty) ?: "grid",
            tabs = tabs,
            workspaceUuid = data.optionalString("workspaceUuid", SyncStorageCodec.MAX_ID_LENGTH),
            folderId = data.optionalString("folderId", SyncStorageCodec.MAX_ID_LENGTH),
        )
    }

    private fun decodeLayout(data: JSONObject): ZenLayoutRecord {
        val essentials = data.optJSONObject("essentials") ?: JSONObject()
        require(essentials.length() <= MAX_ESSENTIAL_GROUPS) { "Too many essential groups" }
        return ZenLayoutRecord(
            spaces = data.stringList("spaces", MAX_CHILDREN, SyncStorageCodec.MAX_ID_LENGTH),
            essentials = essentials.keys().asSequence().associateWith { key ->
                require(key.length <= SyncStorageCodec.MAX_ID_LENGTH) { "Essential key too long" }
                essentials.stringList(key, MAX_CHILDREN, SyncStorageCodec.MAX_ID_LENGTH)
            },
        )
    }

    private fun opaqueJson(data: JSONObject, key: String): String? {
        val value = data.opt(key) ?: return null
        if (value === JSONObject.NULL) return null
        require(value is JSONObject || value is JSONArray) { "Expected JSON value for $key" }
        return SyncEncoding.canonicalJson(value).also { require(it.length <= MAX_OPAQUE_JSON_LENGTH) { "$key too large" } }
    }

    private fun opaque(json: String?): Any {
        if (json == null) return JSONObject.NULL
        require(json.length <= MAX_OPAQUE_JSON_LENGTH) { "Opaque JSON too large" }
        return when (val value = SyncEncoding.parseJsonObjectOrArray(json)) {
            is JSONObject, is JSONArray -> value
            else -> throw IllegalArgumentException("Opaque JSON must be an object or array")
        }
    }
}
