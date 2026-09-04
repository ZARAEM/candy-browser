package dev.sk2andy.firefoxsync

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Builds `Authorization: Hawk` headers for Sync 1.5 storage requests using the credentials issued
 * by the token server. The deterministic core is exposed for tests; the public entry point picks
 * the timestamp and nonce.
 */
class HawkAuthenticator(
    private val id: String,
    key: String,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val key = SyncEncoding.utf8(key)

    /** Seconds to add to the local clock so requests match the server's notion of time. */
    @Volatile
    var timestampOffsetSeconds: Long = 0
        private set

    init {
        require(id.isNotEmpty() && id.length <= 4096 && id.none { it == '"' || it.isISOControl() }) { "Invalid Hawk id" }
        require(key.isNotEmpty()) { "Invalid Hawk key" }
    }

    fun header(method: String, url: URI, body: String?, contentType: String = JSON_CONTENT_TYPE): String {
        val nonce = ByteArray(8).also(random::nextBytes)
        return header(
            method = method,
            url = url,
            timestamp = clock() + timestampOffsetSeconds,
            nonce = SyncEncoding.base64Url(nonce),
            payloadHash = body?.let { payloadHash(contentType, it) },
        )
    }

    fun noteServerTimestamp(serverSeconds: Long) {
        timestampOffsetSeconds = serverSeconds - clock()
    }

    internal fun header(
        method: String,
        url: URI,
        timestamp: Long,
        nonce: String,
        payloadHash: String?,
    ): String {
        require(url.scheme == "https" || url.scheme == "http") { "Unsupported scheme" }
        val host = requireNotNull(url.host) { "Missing host" }.lowercase()
        val port = if (url.port == -1) (if (url.scheme == "https") 443 else 80) else url.port
        val resource = buildString {
            append(url.rawPath.ifEmpty { "/" })
            url.rawQuery?.let { append('?').append(it) }
        }
        val normalized = buildString {
            append("hawk.1.header\n")
            append(timestamp).append('\n')
            append(nonce).append('\n')
            append(method.uppercase()).append('\n')
            append(resource).append('\n')
            append(host).append('\n')
            append(port).append('\n')
            append(payloadHash.orEmpty()).append('\n')
            append('\n')
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val signature = SyncEncoding.base64(mac.doFinal(SyncEncoding.utf8(normalized)))
        return buildString {
            append("Hawk id=\"").append(id).append('"')
            append(", ts=\"").append(timestamp).append('"')
            append(", nonce=\"").append(nonce).append('"')
            if (payloadHash != null) append(", hash=\"").append(payloadHash).append('"')
            append(", mac=\"").append(signature).append('"')
        }
    }

    companion object {
        const val JSON_CONTENT_TYPE = "application/json"

        fun payloadHash(contentType: String, body: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(SyncEncoding.utf8("hawk.1.payload\n"))
            digest.update(SyncEncoding.utf8(contentType.substringBefore(';').trim().lowercase()))
            digest.update('\n'.code.toByte())
            digest.update(SyncEncoding.utf8(body))
            digest.update('\n'.code.toByte())
            return SyncEncoding.base64(digest.digest())
        }
    }
}
