package dev.sk2andy.firefoxsync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStorageCodecTest {
    @Test
    fun `token server response yields https node credentials`() {
        val credentials = SyncStorageCodec.decodeTokenServerResponse(
            """{"id":"hawk-id","key":"hawk-key","uid":12345,"api_endpoint":"https://sync-1.example.org/1.5/12345/","duration":3600,"hashalg":"sha256","hashed_fxa_uid":"abc","node_type":"spanner"}""",
        )
        assertEquals("https://sync-1.example.org/1.5/12345", credentials.apiEndpoint)
        assertEquals(12345L, credentials.uid)
        assertEquals("abc", credentials.hashedFxaUid)
        assertThrows(IllegalArgumentException::class.java) {
            SyncStorageCodec.decodeTokenServerResponse("""{"id":"i","key":"k","uid":1,"api_endpoint":"http://sync.example/1.5/1","duration":10}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncStorageCodec.decodeTokenServerResponse("""{"id":"","key":"k","uid":1,"api_endpoint":"https://sync.example/1.5/1","duration":10}""")
        }
    }

    @Test
    fun `meta global exposes storage version and per engine sync ids`() {
        val meta = SyncStorageCodec.decodeMetaGlobal(
            """{"syncID":"gLoBaL","storageVersion":5,"engines":{"clients":{"version":1,"syncID":"c1"},"spaces":{"version":3,"syncID":"s3"}},"declined":["addons"]}""",
        )
        assertEquals(5, meta.storageVersion)
        assertEquals(SyncEngineInfo(3, "s3"), meta.engines["spaces"])
        assertEquals(listOf("addons"), meta.declined)
        assertEquals(meta, SyncStorageCodec.decodeMetaGlobal(SyncStorageCodec.encodeMetaGlobal(meta)))
        assertThrows(IllegalArgumentException::class.java) {
            SyncStorageCodec.decodeMetaGlobal("""{"syncID":"g","storageVersion":"5","engines":{}}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncStorageCodec.decodeMetaGlobal("""{"syncID":"g","storageVersion":5,"engines":{"bad name":{"version":1,"syncID":"x"}}}""")
        }
    }

    @Test
    fun `crypto keys record requires its identity and 32 byte halves`() {
        val encryption = SyncEncoding.base64(ByteArray(32) { 1 })
        val hmac = SyncEncoding.base64(ByteArray(32) { 2 })
        val keys = SyncStorageCodec.decodeCollectionKeys(
            JSONObject("""{"id":"keys","collection":"crypto","default":["$encryption","$hmac"],"collections":{"spaces":["$hmac","$encryption"]}}"""),
        )
        assertEquals(SyncKeyBundle(ByteArray(32) { 1 }, ByteArray(32) { 2 }), keys.bundleFor("bookmarks"))
        assertEquals(SyncKeyBundle(ByteArray(32) { 2 }, ByteArray(32) { 1 }), keys.bundleFor("spaces"))
        assertEquals(keys.default, SyncStorageCodec.decodeCollectionKeys(SyncStorageCodec.encodeCollectionKeys(keys)).default)
        assertThrows(IllegalArgumentException::class.java) {
            SyncStorageCodec.decodeCollectionKeys(JSONObject("""{"id":"keys","collection":"crypto","default":["$encryption"]}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncStorageCodec.decodeCollectionKeys(JSONObject("""{"id":"other","collection":"crypto","default":["$encryption","$hmac"]}"""))
        }
    }

    @Test
    fun `bso arrays round trip and reject invalid ids`() {
        val records = SyncStorageCodec.decodeBsoArray(
            """[{"id":"space-1","modified":1700000000.12,"payload":"{}","sortindex":3},{"id":"layout","payload":"{}","ttl":null}]""",
        )
        assertEquals(listOf("space-1", "layout"), records.map(SyncBso::id))
        assertEquals(1700000000.12, requireNotNull(records[0].modified), 0.0)
        assertEquals(3, records[0].sortIndex)
        assertNull(records[1].ttlSeconds)
        assertEquals(
            """[{"id":"space-1","payload":"{}","sortindex":3},{"id":"layout","payload":"{}"}]""",
            SyncStorageCodec.encodeBsoArray(records.map { it.copy(modified = null) }),
        )
        assertThrows(IllegalArgumentException::class.java) { SyncStorageCodec.decodeBsoArray("""[{"id":"has space","payload":"{}"}]""") }
        assertThrows(IllegalArgumentException::class.java) { SyncStorageCodec.encodeBsoArray(listOf(SyncBso("", "{}"))) }
        assertFalse(SyncStorageCodec.isValidId("../global"))
        assertFalse(SyncStorageCodec.isValidId(".."))
        assertTrue(SyncStorageCodec.isValidId("builtin-1") && SyncStorageCodec.isValidId("3f1c0d8e-aa11-4c5b-9c1d-2f3e4d5c6b7a"))
    }

    @Test
    fun `post results and info collections parse server timestamps`() {
        val result = SyncStorageCodec.decodePostResult("""{"modified":1700000001.5,"success":["a","b"],"failed":{"c":"invalid payload","d":["x","y"]}}""")
        assertEquals(listOf("a", "b"), result.success)
        assertEquals(mapOf("c" to "invalid payload", "d" to "x, y"), result.failed)
        assertEquals(mapOf("spaces" to 1700000000.0, "crypto" to 1.25), SyncStorageCodec.decodeInfoCollections("""{"spaces":1700000000,"crypto":1.25}"""))
        assertThrows(IllegalArgumentException::class.java) { SyncStorageCodec.decodeInfoCollections("""{"bad name":1}""") }
    }
}
