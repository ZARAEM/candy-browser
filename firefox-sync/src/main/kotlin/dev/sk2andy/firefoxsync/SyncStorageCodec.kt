package dev.sk2andy.firefoxsync

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** Strict wire codec for token-server and Sync 1.5 storage JSON documents. */
object SyncStorageCodec {
    const val MAX_ID_LENGTH = 64
    const val MAX_COLLECTION_NAME_LENGTH = 32
    const val MAX_RECORDS_PER_PAGE = 5_000
    const val MAX_ENGINES = 64

    private val idPattern = Regex("[A-Za-z0-9._-]{1,64}")
    private val collectionPattern = Regex("[a-zA-Z0-9._-]{1,32}")

    fun isValidId(value: String): Boolean = idPattern.matches(value) && value != "." && value != ".."

    fun isValidCollectionName(value: String): Boolean = collectionPattern.matches(value)

    fun decodeTokenServerResponse(raw: String): SyncStorageCredentials {
        val value = SyncEncoding.parseJsonObject(raw)
        val endpoint = value.strictString("api_endpoint", 2_048)
        val uri = runCatching { URI(endpoint) }.getOrElse { throw IllegalArgumentException("Invalid api_endpoint", it) }
        require(uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null) {
            "api_endpoint must be an https URL without query or fragment"
        }
        return SyncStorageCredentials(
            hawkId = value.strictString("id", 4_096).also { require(it.isNotEmpty()) { "Empty Hawk id" } },
            hawkKey = value.strictString("key", 4_096).also { require(it.isNotEmpty()) { "Empty Hawk key" } },
            uid = value.strictLong("uid").also { require(it >= 0) { "Negative uid" } },
            apiEndpoint = endpoint.trimEnd('/'),
            durationSeconds = value.strictLong("duration").also { require(it > 0) { "Invalid duration" } },
            hashedFxaUid = value.optionalString("hashed_fxa_uid", 128),
        )
    }

    fun decodeInfoCollections(raw: String): Map<String, Double> {
        val value = SyncEncoding.parseJsonObject(raw)
        require(value.length() <= 256) { "Too many collections" }
        return value.keys().asSequence().associateWith { name ->
            require(isValidCollectionName(name)) { "Invalid collection name" }
            value.strictDecimal(name)
        }
    }

    fun decodeBsoArray(raw: String): List<SyncBso> {
        val array = SyncEncoding.parseJsonArray(raw)
        require(array.length() <= MAX_RECORDS_PER_PAGE) { "Too many records" }
        return List(array.length()) { index ->
            decodeBso(array.opt(index) as? JSONObject ?: throw IllegalArgumentException("Expected record object"))
        }
    }

    fun decodeBso(raw: String): SyncBso = decodeBso(SyncEncoding.parseJsonObject(raw))

    fun encodeBsoArray(records: List<SyncBso>): String {
        val array = JSONArray()
        records.forEach { record ->
            require(isValidId(record.id)) { "Invalid record id" }
            val value = JSONObject().put("id", record.id).put("payload", record.payload)
            record.sortIndex?.let { value.put("sortindex", it) }
            record.ttlSeconds?.let { value.put("ttl", it) }
            array.put(value)
        }
        return SyncEncoding.canonicalJson(array)
    }

    fun decodePostResult(raw: String): SyncPostResult {
        val value = SyncEncoding.parseJsonObject(raw)
        val failed = value.optJSONObject("failed") ?: JSONObject()
        return SyncPostResult(
            modified = value.strictDecimal("modified"),
            success = value.stringList("success", MAX_RECORDS_PER_PAGE, MAX_ID_LENGTH),
            failed = failed.keys().asSequence().associateWith { id ->
                when (val reason = failed.opt(id)) {
                    is String -> reason.take(512)
                    is JSONArray -> reason.stringList(16, 512).joinToString()
                    else -> "unknown"
                }
            },
        )
    }

    fun decodeMetaGlobal(cleartext: String): SyncMetaGlobal {
        val value = SyncEncoding.parseJsonObject(cleartext)
        val engines = value.optJSONObject("engines") ?: JSONObject()
        require(engines.length() <= MAX_ENGINES) { "Too many engines" }
        return SyncMetaGlobal(
            syncId = value.strictString("syncID", MAX_ID_LENGTH),
            storageVersion = value.strictInt("storageVersion"),
            engines = engines.keys().asSequence().associateWith { name ->
                require(isValidCollectionName(name)) { "Invalid engine name" }
                val engine = engines.optJSONObject(name) ?: throw IllegalArgumentException("Expected engine object")
                SyncEngineInfo(
                    version = engine.strictInt("version"),
                    syncId = engine.strictString("syncID", MAX_ID_LENGTH),
                )
            },
            declined = value.stringList("declined", MAX_ENGINES, MAX_COLLECTION_NAME_LENGTH),
        )
    }

    fun encodeMetaGlobal(meta: SyncMetaGlobal): String {
        val engines = JSONObject()
        meta.engines.toSortedMap().forEach { (name, engine) ->
            require(isValidCollectionName(name)) { "Invalid engine name" }
            engines.put(name, JSONObject().put("version", engine.version).put("syncID", engine.syncId))
        }
        return SyncEncoding.canonicalJson(
            JSONObject()
                .put("syncID", meta.syncId)
                .put("storageVersion", meta.storageVersion)
                .put("engines", engines)
                .put("declined", JSONArray(meta.declined)),
        )
    }

    fun decodeCollectionKeys(cleartext: JSONObject): SyncCollectionKeys {
        require(cleartext.optString("id") == "keys" && cleartext.optString("collection") == "crypto") {
            "Unexpected crypto/keys identity"
        }
        val collections = cleartext.optJSONObject("collections") ?: JSONObject()
        require(collections.length() <= MAX_ENGINES) { "Too many collection keys" }
        return SyncCollectionKeys(
            default = decodeKeyPair(cleartext.optJSONArray("default")),
            collections = collections.keys().asSequence().associateWith { name ->
                require(isValidCollectionName(name)) { "Invalid collection name" }
                decodeKeyPair(collections.optJSONArray(name))
            },
        )
    }

    fun encodeCollectionKeys(keys: SyncCollectionKeys): JSONObject {
        val collections = JSONObject()
        keys.collections.toSortedMap().forEach { (name, bundle) -> collections.put(name, JSONArray(bundle.toBase64Pair())) }
        return JSONObject()
            .put("id", "keys")
            .put("collection", "crypto")
            .put("default", JSONArray(keys.default.toBase64Pair()))
            .put("collections", collections)
    }

    private fun decodeBso(value: JSONObject): SyncBso {
        val id = value.strictString("id", MAX_ID_LENGTH)
        require(isValidId(id)) { "Invalid record id" }
        val modified = if (value.has("modified") && !value.isNull("modified")) value.strictDecimal("modified") else null
        return SyncBso(
            id = id,
            payload = value.strictString("payload", SyncRecordCrypto.MAX_CIPHERTEXT_CHARS + 256),
            modified = modified,
            sortIndex = if (value.has("sortindex") && !value.isNull("sortindex")) value.strictInt("sortindex") else null,
            ttlSeconds = if (value.has("ttl") && !value.isNull("ttl")) value.strictInt("ttl") else null,
        )
    }

    private fun decodeKeyPair(pair: JSONArray?): SyncKeyBundle {
        require(pair != null && pair.length() == 2) { "Expected [encryptionKey, hmacKey]" }
        val keys = pair.stringList(2, 64)
        return SyncKeyBundle.fromBase64(keys[0], keys[1])
    }
}
