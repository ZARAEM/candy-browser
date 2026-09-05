package dev.sk2andy.firefoxsync

import java.net.URI
import java.security.KeyFactory
import java.security.spec.ECPrivateKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FirefoxAccountOAuthTest {
    private val config = FirefoxAccountConfig(
        clientId = "a2270f727f45f648",
        redirectUri = FirefoxAccountConfig.WEB_CHANNEL_REDIRECT_URI,
    )
    private val attempt = FirefoxAccountLoginAttempt(
        state = "st4te",
        codeVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
        keysPrivateKeyPkcs8 = staticPrivateKeyPkcs8(),
        keysPublicJwk = """{"crv":"P-256","kty":"EC","x":"cTX6T9k6Cdzpi79oG0v89Q58DWNU5ir7C_8qNClheGU","y":"7UwfAt25Aj7lalV-UV1qncZsEfIglg3llDNN9Yh3ZyQ"}""",
    )

    @Test
    fun `code challenge follows RFC 7636 S256`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", FirefoxAccountOAuth.codeChallenge(attempt.codeVerifier))
    }

    @Test
    fun `authorization URL carries PKCE keys_jwk and the web channel context`() {
        val url = URI(FirefoxAccountOAuth.authorizationUrl(config, attempt))
        assertEquals("accounts.firefox.com", url.host)
        assertEquals("/authorization", url.path)
        val query = url.rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("a2270f727f45f648", query["client_id"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", query["code_challenge"])
        assertEquals("offline", query["access_type"])
        assertEquals("oauth_webchannel_v1", query["context"])
        // accounts.firefox.com answers "Invalid Query Parameters" when the web-channel URN is sent as redirect_uri.
        assertNull(query["redirect_uri"])
        assertEquals("https%3A%2F%2Fidentity.mozilla.com%2Fapps%2Foldsync+profile", query["scope"])
        assertEquals(
            attempt.keysPublicJwk,
            SyncEncoding.decodeUtf8(SyncEncoding.decodeBase64Url(query.getValue("keys_jwk"))),
        )
        assertThrows(IllegalArgumentException::class.java) {
            FirefoxAccountOAuth.authorizationUrl(config, attempt, scopes = listOf("profile"))
        }
    }

    @Test
    fun `authorization URL for a redirect client sends redirect_uri without a context`() {
        val redirectConfig = config.copy(redirectUri = "https://candy.example/oauth/done")
        val url = URI(FirefoxAccountOAuth.authorizationUrl(redirectConfig, attempt))
        val query = url.rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("https%3A%2F%2Fcandy.example%2Foauth%2Fdone", query["redirect_uri"])
        assertNull(query["context"])
    }

    @Test
    fun `redirect and web channel deliveries require the matching state`() {
        val redirectConfig = config.copy(redirectUri = "https://candy.example/oauth/done")
        assertEquals(
            "c0de",
            FirefoxAccountOAuth.parseRedirect(redirectConfig, attempt, "https://candy.example/oauth/done?code=c0de&state=st4te"),
        )
        assertNull(FirefoxAccountOAuth.parseRedirect(redirectConfig, attempt, "https://candy.example/oauth/done?code=c0de&state=other"))
        assertNull(FirefoxAccountOAuth.parseRedirect(redirectConfig, attempt, "https://evil.example/?code=c0de&state=st4te"))
        val message = """{"id":"account_updates","message":{"command":"fxaccounts:oauth_login","data":{"code":"c0de","state":"st4te","declinedSyncEngines":[]}}}"""
        assertEquals("c0de", FirefoxAccountOAuth.parseWebChannelMessage(attempt, message))
        assertNull(FirefoxAccountOAuth.parseWebChannelMessage(attempt, message.replace("st4te", "nope")))
        assertNull(FirefoxAccountOAuth.parseWebChannelMessage(attempt, """{"id":"other","message":{}}"""))
    }

    @Test
    fun `token exchange bodies are public client PKCE requests`() {
        assertEquals(
            """{"client_id":"a2270f727f45f648","code":"c0de","code_verifier":"dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk","grant_type":"authorization_code"}""",
            FirefoxAccountOAuth.encodeCodeExchange(config, attempt, "c0de"),
        )
        val refresh = FirefoxAccountOAuth.encodeRefresh(config, "r3fresh")
        assertTrue(refresh.contains("\"grant_type\":\"refresh_token\"") && refresh.contains("\"refresh_token\":\"r3fresh\""))
    }

    @Test
    fun `token response requires bearer tokens with positive expiry`() {
        val tokens = FirefoxAccountOAuth.decodeTokenResponse(
            """{"access_token":"acc","refresh_token":"ref","expires_in":3600,"scope":"profile https://identity.mozilla.com/apps/oldsync","token_type":"bearer","keys_jwe":"a..b.c.d","auth_at":1}""",
        )
        assertEquals("acc", tokens.accessToken)
        assertEquals("ref", tokens.refreshToken)
        assertEquals("a..b.c.d", tokens.keysJwe)
        assertNull(tokens.sessionToken)
        assertThrows(IllegalArgumentException::class.java) {
            FirefoxAccountOAuth.decodeTokenResponse("""{"access_token":"acc","expires_in":0,"scope":"","token_type":"bearer"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FirefoxAccountOAuth.decodeTokenResponse("""{"access_token":"acc","expires_in":10,"scope":"","token_type":"mac"}""")
        }
    }

    @Test
    fun `decrypts the oldsync scoped key from a reference keys_jwe`() {
        val keys = FirefoxAccountOAuth.decryptSyncKeys(attempt, KEYS_JWE)
        assertArrayEquals(ByteArray(64) { it.toByte() }, keys.kSync)
        assertEquals("1690000000000-_eq5rPNxA2K9JljNyaKejw", keys.kid)
    }

    @Test
    fun `rejects a keys_jwe whose tag or header was altered`() {
        val parts = KEYS_JWE.split('.')
        val badTag = (parts.dropLast(1) + "BgvFSGvRLE9Aap6aTuI9JQ").joinToString(".")
        assertThrows(IllegalArgumentException::class.java) { FirefoxAccountOAuth.decryptSyncKeys(attempt, badTag) }
        val rsaHeader = SyncEncoding.base64Url(SyncEncoding.utf8("""{"alg":"RSA-OAEP","enc":"A256GCM"}"""))
        assertThrows(IllegalArgumentException::class.java) {
            FirefoxAccountOAuth.decryptSyncKeys(attempt, (listOf(rsaHeader) + parts.drop(1)).joinToString("."))
        }
        assertThrows(IllegalArgumentException::class.java) { FirefoxAccountOAuth.decryptSyncKeys(attempt, "a.b.c") }
    }

    @Test
    fun `fresh login attempts carry a P-256 public JWK matching the private key`() {
        val fresh = FirefoxAccountOAuth.beginLogin()
        val jwk = SyncEncoding.parseJsonObject(fresh.keysPublicJwk)
        assertEquals("P-256", jwk.getString("crv"))
        assertEquals(32, SyncEncoding.decodeBase64Url(jwk.getString("x")).size)
        assertEquals(43, fresh.codeVerifier.length)
        assertEquals(22, fresh.state.length)
    }

    @Test
    fun `config rejects non https endpoints and malformed client ids`() {
        assertThrows(IllegalArgumentException::class.java) { config.copy(clientId = "not-hex") }
        assertThrows(IllegalArgumentException::class.java) { config.copy(contentUrl = "http://accounts.firefox.com") }
        assertThrows(IllegalArgumentException::class.java) { config.copy(oauthUrl = "https://oauth.accounts.firefox.com/v1/") }
    }

    private fun staticPrivateKeyPkcs8(): ByteArray {
        val scalar = java.math.BigInteger(1, ByteArray(32) { 9 })
        val spec = ECPrivateKeySpec(scalar, FirefoxAccountOAuth.p256Parameters())
        return KeyFactory.getInstance("EC").generatePrivate(spec).encoded
    }

    private companion object {
        const val KEYS_JWE = "eyJhbGciOiJFQ0RILUVTIiwiZW5jIjoiQTI1NkdDTSIsImVwayI6eyJrdHkiOiJFQyIsImNydiI6IlAtMjU2IiwieCI6IklKd3hlMk41TmQwOW9jVlBZMGxkLXpINWZTazk4SVZ4QXlCWlhKcXN1RDgiLCJ5IjoiM2VUR244RjZESFRDRE1hU1ppOEVtSks2TjZTNlI5TEhETmlwbVlZNUg1cyJ9fQ..FRYXGBkaGxwdHh8g.usk_pqj_CASAq0_4RPIC9d7c5E4lZerzhHWKxV71b3D3m9QgdL-gag9mifVRILqBuvhcqmpGD1Dxh9vArdDX78n5F3zIolZFoZTNGbAI8lnWbrLKNvCAteGZsPsHSUmJwepe8320buNyf2j2pxgyGJDhoyju1qPG9XuS3lsg3sRUqqEzzu7-l4UA1KiRHd-SoF8bkAjHd_M3YLtAfyf7ytGABKqF37ei4bcOhyqEI6tc-pUfN5XQNvCRTUtQElXmDh03_iOH_sMHvv9HrwwrtwEhvmGZCiZvPE210GXyuWvi7n53UwZEDP4LEHZ8g8ITFh0VVeIG0-wo.AgvFSGvRLE9Aap6aTuI9JQ"
    }
}
