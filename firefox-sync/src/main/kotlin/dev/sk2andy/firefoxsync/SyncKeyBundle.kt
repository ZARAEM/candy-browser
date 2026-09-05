package dev.sk2andy.firefoxsync

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256 encryption key plus HMAC-SHA256 key for one Sync 1.5 collection or for the
 * `crypto/keys` record itself. Both halves are copied on construction and can be zeroed with
 * [destroy] once the owner is done.
 */
class SyncKeyBundle(encryptionKey: ByteArray, hmacKey: ByteArray) {
    init {
        require(encryptionKey.size == KEY_BYTES && hmacKey.size == KEY_BYTES) { "Key bundle halves must be 32 bytes" }
    }

    val encryptionKey: ByteArray = encryptionKey.copyOf()
    val hmacKey: ByteArray = hmacKey.copyOf()

    fun destroy() {
        encryptionKey.fill(0)
        hmacKey.fill(0)
    }

    fun toBase64Pair(): List<String> = listOf(SyncEncoding.base64(encryptionKey), SyncEncoding.base64(hmacKey))

    override fun equals(other: Any?): Boolean = other is SyncKeyBundle &&
        MessageDigest.isEqual(encryptionKey, other.encryptionKey) &&
        MessageDigest.isEqual(hmacKey, other.hmacKey)

    override fun hashCode(): Int = 31 * encryptionKey.contentHashCode() + hmacKey.contentHashCode()

    override fun toString(): String = "SyncKeyBundle(redacted)"

    companion object {
        const val KEY_BYTES = 32
        const val KSYNC_BYTES = 64

        /**
         * The scoped key for `https://identity.mozilla.com/apps/oldsync` is the 64-byte kSync:
         * the first half encrypts, the second half authenticates. This matches
         * `KeyBundle::from_ksync_bytes` in Mozilla's application-services.
         */
        fun fromKSync(kSync: ByteArray): SyncKeyBundle {
            require(kSync.size == KSYNC_BYTES) { "kSync must be 64 bytes" }
            return SyncKeyBundle(kSync.copyOfRange(0, KEY_BYTES), kSync.copyOfRange(KEY_BYTES, KSYNC_BYTES))
        }

        fun fromBase64(encryptionKey: String, hmacKey: String): SyncKeyBundle = SyncKeyBundle(
            SyncEncoding.decodeBase64(encryptionKey, expectedBytes = KEY_BYTES),
            SyncEncoding.decodeBase64(hmacKey, expectedBytes = KEY_BYTES),
        )
    }
}

/**
 * Identity material for one Firefox Sync storage login. The `kid` is the server's key id and is
 * checked for shape only: Mozilla derives it from SHA-256(kB), and kB never reaches a scoped-key
 * client (see [SyncKeyRules.kidSuffix]).
 */
class FirefoxSyncKeys(kSync: ByteArray, val kid: String) {
    init {
        require(kSync.size == SyncKeyBundle.KSYNC_BYTES) { "kSync must be 64 bytes" }
        require(SyncKeyRules.isValidKid(kid)) { "Malformed kid" }
    }

    val kSync: ByteArray = kSync.copyOf()

    fun destroy() = kSync.fill(0)

    override fun toString(): String = "FirefoxSyncKeys(kid=$kid)"
}

/** Pure derivations around kSync shared by the account and storage layers. */
object SyncKeyRules {
    private const val OLD_SYNC_INFO = "identity.mozilla.com/picl/v1/oldsync"
    private val kidPattern = Regex("[0-9]{1,20}-[A-Za-z0-9_-]{22}")

    /** Legacy derivation kept for tests and BrowserID-era key material: kB (32 bytes) to kSync. */
    fun kSyncFromKB(kB: ByteArray): ByteArray {
        require(kB.size == SyncKeyBundle.KEY_BYTES) { "kB must be 32 bytes" }
        return hkdfSha256(inputKey = kB, salt = ByteArray(0), info = SyncEncoding.utf8(OLD_SYNC_INFO), length = SyncKeyBundle.KSYNC_BYTES)
    }

    /**
     * `kid` suffix as the account server computes it (`fxa-crypto-relier` `_deriveLegacySyncKey`):
     * base64url of the first 16 bytes of SHA-256(kB). kB is the 32-byte account key that only the
     * server and BrowserID-era clients hold, so a scoped-key client cannot recompute the kid from
     * kSync and must trust the value delivered in `keys_jwe`. Kept for tests and the legacy kB path.
     */
    fun kidSuffix(kB: ByteArray): String = SyncEncoding.base64Url(keyHashPrefix(kB))

    /** Legacy `X-Client-State` value: hex of the first 16 bytes of SHA-256(kB). */
    fun clientState(kB: ByteArray): String = SyncEncoding.hex(keyHashPrefix(kB))

    /** Shape check only: `<rotation timestamp>-<22 base64url characters>`. */
    fun isValidKid(kid: String): Boolean = kidPattern.matches(kid)

    fun keyRotationTimestamp(kid: String): Long = kid.substringBefore('-').toLong()

    private fun keyHashPrefix(kB: ByteArray): ByteArray {
        require(kB.size == SyncKeyBundle.KEY_BYTES) { "kB must be 32 bytes" }
        return MessageDigest.getInstance("SHA-256").digest(kB).copyOfRange(0, 16)
    }

    internal fun hkdfSha256(inputKey: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32)) { "Unsupported HKDF length" }
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val pseudoRandomKey = extract.doFinal(inputKey)
        return try {
            val expand = Mac.getInstance("HmacSHA256")
            expand.init(SecretKeySpec(pseudoRandomKey, "HmacSHA256"))
            val output = ByteArray(length)
            var previous = ByteArray(0)
            var produced = 0
            var counter = 1
            while (produced < length) {
                expand.update(previous)
                expand.update(info)
                previous = expand.doFinal(byteArrayOf(counter.toByte()))
                val chunk = minOf(previous.size, length - produced)
                System.arraycopy(previous, 0, output, produced, chunk)
                produced += chunk
                counter++
            }
            previous.fill(0)
            output
        } finally {
            pseudoRandomKey.fill(0)
        }
    }
}
