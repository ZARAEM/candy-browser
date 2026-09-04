package dev.sk2andy.firefoxsync

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Sync 1.5 storage-format-5 record payload: AES-256-CBC ciphertext and IV in standard base64,
 * plus a hex HMAC-SHA256 over the base64 ciphertext text.
 */
data class SyncEncryptedPayload(
    val ciphertext: String,
    val iv: String,
    val hmac: String,
) {
    fun encode(): String = SyncEncoding.canonicalJson(
        JSONObject().put("ciphertext", ciphertext).put("IV", iv).put("hmac", hmac),
    )

    companion object {
        fun decode(raw: String): SyncEncryptedPayload {
            val value = SyncEncoding.parseJsonObject(raw)
            value.requireKeys("ciphertext", "IV", "hmac")
            return SyncEncryptedPayload(
                ciphertext = value.strictString("ciphertext", SyncRecordCrypto.MAX_CIPHERTEXT_CHARS),
                iv = value.strictString("IV", 24),
                hmac = value.strictString("hmac", 64),
            )
        }
    }
}

class SyncRecordCrypto(private val random: SecureRandom = SecureRandom()) {
    fun encrypt(bundle: SyncKeyBundle, cleartext: JSONObject): SyncEncryptedPayload {
        val plaintext = SyncEncoding.utf8(SyncEncoding.canonicalJson(cleartext))
        require(plaintext.size <= MAX_CLEARTEXT_BYTES) { "Record cleartext too large" }
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(bundle.encryptionKey, "AES"), IvParameterSpec(iv))
        val ciphertext = SyncEncoding.base64(cipher.doFinal(plaintext))
        plaintext.fill(0)
        return SyncEncryptedPayload(
            ciphertext = ciphertext,
            iv = SyncEncoding.base64(iv),
            hmac = hmac(bundle, ciphertext),
        )
    }

    fun decrypt(bundle: SyncKeyBundle, payload: SyncEncryptedPayload): JSONObject {
        val expectedHmac = SyncEncoding.decodeHex(payload.hmac, expectedBytes = 32)
        val actualHmac = SyncEncoding.decodeHex(hmac(bundle, payload.ciphertext), expectedBytes = 32)
        require(MessageDigest.isEqual(expectedHmac, actualHmac)) { "Record HMAC mismatch" }
        val iv = SyncEncoding.decodeBase64(payload.iv, expectedBytes = IV_BYTES)
        val ciphertext = SyncEncoding.decodeBase64(payload.ciphertext, maxBytes = MAX_CLEARTEXT_BYTES + IV_BYTES)
        require(ciphertext.isNotEmpty() && ciphertext.size % IV_BYTES == 0) { "Invalid ciphertext length" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(bundle.encryptionKey, "AES"), IvParameterSpec(iv))
        val plaintext = runCatching { cipher.doFinal(ciphertext) }
            .getOrElse { throw IllegalArgumentException("Record decryption failed", it) }
        return try {
            SyncEncoding.parseJsonObject(SyncEncoding.decodeUtf8(plaintext))
        } finally {
            plaintext.fill(0)
        }
    }

    private fun hmac(bundle: SyncKeyBundle, ciphertext: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(bundle.hmacKey, "HmacSHA256"))
        return SyncEncoding.hex(mac.doFinal(SyncEncoding.utf8(ciphertext)))
    }

    companion object {
        const val IV_BYTES = 16
        const val MAX_CLEARTEXT_BYTES = 2 * 1024 * 1024
        const val MAX_CIPHERTEXT_CHARS = ((MAX_CLEARTEXT_BYTES + IV_BYTES) * 4 + 2) / 3 + 4
    }
}
