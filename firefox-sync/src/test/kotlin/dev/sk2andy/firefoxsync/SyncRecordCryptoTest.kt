package dev.sk2andy.firefoxsync

import java.security.SecureRandom
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncRecordCryptoTest {
    private val bundle = SyncKeyBundle(ByteArray(32) { (it + 1).toByte() }, ByteArray(32) { (it + 33).toByte() })
    private val crypto = SyncRecordCrypto(FixedRandom(ByteArray(16) { (it + 101).toByte() }))
    private val vector = SyncEncryptedPayload(
        ciphertext = "2jrepS3jHGgRjAZCEAVaHQXXPKJqc4gzLiL8YCN33/btS3QsSnI9Nbqu9Nj033TWSLYG+JzG5dAvv/ef0HKHAug877V2avpYaLvaw0WkKVc=",
        iv = "ZWZnaGlqa2xtbm9wcXJzdA==",
        hmac = "63d72e210116cca42e512fb9159ce15cd9d8a6cbf1acc7cef7c867429c11ba6d",
    )

    @Test
    fun `decrypts a storage format 5 reference payload`() {
        val cleartext = crypto.decrypt(bundle, vector)
        assertEquals("space-1", cleartext.getString("id"))
        assertEquals("space", cleartext.getString("kind"))
        assertEquals("Work", cleartext.getJSONObject("data").getString("name"))
    }

    @Test
    fun `encrypts to the reference ciphertext and hmac with a fixed IV`() {
        val cleartext = JSONObject("""{"id":"space-1","kind":"space","data":{"uuid":"space-1","name":"Work"}}""")
        val encrypted = crypto.encrypt(bundle, cleartext)
        assertEquals(vector.iv, encrypted.iv)
        assertEquals(cleartext.toString(), crypto.decrypt(bundle, encrypted).toString())
        assertEquals(vector, SyncEncryptedPayload.decode(SyncEncryptedPayload.decode(vector.encode()).encode()))
    }

    @Test
    fun `rejects tampered ciphertext hmac and iv`() {
        assertThrows(IllegalArgumentException::class.java) {
            crypto.decrypt(bundle, vector.copy(hmac = vector.hmac.replaceFirst('6', '7')))
        }
        assertThrows(IllegalArgumentException::class.java) {
            crypto.decrypt(bundle, vector.copy(ciphertext = "A" + vector.ciphertext.drop(1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            crypto.decrypt(bundle, vector.copy(iv = "AAAA"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            crypto.decrypt(SyncKeyBundle(ByteArray(32), bundle.hmacKey), vector)
        }
    }

    @Test
    fun `payload decoding requires the exact three fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            SyncEncryptedPayload.decode("""{"ciphertext":"AAAA","IV":"AAAA","hmac":"00","extra":1}""")
        }
        assertThrows(IllegalArgumentException::class.java) { SyncEncryptedPayload.decode("[]") }
        assertThrows(IllegalArgumentException::class.java) { SyncEncryptedPayload.decode("""{"ciphertext":"AAAA","IV":"AAAA"}""") }
    }

    private class FixedRandom(private val bytes: ByteArray) : SecureRandom() {
        override fun nextBytes(target: ByteArray) {
            for (index in target.indices) target[index] = bytes[index % bytes.size]
        }
    }
}
