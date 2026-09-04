package dev.sk2andy.materialbrowser.data.sync.firefox

import dev.sk2andy.firefoxsync.FirefoxAccountLoginAttempt
import dev.sk2andy.firefoxsync.ZenLayoutRecord
import dev.sk2andy.firefoxsync.ZenSpaceRecord
import dev.sk2andy.firefoxsync.ZenTabRecord
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncCache
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncSessionSecrets
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncSettings
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FirefoxSyncStateCodecTest {
    @Test
    fun `settings round trip and reject unknown keys`() {
        val settings = FirefoxSyncSettings("0123456789abcdef0123456789abcdef", "zen@example.org", "2026-09-04T10:00:00Z")
        assertEquals(settings, FirefoxSyncStateCodec.decodeSettings(FirefoxSyncStateCodec.encodeSettings(settings)))
        assertNull(FirefoxSyncStateCodec.decodeSettings(FirefoxSyncStateCodec.encodeSettings(settings.copy(accountEmail = null))).accountEmail)
        assertThrows(IllegalArgumentException::class.java) {
            FirefoxSyncStateCodec.decodeSettings("""{"schemaVersion":1,"accountUid":"u","accountEmail":null,"signedInAt":"t","extra":1}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FirefoxSyncStateCodec.decodeSettings("""{"schemaVersion":2,"accountUid":"u","accountEmail":null,"signedInAt":"t"}""")
        }
    }

    @Test
    fun `vault round trips sessions and pending logins separately`() {
        val session = FirefoxSyncSessionSecrets("acc", 1_700_000_000L, "ref", ByteArray(64) { it.toByte() }, "1-AAAAAAAAAAAAAAAAAAAAAA")
        val attempt = FirefoxAccountLoginAttempt("st", "verifier", ByteArray(40) { 3 }, """{"kty":"EC"}""")
        val vault = FirefoxSyncVault(session = session, pendingLogin = attempt)
        val decoded = FirefoxSyncStateCodec.decodeVault(FirefoxSyncStateCodec.encodeVault(vault))
        assertEquals(session, decoded.session)
        assertEquals("st", decoded.pendingLogin?.state)
        assertEquals("verifier", decoded.pendingLogin?.codeVerifier)
        assertEquals(40, decoded.pendingLogin?.keysPrivateKeyPkcs8?.size)
        val sessionOnly = FirefoxSyncStateCodec.decodeVault(FirefoxSyncStateCodec.encodeVault(FirefoxSyncVault(session = session.copy(refreshToken = null))))
        assertNull(sessionOnly.pendingLogin)
        assertNull(sessionOnly.session?.refreshToken)
        assertThrows(IllegalArgumentException::class.java) {
            FirefoxSyncStateCodec.decodeVault("""{"schemaVersion":1,"session":{"accessToken":"a"},"pendingLogin":null}""".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            FirefoxSyncStateCodec.decodeVault("""{"schemaVersion":1,"session":null,"pendingLogin":null,"leak":"x"}""".toByteArray())
        }
    }

    @Test
    fun `cache round trips Zen records and drops undecodable ones`() {
        val cache = FirefoxSyncCache(
            spacesLastModified = 1_700_000_000.25,
            records = listOf(
                ZenSpaceRecord("ws-1", "Home", "🏠", """{"type":"gradient"}""", "builtin-1", listOf("tab-1")),
                ZenTabRecord("tab-1", "https://example.org/", "Example", "", "builtin-1", false, "ws-1", null, null, false, false),
                ZenLayoutRecord(listOf("ws-1"), mapOf("default" to emptyList())),
            ),
            skippedRecordIds = listOf("weird"),
            syncedAt = "2026-09-04T10:00:00Z",
        )
        assertEquals(cache, FirefoxSyncStateCodec.decodeCache(FirefoxSyncStateCodec.encodeCache(cache)))
        val empty = FirefoxSyncStateCodec.decodeCache(FirefoxSyncStateCodec.encodeCache(FirefoxSyncCache.EMPTY))
        assertEquals(FirefoxSyncCache.EMPTY, empty)
        val withUnknown = """{"schemaVersion":1,"spacesLastModified":null,"syncedAt":null,"records":[{"id":"x","kind":"widget","data":{}}],"skippedRecordIds":[]}"""
        assertEquals(emptyList<Any>(), FirefoxSyncStateCodec.decodeCache(withUnknown.toByteArray()).records)
        assertThrows(IllegalArgumentException::class.java) {
            FirefoxSyncStateCodec.decodeCache("""{"schemaVersion":1,"records":[]}""".toByteArray())
        }
    }
}
