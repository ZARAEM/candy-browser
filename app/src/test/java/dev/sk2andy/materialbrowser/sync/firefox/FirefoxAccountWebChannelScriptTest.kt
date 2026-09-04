package dev.sk2andy.materialbrowser.sync.firefox

import dev.sk2andy.firefoxsync.FirefoxAccountLoginAttempt
import dev.sk2andy.firefoxsync.FirefoxAccountOAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FirefoxAccountWebChannelScriptTest {
    private val token = "0123456789abcdef0123456789abcdef"

    @Test
    fun `script answers status and link requests and forwards every message with the token`() {
        val script = FirefoxAccountWebChannelScript.javascript(token, FirefoxSyncDefaults.CLIENT_ID)
        assertTrue(script.contains("globalThis.candyFxaBridge"))
        assertTrue(script.contains("'fxaccounts:fxa_status'"))
        assertTrue(script.contains("'fxaccounts:can_link_account'"))
        assertTrue(script.contains("const token = '$token'"))
        assertTrue(script.contains("const engines = [\"tabs\"]"))
        assertTrue(script.contains("WebChannelMessageToChrome") && script.contains("WebChannelMessageToContent"))
        assertFalse(script.contains("eval("))
    }

    @Test
    fun `script rejects unsafe tokens client ids and engines`() {
        assertThrows(IllegalArgumentException::class.java) { FirefoxAccountWebChannelScript.javascript("short", FirefoxSyncDefaults.CLIENT_ID) }
        assertThrows(IllegalArgumentException::class.java) { FirefoxAccountWebChannelScript.javascript("$token'", FirefoxSyncDefaults.CLIENT_ID) }
        assertThrows(IllegalArgumentException::class.java) { FirefoxAccountWebChannelScript.javascript(token, "not-hex") }
        assertThrows(IllegalArgumentException::class.java) { FirefoxAccountWebChannelScript.javascript(token, FirefoxSyncDefaults.CLIENT_ID, listOf("tabs'")) }
    }

    @Test
    fun `envelopes unwrap only with the matching token and feed the OAuth parser`() {
        val attempt = FirefoxAccountLoginAttempt("st4te", "verifier", ByteArray(8), "{}")
        val raw = """{"token":"$token","id":"account_updates","message":{"command":"fxaccounts:oauth_login","messageId":"m1","data":{"code":"c0de","state":"st4te"}}}"""
        val message = requireNotNull(FirefoxAccountWebChannelScript.unwrapEnvelope(raw, token))
        assertFalse(message.has("token"))
        assertEquals("c0de", FirefoxAccountOAuth.parseWebChannelMessage(attempt, message.toString()))
        assertNull(FirefoxAccountWebChannelScript.unwrapEnvelope(raw, "other-token-other-token-other-token"))
        assertNull(FirefoxAccountWebChannelScript.unwrapEnvelope("not json", token))
        assertNull(FirefoxAccountWebChannelScript.unwrapEnvelope(null, token))
    }
}
