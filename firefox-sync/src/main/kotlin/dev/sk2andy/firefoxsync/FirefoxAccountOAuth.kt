package dev.sk2andy.firefoxsync

import java.math.BigInteger
import java.net.URI
import java.net.URLEncoder
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Mozilla account OAuth configuration. [clientId] must be registered with Mozilla for the
 * `oldsync` scope; the redirect is delivered either through a redirect URI or through the
 * `WebChannelMessageToChrome` event the login page dispatches for web-channel clients.
 */
data class FirefoxAccountConfig(
    val clientId: String,
    val redirectUri: String,
    val contentUrl: String = DEFAULT_CONTENT_URL,
    val oauthUrl: String = DEFAULT_OAUTH_URL,
    val tokenServerUrl: String = DEFAULT_TOKEN_SERVER_URL,
    val profileUrl: String = DEFAULT_PROFILE_URL,
) {
    init {
        require(clientId.matches(Regex("[0-9a-f]{16}"))) { "Client id must be 16 hex characters" }
        require(redirectUri.isNotEmpty() && redirectUri.length <= 512 && redirectUri.none(Char::isWhitespace)) { "Invalid redirect URI" }
        listOf(contentUrl, oauthUrl, tokenServerUrl, profileUrl).forEach { url ->
            val uri = runCatching { URI(url) }.getOrElse { throw IllegalArgumentException("Invalid URL $url", it) }
            val loopback = uri.scheme == "http" && uri.host in LOOPBACK_HOSTS
            require((uri.scheme == "https" || loopback) && !uri.host.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null) {
                "Account URLs must be https without query or fragment"
            }
            require(!url.endsWith("/")) { "Account URLs must not end with a slash" }
        }
    }

    companion object {
        const val DEFAULT_CONTENT_URL = "https://accounts.firefox.com"
        const val DEFAULT_OAUTH_URL = "https://oauth.accounts.firefox.com/v1"
        const val DEFAULT_TOKEN_SERVER_URL = "https://token.services.mozilla.com/1.0/sync/1.5"
        const val DEFAULT_PROFILE_URL = "https://profile.accounts.firefox.com/v1"
        const val WEB_CHANNEL_REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob:oauth-redirect-webchannel"
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")
    }
}

/** Subset of the Mozilla account profile Candy shows to identify the signed-in account. */
data class FirefoxAccountProfile(
    val uid: String,
    val email: String?,
    val displayName: String?,
)

/** Secret state for one in-flight login: PKCE verifier, CSRF state and the key-wrapping key pair. */
class FirefoxAccountLoginAttempt(
    val state: String,
    val codeVerifier: String,
    keysPrivateKeyPkcs8: ByteArray,
    val keysPublicJwk: String,
) {
    val keysPrivateKeyPkcs8: ByteArray = keysPrivateKeyPkcs8.copyOf()

    fun destroy() = keysPrivateKeyPkcs8.fill(0)

    override fun toString(): String = "FirefoxAccountLoginAttempt(state=$state)"
}

data class FirefoxAccountTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Long,
    val scope: String,
    val keysJwe: String?,
    val sessionToken: String?,
)

/** Pure request builders and response parsers for the Mozilla account OAuth code flow. */
object FirefoxAccountOAuth {
    const val SYNC_SCOPE = "https://identity.mozilla.com/apps/oldsync"
    const val PROFILE_SCOPE = "profile"
    const val DEFAULT_ENTRYPOINT = "candy-browser"
    const val WEB_CHANNEL_CONTEXT = "oauth_webchannel_v1"
    const val WEB_CHANNEL_ID = "account_updates"
    const val WEB_CHANNEL_LOGIN_COMMAND = "fxaccounts:oauth_login"
    const val WEB_CHANNEL_STATUS_COMMAND = "fxaccounts:fxa_status"
    const val WEB_CHANNEL_CAN_LINK_COMMAND = "fxaccounts:can_link_account"

    private const val MAX_TOKEN_LENGTH = 4_096
    private const val MAX_JWE_LENGTH = 16_384

    fun beginLogin(random: SecureRandom = SecureRandom()): FirefoxAccountLoginAttempt {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), random)
        val pair = generator.generateKeyPair()
        val publicKey = pair.public as ECPublicKey
        return FirefoxAccountLoginAttempt(
            state = SyncEncoding.base64Url(ByteArray(16).also(random::nextBytes)),
            codeVerifier = SyncEncoding.base64Url(ByteArray(32).also(random::nextBytes)),
            keysPrivateKeyPkcs8 = pair.private.encoded,
            keysPublicJwk = publicJwk(publicKey),
        )
    }

    fun codeChallenge(codeVerifier: String): String =
        SyncEncoding.base64Url(MessageDigest.getInstance("SHA-256").digest(SyncEncoding.utf8(codeVerifier)))

    fun authorizationUrl(
        config: FirefoxAccountConfig,
        attempt: FirefoxAccountLoginAttempt,
        scopes: List<String> = listOf(SYNC_SCOPE, PROFILE_SCOPE),
        entrypoint: String = DEFAULT_ENTRYPOINT,
        context: String? = if (config.redirectUri == FirefoxAccountConfig.WEB_CHANNEL_REDIRECT_URI) WEB_CHANNEL_CONTEXT else null,
        email: String? = null,
    ): String {
        require(scopes.isNotEmpty() && SYNC_SCOPE in scopes) { "Sync scope is required" }
        require(entrypoint.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Invalid entrypoint" }
        val parameters = buildList {
            add("client_id" to config.clientId)
            add("scope" to scopes.joinToString(" "))
            add("state" to attempt.state)
            add("code_challenge_method" to "S256")
            add("code_challenge" to codeChallenge(attempt.codeVerifier))
            add("access_type" to "offline")
            add("keys_jwk" to SyncEncoding.base64Url(SyncEncoding.utf8(attempt.keysPublicJwk)))
            add("redirect_uri" to config.redirectUri)
            add("response_type" to "code")
            add("action" to "email")
            add("entrypoint" to entrypoint)
            context?.let { add("context" to it) }
            email?.takeIf { it.isNotBlank() && it.length <= 320 }?.let { add("email" to it) }
        }
        return config.contentUrl + "/authorization?" + parameters.joinToString("&") { (key, value) ->
            key + "=" + URLEncoder.encode(value, "UTF-8")
        }
    }

    /** Extracts the authorization code from a redirect URI, rejecting mismatched state. */
    fun parseRedirect(config: FirefoxAccountConfig, attempt: FirefoxAccountLoginAttempt, redirect: String): String? {
        if (!redirect.startsWith(config.redirectUri)) return null
        val query = redirect.substringAfter('?', "").substringBefore('#')
        val values = query.split('&').filter(String::isNotEmpty).associate { pair ->
            pair.substringBefore('=') to java.net.URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        }
        return completeLogin(attempt, code = values["code"], state = values["state"])
    }

    /**
     * Extracts the authorization code from an `fxaccounts:oauth_login` web-channel message, the
     * event-based delivery that Firefox and GeckoView clients use instead of a redirect.
     */
    fun parseWebChannelMessage(attempt: FirefoxAccountLoginAttempt, rawMessage: String): String? {
        val envelope = runCatching { SyncEncoding.parseJsonObject(rawMessage) }.getOrNull() ?: return null
        if (envelope.optString("id") != WEB_CHANNEL_ID) return null
        val message = envelope.optJSONObject("message") ?: return null
        if (message.optString("command") != WEB_CHANNEL_LOGIN_COMMAND) return null
        val data = message.optJSONObject("data") ?: return null
        return completeLogin(attempt, code = data.optString("code", ""), state = data.optString("state", ""))
    }

    fun encodeCodeExchange(config: FirefoxAccountConfig, attempt: FirefoxAccountLoginAttempt, code: String): String =
        SyncEncoding.canonicalJson(
            JSONObject()
                .put("client_id", config.clientId)
                .put("code", code)
                .put("code_verifier", attempt.codeVerifier)
                .put("grant_type", "authorization_code"),
        )

    fun encodeRefresh(config: FirefoxAccountConfig, refreshToken: String, scopes: List<String> = listOf(SYNC_SCOPE)): String =
        SyncEncoding.canonicalJson(
            JSONObject()
                .put("client_id", config.clientId)
                .put("grant_type", "refresh_token")
                .put("refresh_token", refreshToken)
                .put("scope", scopes.joinToString(" ")),
        )

    fun encodeDestroy(config: FirefoxAccountConfig, token: String): String =
        SyncEncoding.canonicalJson(JSONObject().put("client_id", config.clientId).put("token", token))

    fun decodeTokenResponse(raw: String): FirefoxAccountTokens {
        val value = SyncEncoding.parseJsonObject(raw)
        require(value.optString("token_type").equals("bearer", ignoreCase = true)) { "Unexpected token type" }
        return FirefoxAccountTokens(
            accessToken = value.strictString("access_token", MAX_TOKEN_LENGTH).also { require(it.isNotEmpty()) { "Empty access token" } },
            refreshToken = value.optionalString("refresh_token", MAX_TOKEN_LENGTH)?.takeIf(String::isNotEmpty),
            expiresInSeconds = value.strictLong("expires_in").also { require(it > 0) { "Invalid expiry" } },
            scope = value.strictString("scope", 1_024),
            keysJwe = value.optionalString("keys_jwe", MAX_JWE_LENGTH)?.takeIf(String::isNotEmpty),
            sessionToken = value.optionalString("session_token", MAX_TOKEN_LENGTH)?.takeIf(String::isNotEmpty),
        )
    }

    fun decodeProfileResponse(raw: String): FirefoxAccountProfile {
        val value = SyncEncoding.parseJsonObject(raw)
        return FirefoxAccountProfile(
            uid = value.strictString("uid", 64).also { require(it.matches(Regex("[0-9a-f]{32}"))) { "Invalid account uid" } },
            email = value.optionalString("email", 320)?.takeIf(String::isNotEmpty),
            displayName = value.optionalString("displayName", 256)?.takeIf(String::isNotEmpty),
        )
    }

    /**
     * Unwraps the `keys_jwe` compact JWE (ECDH-ES with A256GCM) returned with the sync scope and
     * returns the `oldsync` scoped key as kSync plus its `kid`.
     */
    fun decryptSyncKeys(attempt: FirefoxAccountLoginAttempt, keysJwe: String): FirefoxSyncKeys {
        require(keysJwe.length <= MAX_JWE_LENGTH) { "keys_jwe too large" }
        val parts = keysJwe.split('.')
        require(parts.size == 5 && parts[1].isEmpty()) { "Expected a five-part ECDH-ES compact JWE" }
        val header = SyncEncoding.parseJsonObject(SyncEncoding.decodeUtf8(SyncEncoding.decodeBase64Url(parts[0], maxBytes = 4_096)))
        require(header.optString("alg") == "ECDH-ES" && header.optString("enc") == "A256GCM") { "Unsupported JWE algorithm" }
        val ephemeral = header.optJSONObject("epk") ?: throw IllegalArgumentException("Missing epk")
        require(ephemeral.optString("kty") == "EC" && ephemeral.optString("crv") == "P-256") { "Unsupported epk" }
        val iv = SyncEncoding.decodeBase64Url(parts[2], expectedBytes = 12)
        val ciphertext = SyncEncoding.decodeBase64Url(parts[3], maxBytes = MAX_JWE_LENGTH)
        val tag = SyncEncoding.decodeBase64Url(parts[4], expectedBytes = 16)

        val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(attempt.keysPrivateKeyPkcs8)) as ECPrivateKey
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(publicKeyFromJwk(ephemeral, privateKey.params), true)
        val sharedSecret = agreement.generateSecret()
        val contentKey = try {
            concatKdfSha256(sharedSecret, algorithmId = "A256GCM", keyBits = 256)
        } finally {
            sharedSecret.fill(0)
        }
        val plaintext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(SyncEncoding.utf8(parts[0]))
            runCatching { cipher.doFinal(ciphertext + tag) }
                .getOrElse { throw IllegalArgumentException("keys_jwe authentication failed", it) }
        } finally {
            contentKey.fill(0)
        }
        return try {
            val keys = SyncEncoding.parseJsonObject(SyncEncoding.decodeUtf8(plaintext))
            val syncKey = keys.optJSONObject(SYNC_SCOPE) ?: throw IllegalArgumentException("Missing oldsync scoped key")
            require(syncKey.optString("kty") == "oct") { "Unexpected scoped key type" }
            val kSync = SyncEncoding.decodeBase64Url(syncKey.strictString("k", 128), expectedBytes = SyncKeyBundle.KSYNC_BYTES)
            try {
                FirefoxSyncKeys(kSync = kSync, kid = syncKey.strictString("kid", 64))
            } finally {
                kSync.fill(0)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun completeLogin(attempt: FirefoxAccountLoginAttempt, code: String?, state: String?): String? {
        if (code.isNullOrEmpty() || code.length > MAX_TOKEN_LENGTH || state == null) return null
        if (!MessageDigest.isEqual(SyncEncoding.utf8(state), SyncEncoding.utf8(attempt.state))) return null
        return code
    }

    private fun publicJwk(key: ECPublicKey): String = SyncEncoding.canonicalJson(
        JSONObject()
            .put("crv", "P-256")
            .put("kty", "EC")
            .put("x", SyncEncoding.base64Url(coordinate(key.w.affineX)))
            .put("y", SyncEncoding.base64Url(coordinate(key.w.affineY))),
    )

    private fun coordinate(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().dropWhile { it == 0.toByte() }
        require(bytes.size <= 32) { "Invalid P-256 coordinate" }
        return ByteArray(32 - bytes.size) + bytes.toByteArray()
    }

    private fun publicKeyFromJwk(jwk: JSONObject, parameters: ECParameterSpec): ECPublicKey {
        val x = BigInteger(1, SyncEncoding.decodeBase64Url(jwk.strictString("x", 64), expectedBytes = 32))
        val y = BigInteger(1, SyncEncoding.decodeBase64Url(jwk.strictString("y", 64), expectedBytes = 32))
        val spec = ECPublicKeySpec(ECPoint(x, y), parameters)
        return runCatching { KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey }
            .getOrElse { throw IllegalArgumentException("Invalid ephemeral public key", it) }
    }

    internal fun p256Parameters(): ECParameterSpec {
        val parameters = AlgorithmParameters.getInstance("EC")
        parameters.init(ECGenParameterSpec("secp256r1"))
        return parameters.getParameterSpec(ECParameterSpec::class.java)
    }

    /** NIST SP 800-56A single-step KDF with SHA-256 as used by JWE ECDH-ES direct key agreement. */
    internal fun concatKdfSha256(sharedSecret: ByteArray, algorithmId: String, keyBits: Int): ByteArray {
        require(keyBits == 256) { "Only 256-bit content keys are supported" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(byteArrayOf(0, 0, 0, 1))
        digest.update(sharedSecret)
        digest.update(lengthPrefixed(SyncEncoding.utf8(algorithmId)))
        digest.update(lengthPrefixed(ByteArray(0)))
        digest.update(lengthPrefixed(ByteArray(0)))
        digest.update(byteArrayOf(0, 0, (keyBits shr 8).toByte(), keyBits.toByte()))
        return digest.digest()
    }

    private fun lengthPrefixed(value: ByteArray): ByteArray =
        byteArrayOf(0, 0, (value.size shr 8).toByte(), value.size.toByte()) + value
}
