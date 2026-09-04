package dev.sk2andy.firefoxsync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FirefoxSyncSessionTest {
    private val kSync = ByteArray(64) { (it * 3).toByte() }
    private val keys = FirefoxSyncKeys(kSync, "1700000000000-" + SyncKeyRules.kidSuffix(kSync))
    private val credentials = SyncStorageCredentials("hawk-id", "hawk-key", 7, "https://node.example/1.5/7", 3600, null)
    private val spacesBundle = SyncKeyBundle(ByteArray(32) { 5 }, ByteArray(32) { 6 })
    private val recordCrypto = SyncRecordCrypto()

    @Test
    fun `connect decrypts collection keys and validates storage version`() {
        val transport = FakeTransport(credentials, metaGlobal(spacesVersion = 3), cryptoKeys(), emptyList())
        val outcome = FirefoxSyncSession(transport, recordCrypto, clock = { 1_000L }).connect("https://token.example/1.0/sync/1.5", "acc", keys)
        val connection = (outcome as FirefoxSyncConnectOutcome.Connected).connection
        assertEquals(4_600L, connection.expiresAtEpochSeconds)
        assertEquals(SyncEngineInfo(3, "spaces-sync-id"), connection.zenSpacesEngine())
        assertEquals(spacesBundle, connection.collectionKeys.bundleFor("spaces"))
        assertEquals(listOf("token:acc:${keys.kid}", "get:meta/global", "get:crypto/keys"), transport.log)

        val wrongVersion = FirefoxSyncSession(FakeTransport(credentials, metaGlobal(spacesVersion = 3, storageVersion = 6), cryptoKeys(), emptyList()), recordCrypto)
            .connect("https://token.example/1.0/sync/1.5", "acc", keys)
        assertEquals(FirefoxSyncConnectOutcome.UnsupportedStorageVersion(6), wrongVersion)

        val uninitialized = FirefoxSyncSession(FakeTransport(credentials, null, null, emptyList()), recordCrypto)
            .connect("https://token.example/1.0/sync/1.5", "acc", keys)
        assertEquals(FirefoxSyncConnectOutcome.StorageNotInitialized, uninitialized)
    }

    @Test
    fun `fetch pages through the spaces collection and skips undecodable records`() {
        val records = listOf(
            encrypted("ws-1", """{"id":"ws-1","kind":"space","data":{"uuid":"ws-1","name":"Home","children":["tab-1"]}}"""),
            encrypted("tab-1", """{"id":"tab-1","kind":"tab","data":{"tabId":"tab-1","url":"https://a.example/","essential":false,"workspaceUuid":"ws-1"}}"""),
            encrypted("weird", """{"id":"weird","kind":"widget","data":{}}"""),
            SyncBso("garbage", """{"ciphertext":"AAAA","IV":"AAAAAAAAAAAAAAAAAAAAAA==","hmac":"00"}"""),
            encrypted("layout", """{"id":"layout","kind":"layout","data":{"spaces":["ws-1"],"essentials":{}}}"""),
        )
        val transport = FakeTransport(credentials, metaGlobal(spacesVersion = 3), cryptoKeys(), records, pageSize = 2)
        val session = FirefoxSyncSession(transport, recordCrypto)
        val connection = (session.connect("https://token.example/1.0/sync/1.5", "acc", keys) as FirefoxSyncConnectOutcome.Connected).connection

        val outcome = session.fetchZenSpaces(connection, newerThan = 12.5) as ZenSpacesFetchOutcome.Ready
        assertEquals(listOf("ws-1"), outcome.snapshot.orderedSpaces().map(ZenSpaceRecord::id))
        assertEquals(listOf("tab-1"), outcome.snapshot.pinnedTabs("ws-1").map(ZenTabRecord::id))
        assertEquals(listOf("weird", "garbage"), outcome.skippedRecordIds)
        assertEquals(1_700.0, requireNotNull(outcome.lastModified), 0.0)
        assertEquals(
            listOf("get:spaces?newer=12.5&offset=null", "get:spaces?newer=12.5&offset=page-2", "get:spaces?newer=12.5&offset=page-4"),
            transport.log.filter { it.startsWith("get:spaces") },
        )
    }

    @Test
    fun `fetch reports missing or newer Zen engines instead of reading`() {
        val missing = FirefoxSyncSession(FakeTransport(credentials, metaGlobal(spacesVersion = null), cryptoKeys(), emptyList()), recordCrypto)
        val missingConnection = (missing.connect("https://token.example/1.0/sync/1.5", "acc", keys) as FirefoxSyncConnectOutcome.Connected).connection
        assertEquals(ZenSpacesFetchOutcome.EngineMissing, missing.fetchZenSpaces(missingConnection))

        val newer = FirefoxSyncSession(FakeTransport(credentials, metaGlobal(spacesVersion = 4), cryptoKeys(), emptyList()), recordCrypto)
        val newerConnection = (newer.connect("https://token.example/1.0/sync/1.5", "acc", keys) as FirefoxSyncConnectOutcome.Connected).connection
        assertEquals(ZenSpacesFetchOutcome.UnsupportedEngineVersion(4), newer.fetchZenSpaces(newerConnection))
        assertThrows(IllegalArgumentException::class.java) {
            newer.uploadZenSpaces(newerConnection, listOf(ZenTombstoneRecord("x")), ifUnmodifiedSince = null)
        }
    }

    @Test
    fun `upload encrypts with the spaces bundle and batches with precondition timestamps`() {
        val transport = FakeTransport(credentials, metaGlobal(spacesVersion = 3), cryptoKeys(), emptyList())
        val session = FirefoxSyncSession(transport, recordCrypto)
        val connection = (session.connect("https://token.example/1.0/sync/1.5", "acc", keys) as FirefoxSyncConnectOutcome.Connected).connection
        val records = (1..150).map { index ->
            ZenTabRecord("tab-$index", "https://t.example/$index", "", "", null, false, "ws-1", null, null, false, false)
        } + ZenTombstoneRecord("tab-old")

        val result = session.uploadZenSpaces(connection, records, ifUnmodifiedSince = 1_650.0)
        assertEquals(151, result.success.size)
        assertTrue(result.failed.isEmpty())
        assertEquals(listOf("post:spaces:100:1650.0", "post:spaces:51:1651.0"), transport.log.filter { it.startsWith("post") })
        val uploaded = transport.posted.single { it.id == "tab-150" }
        val cleartext = recordCrypto.decrypt(spacesBundle, SyncEncryptedPayload.decode(uploaded.payload))
        assertEquals("https://t.example/150", cleartext.getJSONObject("data").getString("url"))
        assertTrue(recordCrypto.decrypt(spacesBundle, SyncEncryptedPayload.decode(transport.posted.last().payload)).getBoolean("deleted"))
    }

    private fun metaGlobal(spacesVersion: Int?, storageVersion: Int = 5): SyncBso {
        val engines = JSONObject().put("clients", JSONObject().put("version", 1).put("syncID", "c"))
        spacesVersion?.let { engines.put("spaces", JSONObject().put("version", it).put("syncID", "spaces-sync-id")) }
        return SyncBso("global", JSONObject().put("syncID", "g").put("storageVersion", storageVersion).put("engines", engines).toString())
    }

    private fun cryptoKeys(): SyncBso {
        val defaultBundle = SyncKeyBundle(ByteArray(32) { 1 }, ByteArray(32) { 2 })
        val cleartext = SyncStorageCodec.encodeCollectionKeys(SyncCollectionKeys(defaultBundle, mapOf("spaces" to spacesBundle)))
        val syncBundle = SyncKeyBundle.fromKSync(kSync)
        return SyncBso("keys", recordCrypto.encrypt(syncBundle, cleartext).encode())
    }

    private fun encrypted(id: String, cleartext: String): SyncBso =
        SyncBso(id, recordCrypto.encrypt(spacesBundle, JSONObject(cleartext)).encode(), modified = 1_700.0)

    private class FakeTransport(
        private val credentials: SyncStorageCredentials,
        private val meta: SyncBso?,
        private val keys: SyncBso?,
        private val spaces: List<SyncBso>,
        private val pageSize: Int = 500,
    ) : FirefoxSyncTransport {
        val log = mutableListOf<String>()
        val posted = mutableListOf<SyncBso>()
        private var modified = 0.0

        override fun requestTokens(config: FirefoxAccountConfig, body: String): FirefoxAccountTokens = error("unused")

        override fun destroyToken(config: FirefoxAccountConfig, body: String) = error("unused")

        override fun fetchProfile(config: FirefoxAccountConfig, accessToken: String): FirefoxAccountProfile = error("unused")

        override fun fetchStorageCredentials(tokenServerUrl: String, accessToken: String, kid: String): SyncStorageCredentials {
            log += "token:$accessToken:$kid"
            return credentials
        }

        override fun infoCollections(credentials: SyncStorageCredentials): Map<String, Double> = emptyMap()

        override fun getRecord(credentials: SyncStorageCredentials, collection: String, id: String): SyncBso? {
            log += "get:$collection/$id"
            return when ("$collection/$id") {
                "meta/global" -> meta
                "crypto/keys" -> keys
                else -> null
            }
        }

        override fun getCollection(
            credentials: SyncStorageCredentials,
            collection: String,
            newerThan: Double?,
            limit: Int,
            offset: String?,
        ): SyncCollectionPage {
            log += "get:$collection?newer=$newerThan&offset=$offset"
            val start = offset?.removePrefix("page-")?.toInt() ?: 0
            val end = minOf(start + pageSize, spaces.size)
            return SyncCollectionPage(
                records = spaces.subList(start, end),
                lastModified = 1_700.0,
                nextOffset = if (end < spaces.size) "page-$end" else null,
            )
        }

        override fun postRecords(
            credentials: SyncStorageCredentials,
            collection: String,
            records: List<SyncBso>,
            ifUnmodifiedSince: Double?,
        ): SyncPostResult {
            log += "post:$collection:${records.size}:$ifUnmodifiedSince"
            posted += records
            modified = (ifUnmodifiedSince ?: modified) + 1.0
            return SyncPostResult(modified, records.map(SyncBso::id), emptyMap())
        }
    }
}
