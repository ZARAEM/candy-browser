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
    fun `kid suffix and client state match reference derivation`() {
        assertEquals("_eq5rPNxA2K9JljNyaKejw", SyncKeyRules.kidSuffix(kSync))
        assertEquals("fdeab9acf3710362bd2658cdc9a29e8f", SyncKeyRules.clientState(kSync))
        assertTrue(SyncKeyRules.isValidKid("1690000000000-_eq5rPNxA2K9JljNyaKejw", kSync))
        assertFalse(SyncKeyRules.isValidKid("1690000000000-AAAAAAAAAAAAAAAAAAAAAA", kSync))
        assertFalse(SyncKeyRules.isValidKid("_eq5rPNxA2K9JljNyaKejw", kSync))
        assertEquals(1690000000000L, SyncKeyRules.keyRotationTimestamp("1690000000000-_eq5rPNxA2K9JljNyaKejw"))
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
    fun `sync keys reject a kid that does not belong to the key`() {
        assertThrows(IllegalArgumentException::class.java) { FirefoxSyncKeys(kSync, "1-AAAAAAAAAAAAAAAAAAAAAA") }
        val keys = FirefoxSyncKeys(kSync, "1690000000000-_eq5rPNxA2K9JljNyaKejw")
        keys.destroy()
        assertArrayEquals(ByteArray(64), keys.kSync)
        assertEquals("FirefoxSyncKeys(kid=1690000000000-_eq5rPNxA2K9JljNyaKejw)", keys.toString())
    }
}
