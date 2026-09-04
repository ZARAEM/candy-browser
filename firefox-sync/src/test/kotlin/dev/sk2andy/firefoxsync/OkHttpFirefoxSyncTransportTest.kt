package dev.sk2andy.firefoxsync

import java.security.SecureRandom
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpFirefoxSyncTransportTest {
    private val server = MockWebServer()
    private lateinit var transport: OkHttpFirefoxSyncTransport
    private lateinit var credentials: SyncStorageCredentials

    @Before
    fun start() {
        server.start()
        transport = OkHttpFirefoxSyncTransport(random = SecureRandom(), clock = { 1_700_000_000L })
        credentials = SyncStorageCredentials("hawk-id", "hawk-key", 7, server.url("/1.5/7").toString().trimEnd('/'), 3600, null)
    }

    @After
    fun stop() = server.shutdown()

    @Test
    fun `token server request sends bearer token and key id`() {
        server.enqueue(MockResponse().setBody("""{"id":"i","key":"k","uid":7,"api_endpoint":"https://node.example/1.5/7","duration":60}"""))
        val result = transport.fetchStorageCredentials(server.url("/1.0/sync/1.5").toString(), "acc-token", "1700000000000-AAAAAAAAAAAAAAAAAAAAAA")
        assertEquals("https://node.example/1.5/7", result.apiEndpoint)
        val request = server.takeRequest()
        assertEquals("Bearer acc-token", request.getHeader("Authorization"))
        assertEquals("1700000000000-AAAAAAAAAAAAAAAAAAAAAA", request.getHeader("X-KeyID"))
        assertEquals("GET", request.method)
    }

    @Test
    fun `profile request returns the account identity`() {
        server.enqueue(MockResponse().setBody("""{"uid":"0123456789abcdef0123456789abcdef","email":"zen@example.org","displayName":"Zen","avatar":"https://x"}"""))
        val config = FirefoxAccountConfig(
            clientId = "a2270f727f45f648",
            redirectUri = FirefoxAccountConfig.WEB_CHANNEL_REDIRECT_URI,
            profileUrl = server.url("/v1").toString().trimEnd('/'),
        )
        assertThrows(IllegalArgumentException::class.java) {
            config.copy(profileUrl = "http://profile.example/v1")
        }
        val profile = transport.fetchProfile(config, "acc-token")
        assertEquals("zen@example.org", profile.email)
        assertEquals("0123456789abcdef0123456789abcdef", profile.uid)
        val request = server.takeRequest()
        assertEquals("Bearer acc-token", request.getHeader("Authorization"))
        assertEquals("/v1/profile", request.path)
    }

    @Test
    fun `storage requests carry Hawk auth and parse paging headers`() {
        server.enqueue(
            MockResponse()
                .setBody("""[{"id":"ws-1","modified":1700000000.5,"payload":"{}"}]""")
                .addHeader("X-Last-Modified", "1700000000.50")
                .addHeader("X-Weave-Next-Offset", "cursor-2")
                .addHeader("X-Weave-Timestamp", "1700000030.00"),
        )
        val page = transport.getCollection(credentials, "spaces", newerThan = 1_699_999_999.126, limit = 1)
        assertEquals("ws-1", page.records.single().id)
        assertEquals(1_700_000_000.5, requireNotNull(page.lastModified), 0.0)
        assertEquals("cursor-2", page.nextOffset)
        val request = server.takeRequest()
        assertEquals("/1.5/7/storage/spaces?full=1&limit=1&newer=1699999999.13", request.path)
        val authorization = requireNotNull(request.getHeader("Authorization"))
        assertTrue(authorization.startsWith("Hawk id=\"hawk-id\", ts=\"1700000000\", nonce=\""))
        assertTrue(authorization.contains(", mac=\""))

        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(transport.getRecord(credentials, "meta", "global"))
        assertTrue(requireNotNull(server.takeRequest().getHeader("Authorization")).contains("ts=\"1700000030\""))
    }

    @Test
    fun `posting records sends the batch with precondition and payload hash`() {
        server.enqueue(MockResponse().setBody("""{"modified":1700000002.0,"success":["ws-1"],"failed":{}}"""))
        val result = transport.postRecords(credentials, "spaces", listOf(SyncBso("ws-1", "{}")), ifUnmodifiedSince = 1_700_000_001.0)
        assertEquals(listOf("ws-1"), result.success)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("1700000001.00", request.getHeader("X-If-Unmodified-Since"))
        assertEquals("""[{"id":"ws-1","payload":"{}"}]""", request.body.readUtf8())
        assertTrue(requireNotNull(request.getHeader("Authorization")).contains("hash=\""))
    }

    @Test
    fun `http failures surface status codes and non local plain http is refused`() {
        server.enqueue(MockResponse().setResponseCode(412))
        val failure = assertThrows(FirefoxSyncTransportException::class.java) {
            transport.postRecords(credentials, "spaces", listOf(SyncBso("ws-1", "{}")))
        }
        assertTrue(failure.isPreconditionFailed)
        assertThrows(IllegalArgumentException::class.java) {
            transport.infoCollections(credentials.copy(apiEndpoint = "http://sync.example.org/1.5/7"))
        }
        assertThrows(IllegalArgumentException::class.java) { transport.getRecord(credentials, "meta", "../global") }
    }
}
