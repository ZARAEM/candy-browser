package dev.sk2andy.firefoxsync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncKeyBundleTest {
    private val kSync = ByteArray(64) { it.toByte() }

    @Test
    fun `kSync splits into encryption and hmac halves`() {
        val bundle = SyncKeyBundle.fromKSync(kSync)
        assertArrayEquals(ByteArray(32) { it.toByte() }, bundle.encryptionKey)
        assertArrayEquals(ByteArray(32) { (it + 32).toByte() }, bundle.hmacKey)
        assertEquals(bundle, SyncKeyBundle.fromBase64(bundle.toBase64Pair()[0], bundle.toBase64Pair()[1]))
        assertThrows(IllegalArgumentException::class.java) { SyncKeyBundle.fromKSync(ByteArray(32)) }
    }

    @Test
    fun `kid suffix and client state match the server derivation from kB`() {
        val kB = ByteArray(32) { 7 }
        assertEquals("S7Bvjk46dxXSAdVz0KpCNw", SyncKeyRules.kidSuffix(kB))
        assertEquals("4bb06f8e4e3a7715d201d573d0aa4237", SyncKeyRules.clientState(kB))
        assertThrows(IllegalArgumentException::class.java) { SyncKeyRules.kidSuffix(kSync) }
        assertEquals(1690000000000L, SyncKeyRules.keyRotationTimestamp("1690000000000-_eq5rPNxA2K9JljNyaKejw"))
    }

    @Test
    fun `kid validation checks the shape only`() {
        assertTrue(SyncKeyRules.isValidKid("1690000000000-_eq5rPNxA2K9JljNyaKejw"))
        assertTrue(SyncKeyRules.isValidKid("1690000000000-AAAAAAAAAAAAAAAAAAAAAA"))
        assertFalse(SyncKeyRules.isValidKid("_eq5rPNxA2K9JljNyaKejw"))
        assertFalse(SyncKeyRules.isValidKid("1690000000000-tooshort"))
        assertFalse(SyncKeyRules.isValidKid("1690000000000-_eq5rPNxA2K9JljNyaKej+"))
        assertFalse(SyncKeyRules.isValidKid("abc-_eq5rPNxA2K9JljNyaKejw"))
    }

    @Test
    fun `legacy kB derives kSync through HKDF oldsync info`() {
        val kB = ByteArray(32) { 7 }
        assertEquals(
            "Px_jPZ1p2wwiSUXffOvphCpS_hjuw9iZx3vjTTJr-2QgJRJGdUk3Wbm6DOyGseZQMXN7iFLiLoOjSoZj6iblvg",
            SyncEncoding.base64Url(SyncKeyRules.kSyncFromKB(kB)),
        )
    }

    @Test
    fun `sync keys reject a malformed kid but trust any well-formed server kid`() {
        assertThrows(IllegalArgumentException::class.java) { FirefoxSyncKeys(kSync, "not-a-kid") }
        assertThrows(IllegalArgumentException::class.java) { FirefoxSyncKeys(ByteArray(32), "1-AAAAAAAAAAAAAAAAAAAAAA") }
        // The suffix is a hash of kB, which the client never sees, so it is not checked against kSync.
        FirefoxSyncKeys(kSync, "1-AAAAAAAAAAAAAAAAAAAAAA").destroy()
        val keys = FirefoxSyncKeys(kSync, "1690000000000-_eq5rPNxA2K9JljNyaKejw")
        keys.destroy()
        assertArrayEquals(ByteArray(64), keys.kSync)
        assertEquals("FirefoxSyncKeys(kid=1690000000000-_eq5rPNxA2K9JljNyaKejw)", keys.toString())
    }
}
