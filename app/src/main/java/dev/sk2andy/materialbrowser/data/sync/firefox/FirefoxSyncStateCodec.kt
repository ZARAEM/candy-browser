package dev.sk2andy.materialbrowser.data.sync.firefox

import dev.sk2andy.firefoxsync.FirefoxAccountLoginAttempt
import dev.sk2andy.firefoxsync.ZenSpacesCodec
import dev.sk2andy.firefoxsync.ZenSpacesRecord
import dev.sk2andy.materialbrowser.sync.SyncBase64
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncCache
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncSessionSecrets
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncSettings
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncVault
import dev.sk2andy.materialbrowser.sync.parseStrictJsonObject
import org.json.JSONArray
import org.json.JSONObject

internal object FirefoxSyncStateCodec {
    fun encodeSettings(value: FirefoxSyncSettings): String = JSONObject()
        .put("schemaVersion", 1)
        .put("accountUid", value.accountUid)
        .put("accountEmail", value.accountEmail ?: JSONObject.NULL)
        .put("signedInAt", value.signedInAt)
        .toString()

    fun decodeSettings(raw: String): FirefoxSyncSettings {
        val value = parseStrictJsonObject(raw).requireKeys("schemaVersion", "accountUid", "accountEmail", "signedInAt")
        require(value.getInt("schemaVersion") == 1)
        return FirefoxSyncSettings(
            accountUid = value.boundedString("accountUid", 64),
            accountEmail = if (value.isNull("accountEmail")) null else value.boundedString("accountEmail", 320),
            signedInAt = value.boundedString("signedInAt", 64),
        )
    }

    fun encodeVault(value: FirefoxSyncVault): ByteArray = JSONObject()
        .put("schemaVersion", 1)
        .put("session", value.session?.let(::encodeSession) ?: JSONObject.NULL)
        .put("pendingLogin", value.pendingLogin?.let(::encodePendingLogin) ?: JSONObject.NULL)
        .toString()
        .toByteArray(Charsets.UTF_8)

    fun decodeVault(raw: ByteArray): FirefoxSyncVault {
        val value = parseStrictJsonObject(raw.toString(Charsets.UTF_8)).requireKeys("schemaVersion", "session", "pendingLogin")
        require(value.getInt("schemaVersion") == 1)
        return FirefoxSyncVault(
            session = if (value.isNull("session")) null else decodeSession(value.getJSONObject("session")),
            pendingLogin = if (value.isNull("pendingLogin")) null else decodePendingLogin(value.getJSONObject("pendingLogin")),
        )
    }

    fun encodeCache(value: FirefoxSyncCache): ByteArray {
        require(value.records.size <= MAX_RECORDS && value.skippedRecordIds.size <= MAX_RECORDS)
        return JSONObject()
            .put("schemaVersion", 1)
            .put("spacesLastModified", value.spacesLastModified ?: JSONObject.NULL)
            .put("syncedAt", value.syncedAt ?: JSONObject.NULL)
            .put("records", JSONArray(value.records.map(ZenSpacesCodec::encode)))
            .put("skippedRecordIds", JSONArray(value.skippedRecordIds))
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    fun decodeCache(raw: ByteArray): FirefoxSyncCache {
        require(raw.size <= MAX_CACHE_BYTES)
        val value = parseStrictJsonObject(raw.toString(Charsets.UTF_8))
            .requireKeys("schemaVersion", "spacesLastModified", "syncedAt", "records", "skippedRecordIds")
        require(value.getInt("schemaVersion") == 1)
        val records = value.getJSONArray("records")
        val skipped = value.getJSONArray("skippedRecordIds")
        require(records.length() <= MAX_RECORDS && skipped.length() <= MAX_RECORDS)
        val decodedRecords = buildList<ZenSpacesRecord> {
            repeat(records.length()) { index ->
                ZenSpacesCodec.decode(records.getJSONObject(index))?.let(::add)
            }
        }
        require(decodedRecords.map(ZenSpacesRecord::id).distinct().size == decodedRecords.size)
        return FirefoxSyncCache(
            spacesLastModified = if (value.isNull("spacesLastModified")) null else value.getDouble("spacesLastModified").also { require(it >= 0.0) },
            records = decodedRecords,
            skippedRecordIds = buildList { repeat(skipped.length()) { add(skipped.getString(it).also { id -> require(id.length in 1..64) }) } },
            syncedAt = if (value.isNull("syncedAt")) null else value.boundedString("syncedAt", 64),
        )
    }

    private fun encodeSession(value: FirefoxSyncSessionSecrets): JSONObject = JSONObject()
        .put("accessToken", value.accessToken)
        .put("accessTokenExpiresAt", value.accessTokenExpiresAtEpochSeconds.toString())
        .put("refreshToken", value.refreshToken ?: JSONObject.NULL)
        .put("kSync", SyncBase64.encode(value.kSync))
        .put("kid", value.kid)

    private fun decodeSession(value: JSONObject): FirefoxSyncSessionSecrets {
        value.requireKeys("accessToken", "accessTokenExpiresAt", "refreshToken", "kSync", "kid")
        return FirefoxSyncSessionSecrets(
            accessToken = value.boundedString("accessToken", 4_096),
            accessTokenExpiresAtEpochSeconds = value.getString("accessTokenExpiresAt").toLong().also { require(it >= 0) },
            refreshToken = if (value.isNull("refreshToken")) null else value.boundedString("refreshToken", 4_096),
            kSync = SyncBase64.decode(value.getString("kSync"), expectedBytes = 64),
            kid = value.boundedString("kid", 64),
        )
    }

    private fun encodePendingLogin(value: FirefoxAccountLoginAttempt): JSONObject = JSONObject()
        .put("state", value.state)
        .put("codeVerifier", value.codeVerifier)
        .put("keysPrivateKeyPkcs8", SyncBase64.encode(value.keysPrivateKeyPkcs8))
        .put("keysPublicJwk", value.keysPublicJwk)

    private fun decodePendingLogin(value: JSONObject): FirefoxAccountLoginAttempt {
        value.requireKeys("state", "codeVerifier", "keysPrivateKeyPkcs8", "keysPublicJwk")
        return FirefoxAccountLoginAttempt(
            state = value.boundedString("state", 128),
            codeVerifier = value.boundedString("codeVerifier", 128),
            keysPrivateKeyPkcs8 = SyncBase64.decode(value.getString("keysPrivateKeyPkcs8"), maxBytes = 512),
            keysPublicJwk = value.boundedString("keysPublicJwk", 512),
        )
    }

    private fun JSONObject.requireKeys(vararg expected: String): JSONObject = apply {
        require(keys().asSequence().toSet() == expected.toSet())
    }

    private fun JSONObject.boundedString(name: String, maximum: Int): String = getString(name).also {
        require(it.isNotEmpty() && it.length <= maximum)
    }

    private const val MAX_CACHE_BYTES = 8 * 1_024 * 1_024
    private const val MAX_RECORDS = 20_000
}
