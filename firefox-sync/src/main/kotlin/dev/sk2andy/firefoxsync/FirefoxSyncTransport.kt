package dev.sk2andy.firefoxsync

import java.net.URI
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class FirefoxSyncTransportException(
    val statusCode: Int?,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val isUnauthorized: Boolean get() = statusCode == 401
    val isPreconditionFailed: Boolean get() = statusCode == 412
}

/** Network seam for the Mozilla account OAuth server, the token server and Sync 1.5 storage. */
interface FirefoxSyncTransport {
    fun requestTokens(config: FirefoxAccountConfig, body: String): FirefoxAccountTokens
    fun destroyToken(config: FirefoxAccountConfig, body: String)
    fun fetchProfile(config: FirefoxAccountConfig, accessToken: String): FirefoxAccountProfile
    fun fetchStorageCredentials(tokenServerUrl: String, accessToken: String, kid: String): SyncStorageCredentials
    fun infoCollections(credentials: SyncStorageCredentials): Map<String, Double>
    fun getRecord(credentials: SyncStorageCredentials, collection: String, id: String): SyncBso?
    fun getCollection(
        credentials: SyncStorageCredentials,
        collection: String,
        newerThan: Double? = null,
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: String? = null,
    ): SyncCollectionPage
    fun postRecords(
        credentials: SyncStorageCredentials,
        collection: String,
        records: List<SyncBso>,
        ifUnmodifiedSince: Double? = null,
    ): SyncPostResult

    companion object {
        const val DEFAULT_PAGE_SIZE = 500
        const val MAX_POST_RECORDS = 100
    }
}

class OkHttpFirefoxSyncTransport(
    client: OkHttpClient = OkHttpClient(),
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : FirefoxSyncTransport {
    private val client = client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val authenticators = ConcurrentHashMap<String, HawkAuthenticator>()

    override fun requestTokens(config: FirefoxAccountConfig, body: String): FirefoxAccountTokens {
        val response = execute(jsonRequest("${config.oauthUrl}/token", "POST", body))
        return FirefoxAccountOAuth.decodeTokenResponse(response.body)
    }

    override fun destroyToken(config: FirefoxAccountConfig, body: String) {
        execute(jsonRequest("${config.oauthUrl}/destroy", "POST", body))
    }

    override fun fetchProfile(config: FirefoxAccountConfig, accessToken: String): FirefoxAccountProfile {
        requireToken(accessToken)
        val request = Request.Builder()
            .url(requireHttps("${config.profileUrl}/profile"))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", HawkAuthenticator.JSON_CONTENT_TYPE)
            .get()
            .build()
        return FirefoxAccountOAuth.decodeProfileResponse(execute(request).body)
    }

    override fun fetchStorageCredentials(tokenServerUrl: String, accessToken: String, kid: String): SyncStorageCredentials {
        requireToken(accessToken)
        require(kid.matches(Regex("[0-9]{1,20}-[A-Za-z0-9_-]{22}"))) { "Invalid kid" }
        val request = Request.Builder()
            .url(requireHttps(tokenServerUrl))
            .header("Authorization", "Bearer $accessToken")
            .header("X-KeyID", kid)
            .header("Accept", HawkAuthenticator.JSON_CONTENT_TYPE)
            .get()
            .build()
        return SyncStorageCodec.decodeTokenServerResponse(execute(request).body)
    }

    override fun infoCollections(credentials: SyncStorageCredentials): Map<String, Double> =
        SyncStorageCodec.decodeInfoCollections(storage(credentials, "GET", "/info/collections").body)

    override fun getRecord(credentials: SyncStorageCredentials, collection: String, id: String): SyncBso? {
        require(SyncStorageCodec.isValidCollectionName(collection) && SyncStorageCodec.isValidId(id)) { "Invalid record address" }
        val response = storage(credentials, "GET", "/storage/$collection/$id", allowNotFound = true)
        if (response.statusCode == 404) return null
        return SyncStorageCodec.decodeBso(response.body)
    }

    override fun getCollection(
        credentials: SyncStorageCredentials,
        collection: String,
        newerThan: Double?,
        limit: Int,
        offset: String?,
    ): SyncCollectionPage {
        require(SyncStorageCodec.isValidCollectionName(collection)) { "Invalid collection" }
        require(limit in 1..SyncStorageCodec.MAX_RECORDS_PER_PAGE) { "Invalid page size" }
        val query = buildList {
            add("full=1")
            add("limit=$limit")
            newerThan?.let { add("newer=${formatTimestamp(it)}") }
            offset?.let { token ->
                require(token.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) { "Invalid offset token" }
                add("offset=$token")
            }
        }.joinToString("&")
        val response = storage(credentials, "GET", "/storage/$collection?$query")
        return SyncCollectionPage(
            records = SyncStorageCodec.decodeBsoArray(response.body),
            lastModified = response.lastModified,
            nextOffset = response.nextOffset,
        )
    }

    override fun postRecords(
        credentials: SyncStorageCredentials,
        collection: String,
        records: List<SyncBso>,
        ifUnmodifiedSince: Double?,
    ): SyncPostResult {
        require(SyncStorageCodec.isValidCollectionName(collection)) { "Invalid collection" }
        require(records.size in 1..FirefoxSyncTransport.MAX_POST_RECORDS) { "Invalid batch size" }
        val response = storage(
            credentials = credentials,
            method = "POST",
            resource = "/storage/$collection",
            body = SyncStorageCodec.encodeBsoArray(records),
            ifUnmodifiedSince = ifUnmodifiedSince,
        )
        return SyncStorageCodec.decodePostResult(response.body)
    }

    private fun storage(
        credentials: SyncStorageCredentials,
        method: String,
        resource: String,
        body: String? = null,
        ifUnmodifiedSince: Double? = null,
        allowNotFound: Boolean = false,
    ): StorageResponse {
        val url = URI(requireHttps(credentials.apiEndpoint + resource))
        val authenticator = authenticators.computeIfAbsent(credentials.hawkId) {
            HawkAuthenticator(credentials.hawkId, credentials.hawkKey, random, clock)
        }
        val builder = Request.Builder()
            .url(url.toString())
            .header("Authorization", authenticator.header(method, url, body))
            .header("Accept", HawkAuthenticator.JSON_CONTENT_TYPE)
        ifUnmodifiedSince?.let { builder.header("X-If-Unmodified-Since", formatTimestamp(it)) }
        if (body != null) {
            builder.method(method, body.toRequestBody(JSON_MEDIA_TYPE))
        } else {
            builder.method(method, null)
        }
        val response = execute(builder.build(), allowNotFound = allowNotFound)
        response.serverTimestamp?.let(authenticator::noteServerTimestamp)
        return response
    }

    private fun jsonRequest(url: String, method: String, body: String): Request = Request.Builder()
        .url(requireHttps(url))
        .header("Accept", HawkAuthenticator.JSON_CONTENT_TYPE)
        .method(method, body.toRequestBody(JSON_MEDIA_TYPE))
        .build()

    private fun execute(request: Request, allowNotFound: Boolean = false): StorageResponse {
        val response = try {
            client.newCall(request).execute()
        } catch (e: java.io.IOException) {
            throw FirefoxSyncTransportException(null, "Firefox Sync request failed", e)
        }
        response.use {
            val body = readBody(it)
            if (!it.isSuccessful && !(allowNotFound && it.code == 404)) {
                throw FirefoxSyncTransportException(it.code, "Firefox Sync request returned ${it.code}")
            }
            return StorageResponse(
                statusCode = it.code,
                body = body,
                lastModified = it.header("X-Last-Modified")?.toDoubleOrNull()?.takeIf { value -> value.isFinite() && value >= 0 },
                nextOffset = it.header("X-Weave-Next-Offset")?.takeIf { token -> token.matches(Regex("[A-Za-z0-9._:-]{1,128}")) },
                serverTimestamp = it.header("X-Weave-Timestamp")?.toDoubleOrNull()?.takeIf { value -> value.isFinite() }?.toLong(),
            )
        }
    }

    private fun readBody(response: Response): String {
        val body = response.body ?: return ""
        val source = body.source()
        if (source.request(MAX_RESPONSE_BYTES.toLong() + 1)) {
            throw FirefoxSyncTransportException(response.code, "Response too large")
        }
        return SyncEncoding.decodeUtf8(source.readByteArray())
    }

    private fun requireToken(accessToken: String) {
        require(accessToken.isNotEmpty() && accessToken.none { it.isWhitespace() || it.isISOControl() }) { "Invalid access token" }
    }

    private fun requireHttps(url: String): String {
        val uri = runCatching { URI(url) }.getOrElse { throw IllegalArgumentException("Invalid URL", it) }
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in LOCAL_HOSTS)) { "Sync endpoints must use https" }
        return url
    }

    private fun formatTimestamp(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    private class StorageResponse(
        val statusCode: Int,
        val body: String,
        val lastModified: Double?,
        val nextOffset: String?,
        val serverTimestamp: Long?,
    )

    companion object {
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")
    }
}
