package dev.sk2andy.firefoxsync

import java.net.URI
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HawkAuthenticatorTest {
    private val authenticator = HawkAuthenticator(
        id = "eyJub2RlIjogImh0dHBzOi8vc3luYy5leGFtcGxlIn0",
        key = "sekrit-hawk-key",
        random = SecureRandom(),
        clock = { 1_700_000_000L },
    )

    @Test
    fun `signs a GET without payload hash like the reference implementation`() {
        val header = authenticator.header(
            method = "GET",
            url = URI("https://sync.example.org/1.5/12345/info/collections"),
            timestamp = 1_700_000_000L,
            nonce = "n0nc3",
            payloadHash = null,
        )
        assertEquals(
            "Hawk id=\"eyJub2RlIjogImh0dHBzOi8vc3luYy5leGFtcGxlIn0\", ts=\"1700000000\", nonce=\"n0nc3\", " +
                "mac=\"QdOW5hNJ5y5b9UMdXFC8nwOb0FCmd+qpF1uysl8fDKo=\"",
            header,
        )
    }

    @Test
    fun `signs a POST with query string and payload hash`() {
        val body = """[{"id":"space-1","payload":"x"}]"""
        val hash = HawkAuthenticator.payloadHash("application/json; charset=utf-8", body)
        assertEquals("bM87F/bWdW6kvo702lf2klIXVR732Kq8ND9ba6bLKxk=", hash)
        val header = authenticator.header(
            method = "post",
            url = URI("https://sync.example.org/1.5/12345/storage/spaces?batch=true"),
            timestamp = 1_700_000_000L,
            nonce = "n0nc3",
            payloadHash = hash,
        )
        assertTrue(header.contains("hash=\"bM87F/bWdW6kvo702lf2klIXVR732Kq8ND9ba6bLKxk=\""))
        assertTrue(header.endsWith("mac=\"kV+kig0fq8Rpk7JExAWYc6h8jOIRp1pxkzSEM4kAbRQ=\""))
    }

    @Test
    fun `public entry point applies the server clock offset and a fresh nonce`() {
        authenticator.noteServerTimestamp(1_700_000_120L)
        assertEquals(120L, authenticator.timestampOffsetSeconds)
        val header = authenticator.header("GET", URI("https://sync.example.org/1.5/1/info/collections"), body = null)
        assertTrue(header.contains("ts=\"1700000120\""))
        assertTrue(Regex("nonce=\"[A-Za-z0-9_-]{11}\"").containsMatchIn(header))
    }

    @Test
    fun `rejects unusable ids keys and schemes`() {
        assertThrows(IllegalArgumentException::class.java) { HawkAuthenticator("bad\"id", "key") }
        assertThrows(IllegalArgumentException::class.java) { HawkAuthenticator("id", "") }
        assertThrows(IllegalArgumentException::class.java) {
            authenticator.header("GET", URI("ftp://sync.example.org/"), body = null)
        }
    }
}
