package dev.sk2andy.materialbrowser.data.sync.firefox

import dev.sk2andy.firefoxsync.FirefoxAccountConfig
import dev.sk2andy.firefoxsync.FirefoxAccountLoginAttempt
import dev.sk2andy.firefoxsync.FirefoxAccountProfile
import dev.sk2andy.firefoxsync.FirefoxAccountTokens
import dev.sk2andy.firefoxsync.FirefoxSyncKeys
import dev.sk2andy.firefoxsync.FirefoxSyncTransport
import dev.sk2andy.firefoxsync.FirefoxSyncTransportException
import dev.sk2andy.firefoxsync.SyncBso
import dev.sk2andy.firefoxsync.SyncCollectionKeys
import dev.sk2andy.firefoxsync.SyncCollectionPage
import dev.sk2andy.firefoxsync.SyncKeyBundle
import dev.sk2andy.firefoxsync.SyncPostResult
import dev.sk2andy.firefoxsync.SyncRecordCrypto
import dev.sk2andy.firefoxsync.SyncStorageCodec
import dev.sk2andy.firefoxsync.SyncStorageCredentials
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncCache
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncDefaults
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncLoginOutcome
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncRepositoryState
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncSessionSecrets
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncSettings
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncStatus
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncVault
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirefoxSyncRepositoryTest {
    private val kSync = ByteArray(64) { (it * 5).toByte() }
    private val kid = "1700000000000-S7Bvjk46dxXSAdVz0KpCNw"
    private val spacesBundle = SyncKeyBundle(ByteArray(32) { 8 }, ByteArray(32) { 9 })
    private val recordCrypto = SyncRecordCrypto()
    private val settingsStore = MemorySettingsStore()
    private val vaultStore = MemoryVaultStore()
    private val cacheStore = MemoryCacheStore()
    private val transport = FakeTransport()
    private var nowSeconds = 1_700_000_000L
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
        override fun instant(): Instant = Instant.ofEpochSecond(nowSeconds)
    }

    private fun repository() = FirefoxSyncRepository(
        settingsStore = settingsStore,
        vaultStore = vaultStore,
        cacheStore = cacheStore,
        config = FirefoxSyncDefaults.accountConfig,
        transport = transport,
        keyUnwrapper = { _, jwe -> require(jwe == "jwe-ok") { "bad jwe" }; FirefoxSyncKeys(kSync, kid) },
        clock = clock,
        executor = Executors.newSingleThreadExecutor(),
    )

    @Test
    fun `login exchanges the code stores the session and syncs Zen spaces`() {
        val repository = repository()
        val states = mutableListOf<FirefoxSyncRepositoryState>()
        repository.observe(states::add)
        val attempt = repository.beginLogin()
        assertTrue(repository.authorizationUrl(attempt).startsWith("https://accounts.firefox.com/authorization?client_id=a2270f727f45f648"))
        repository.cancelLogin().get()
        val again = repository.beginLogin()

        val outcome = repository.completeLogin("c0de").get()
        assertEquals(FirefoxSyncLoginOutcome.SignedIn, outcome)
        assertTrue(transport.tokenBodies.single().contains("\"code\":\"c0de\"") && transport.tokenBodies.single().contains(again.codeVerifier))
        val state = repository.currentState()
        assertEquals(FirefoxSyncStatus.Ready, state.status)
        assertEquals("zen@example.org", state.settings?.accountEmail)
        assertEquals(listOf("ws-1"), state.snapshot.orderedSpaces().map { it.id })
        assertEquals(1, state.counts.pinnedTabs)
        assertEquals(listOf("weird"), cacheStore.value.skippedRecordIds)
        assertNull(vaultStore.value?.pendingLogin)
        assertEquals("acc-1", vaultStore.value?.session?.accessToken)
        assertTrue(states.any { it.status == FirefoxSyncStatus.SigningIn })
        assertTrue(states.any { it.status == FirefoxSyncStatus.Syncing })
    }

    @Test
    fun `completing a login without an attempt or with a rejected code fails safely`() {
        val repository = repository()
        assertEquals(FirefoxSyncLoginOutcome.NoLoginInProgress, repository.completeLogin("x").get())
        repository.beginLogin()
        transport.tokenFailure = FirefoxSyncTransportException(400, "invalid grant")
        assertEquals(FirefoxSyncLoginOutcome.AuthenticationFailed, repository.completeLogin("bad").get())
        assertEquals(FirefoxSyncStatus.SignedOut, repository.currentState().status)
        assertEquals("invalid grant", repository.currentState().lastError)
        assertNull(vaultStore.value?.pendingLogin)
    }

    @Test
    fun `refresh skips unchanged collections and refreshes expired access tokens`() {
        signedInFixture(accessTokenExpiresAt = nowSeconds + 3_600)
        cacheStore.value = FirefoxSyncCache(spacesLastModified = 1_700.0, records = emptyList(), skippedRecordIds = emptyList(), syncedAt = "earlier")
        val repository = repository()
        assertEquals(FirefoxSyncStatus.Ready, repository.currentState().status)

        assertTrue(repository.refresh().get())
        assertEquals(0, transport.collectionFetches)
        assertEquals("earlier", repository.currentState().lastSyncAt)

        assertTrue(repository.refresh(force = true).get())
        assertEquals(1, transport.collectionFetches)
        assertEquals(1, repository.currentState().counts.spaces)

        nowSeconds += 3_600
        transport.infoLastModified = 1_701.0
        assertTrue(repository.refresh().get())
        assertEquals(1, transport.tokenBodies.count { it.contains("refresh_token") })
        assertEquals("acc-1", vaultStore.value?.session?.accessToken)
        assertEquals(2, transport.collectionFetches)
    }

    @Test
    fun `missing engines and auth failures map to statuses without clearing the session`() {
        signedInFixture(accessTokenExpiresAt = nowSeconds + 3_600)
        transport.spacesEngineVersion = null
        val repository = repository()
        assertFalse(repository.refresh(force = true).get())
        assertEquals(FirefoxSyncStatus.EngineMissing, repository.currentState().status)

        transport.spacesEngineVersion = 4
        assertFalse(repository.refresh(force = true).get())
        assertEquals(FirefoxSyncStatus.Incompatible, repository.currentState().status)

        transport.spacesEngineVersion = 3
        transport.credentialsFailure = FirefoxSyncTransportException(401, "expired")
        assertFalse(repository.refresh(force = true).get())
        assertEquals(FirefoxSyncStatus.AuthError, repository.currentState().status)
        assertNotNull(vaultStore.value?.session)

        transport.credentialsFailure = FirefoxSyncTransportException(null, "offline")
        assertFalse(repository.refresh(force = true).get())
        assertEquals(FirefoxSyncStatus.Offline, repository.currentState().status)
    }

    @Test
    fun `sign out destroys the refresh token and clears every store`() {
        signedInFixture(accessTokenExpiresAt = nowSeconds + 3_600)
        cacheStore.value = FirefoxSyncCache(1.0, emptyList(), emptyList(), "t")
        val repository = repository()
        repository.signOut().get()
        assertEquals(FirefoxSyncRepositoryState.SIGNED_OUT, repository.currentState())
        assertNull(vaultStore.value)
        assertNull(settingsStore.value)
        assertEquals(FirefoxSyncCache.EMPTY, cacheStore.value)
        assertTrue(transport.destroyed.single().contains("ref-1"))
    }

    private fun signedInFixture(accessTokenExpiresAt: Long) {
        settingsStore.value = FirefoxSyncSettings("0123456789abcdef0123456789abcdef", "zen@example.org", "t")
        vaultStore.value = FirefoxSyncVault(
            session = FirefoxSyncSessionSecrets("acc-0", accessTokenExpiresAt, "ref-1", kSync, kid),
        )
    }

    private class MemorySettingsStore : FirefoxSyncSettingsStore {
        var value: FirefoxSyncSettings? = null
        override fun load() = value
        override fun save(value: FirefoxSyncSettings): Boolean { this.value = value; return true }
        override fun clear(): Boolean { value = null; return true }
    }

    private class MemoryVaultStore : FirefoxSyncVaultStore {
        var value: FirefoxSyncVault? = null
        override fun load() = value
        override fun save(value: FirefoxSyncVault): Boolean { this.value = value; return true }
        override fun clear() { value = null }
    }

    private class MemoryCacheStore : FirefoxSyncCacheStore {
        var value: FirefoxSyncCache = FirefoxSyncCache.EMPTY
        override fun load() = value
        override fun save(value: FirefoxSyncCache): Boolean { this.value = value; return true }
        override fun clear() { value = FirefoxSyncCache.EMPTY }
    }

    private inner class FakeTransport : FirefoxSyncTransport {
        val tokenBodies = mutableListOf<String>()
        val destroyed = mutableListOf<String>()
        var tokenFailure: FirefoxSyncTransportException? = null
        var credentialsFailure: FirefoxSyncTransportException? = null
        var spacesEngineVersion: Int? = 3
        var infoLastModified = 1_700.0
        var collectionFetches = 0
        private var tokenCounter = 0

        override fun requestTokens(config: FirefoxAccountConfig, body: String): FirefoxAccountTokens {
            tokenBodies += body
            tokenFailure?.let { throw it }
            tokenCounter++
            return FirefoxAccountTokens("acc-$tokenCounter", "ref-$tokenCounter", 3_600, "profile oldsync", "jwe-ok", null)
        }

        override fun destroyToken(config: FirefoxAccountConfig, body: String) { destroyed += body }

        override fun fetchProfile(config: FirefoxAccountConfig, accessToken: String) =
            FirefoxAccountProfile("0123456789abcdef0123456789abcdef", "zen@example.org", "Zen")

        override fun fetchStorageCredentials(tokenServerUrl: String, accessToken: String, kid: String): SyncStorageCredentials {
            credentialsFailure?.let { throw it }
            return SyncStorageCredentials("hawk", "key", 1, "https://node.example/1.5/1", 3_600, null)
        }

        override fun infoCollections(credentials: SyncStorageCredentials): Map<String, Double> = mapOf("spaces" to infoLastModified)

        override fun getRecord(credentials: SyncStorageCredentials, collection: String, id: String): SyncBso? = when ("$collection/$id") {
            "meta/global" -> {
                val engines = JSONObject().put("clients", JSONObject().put("version", 1).put("syncID", "c"))
                spacesEngineVersion?.let { engines.put("spaces", JSONObject().put("version", it).put("syncID", "s")) }
                SyncBso("global", JSONObject().put("syncID", "g").put("storageVersion", 5).put("engines", engines).toString())
            }
            "crypto/keys" -> {
                val cleartext = SyncStorageCodec.encodeCollectionKeys(
                    SyncCollectionKeys(SyncKeyBundle(ByteArray(32) { 1 }, ByteArray(32) { 2 }), mapOf("spaces" to spacesBundle)),
                )
                SyncBso("keys", recordCrypto.encrypt(SyncKeyBundle.fromKSync(kSync), cleartext).encode())
            }
            else -> null
        }

        override fun getCollection(credentials: SyncStorageCredentials, collection: String, newerThan: Double?, limit: Int, offset: String?): SyncCollectionPage {
            collectionFetches++
            fun encrypted(id: String, cleartext: String) = SyncBso(id, recordCrypto.encrypt(spacesBundle, JSONObject(cleartext)).encode(), infoLastModified)
            return SyncCollectionPage(
                records = listOf(
                    encrypted("ws-1", """{"id":"ws-1","kind":"space","data":{"uuid":"ws-1","name":"Home","children":["tab-1"]}}"""),
                    encrypted("tab-1", """{"id":"tab-1","kind":"tab","data":{"tabId":"tab-1","url":"https://a.example/","workspaceUuid":"ws-1"}}"""),
                    encrypted("weird", """{"id":"weird","kind":"widget","data":{}}"""),
                    encrypted("layout", """{"id":"layout","kind":"layout","data":{"spaces":["ws-1"],"essentials":{}}}"""),
                ),
                lastModified = infoLastModified,
                nextOffset = null,
            )
        }

        override fun postRecords(credentials: SyncStorageCredentials, collection: String, records: List<SyncBso>, ifUnmodifiedSince: Double?): SyncPostResult =
            SyncPostResult(infoLastModified, records.map(SyncBso::id), emptyMap())
    }
}
