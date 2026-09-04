package dev.sk2andy.materialbrowser.data.sync.firefox

import dev.sk2andy.firefoxsync.FirefoxAccountConfig
import dev.sk2andy.firefoxsync.FirefoxAccountLoginAttempt
import dev.sk2andy.firefoxsync.FirefoxAccountOAuth
import dev.sk2andy.firefoxsync.FirefoxSyncConnectOutcome
import dev.sk2andy.firefoxsync.FirefoxSyncKeys
import dev.sk2andy.firefoxsync.FirefoxSyncSession
import dev.sk2andy.firefoxsync.FirefoxSyncTransport
import dev.sk2andy.firefoxsync.FirefoxSyncTransportException
import dev.sk2andy.firefoxsync.OkHttpFirefoxSyncTransport
import dev.sk2andy.firefoxsync.ZenSpacesCodec
import dev.sk2andy.firefoxsync.ZenSpacesFetchOutcome
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncCache
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncDefaults
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncLoginOutcome
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncRepositoryState
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncRules
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncSessionSecrets
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncSettings
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncStatus
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncVault
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns one Mozilla account session and the last Zen `spaces` snapshot. All network and store work
 * runs on a single daemon thread; listeners receive immutable [FirefoxSyncRepositoryState] values
 * on that thread and marshal to main themselves, mirroring `CandySyncRepository`.
 */
class FirefoxSyncRepository(
    private val settingsStore: FirefoxSyncSettingsStore,
    private val vaultStore: FirefoxSyncVaultStore,
    private val cacheStore: FirefoxSyncCacheStore,
    private val config: FirefoxAccountConfig = FirefoxSyncDefaults.accountConfig,
    private val transport: FirefoxSyncTransport = OkHttpFirefoxSyncTransport(),
    private val session: FirefoxSyncSession = FirefoxSyncSession(transport),
    private val keyUnwrapper: (FirefoxAccountLoginAttempt, String) -> FirefoxSyncKeys = FirefoxAccountOAuth::decryptSyncKeys,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "candy-firefox-sync").apply { isDaemon = true }
    },
) : AutoCloseable {
    private val listeners = CopyOnWriteArraySet<(FirefoxSyncRepositoryState) -> Unit>()
    private var cache = cacheStore.load()

    @Volatile
    private var lastBridgeCommand: String? = null

    @Volatile
    private var state = initialState()

    fun currentState(): FirefoxSyncRepositoryState = state

    fun observe(listener: (FirefoxSyncRepositoryState) -> Unit): AutoCloseable {
        listeners += listener
        listener(state)
        return AutoCloseable { listeners -= listener }
    }

    /** Starts a login: the attempt is persisted so the code exchange survives process death. */
    fun beginLogin(): FirefoxAccountLoginAttempt {
        val attempt = FirefoxAccountOAuth.beginLogin(random)
        submit {
            val existing = vaultStore.load()
            existing?.pendingLogin?.destroy()
            vaultStore.save(FirefoxSyncVault(session = existing?.session, pendingLogin = attempt))
            publish(state.copy(status = FirefoxSyncStatus.SigningIn, lastError = null))
        }
        return attempt
    }

    fun authorizationUrl(attempt: FirefoxAccountLoginAttempt): String =
        FirefoxAccountOAuth.authorizationUrl(config, attempt, entrypoint = FirefoxSyncDefaults.ENTRYPOINT)

    fun cancelLogin(): CompletableFuture<Unit> = submit {
        val existing = vaultStore.load()
        if (existing?.pendingLogin != null) {
            existing.pendingLogin.destroy()
            vaultStore.save(FirefoxSyncVault(session = existing.session, pendingLogin = null))
        }
        publish(state.copy(status = if (settingsStore.load() != null && existing?.session != null) FirefoxSyncStatus.Ready else FirefoxSyncStatus.SignedOut))
    }

    fun noteBridgeCommand(command: String) {
        lastBridgeCommand = command.take(64)
        publish(state.copy(lastBridgeCommand = lastBridgeCommand))
    }

    fun completeLogin(code: String): CompletableFuture<FirefoxSyncLoginOutcome> = submit { completeLoginNow(code) }

    fun refresh(force: Boolean = false): CompletableFuture<Boolean> = submit { refreshNow(force) }

    fun signOut(): CompletableFuture<Unit> = submit {
        val vault = vaultStore.load()
        vault?.session?.refreshToken?.let { refreshToken ->
            runCatching { transport.destroyToken(config, FirefoxAccountOAuth.encodeDestroy(config, refreshToken)) }
        }
        vault?.clear()
        vaultStore.clear()
        cacheStore.clear()
        settingsStore.clear()
        cache = FirefoxSyncCache.EMPTY
        publish(FirefoxSyncRepositoryState.SIGNED_OUT)
    }

    override fun close() {
        executor.shutdownNow()
        listeners.clear()
    }

    private fun completeLoginNow(code: String): FirefoxSyncLoginOutcome {
        val vault = vaultStore.load()
        val attempt = vault?.pendingLogin ?: return FirefoxSyncLoginOutcome.NoLoginInProgress
        return try {
            val tokens = transport.requestTokens(config, FirefoxAccountOAuth.encodeCodeExchange(config, attempt, code))
            val keysJwe = tokens.keysJwe ?: return FirefoxSyncLoginOutcome.MissingSyncKeys.also { failLogin(vault, "The account did not return sync keys") }
            val keys = keyUnwrapper(attempt, keysJwe)
            val secrets = try {
                FirefoxSyncSessionSecrets(
                    accessToken = tokens.accessToken,
                    accessTokenExpiresAtEpochSeconds = now() + tokens.expiresInSeconds,
                    refreshToken = tokens.refreshToken,
                    kSync = keys.kSync.copyOf(),
                    kid = keys.kid,
                )
            } finally {
                keys.destroy()
            }
            val profile = runCatching { transport.fetchProfile(config, secrets.accessToken) }.getOrNull()
            val settings = FirefoxSyncSettings(
                accountUid = profile?.uid ?: "unknown",
                accountEmail = profile?.email,
                signedInAt = Instant.now(clock).toString(),
            )
            attempt.destroy()
            vaultStore.save(FirefoxSyncVault(session = secrets, pendingLogin = null))
            settingsStore.save(settings)
            publish(stateFrom(settings, FirefoxSyncStatus.Ready, lastError = null))
            refreshNow(force = true)
            FirefoxSyncLoginOutcome.SignedIn
        } catch (error: FirefoxSyncTransportException) {
            failLogin(vault, error.message)
            if (error.statusCode in 400..499) FirefoxSyncLoginOutcome.AuthenticationFailed else FirefoxSyncLoginOutcome.Failed
        } catch (error: Exception) {
            failLogin(vault, error.message)
            FirefoxSyncLoginOutcome.Failed
        }
    }

    private fun failLogin(vault: FirefoxSyncVault, message: String?) {
        vault.pendingLogin?.destroy()
        vaultStore.save(FirefoxSyncVault(session = vault.session, pendingLogin = null))
        val settings = settingsStore.load()
        publish(
            stateFrom(
                settings = settings,
                status = if (settings != null && vault.session != null) FirefoxSyncStatus.Ready else FirefoxSyncStatus.SignedOut,
                lastError = message,
            ),
        )
    }

    private fun refreshNow(force: Boolean): Boolean {
        val settings = settingsStore.load() ?: return false
        val vault = vaultStore.load() ?: return false
        val stored = vault.session ?: return false
        var secrets = stored.copyForUse()
        publish(stateFrom(settings, FirefoxSyncStatus.Syncing, lastError = null))
        return try {
            if (FirefoxSyncRules.accessTokenNeedsRefresh(secrets, now())) {
                val refreshToken = secrets.refreshToken
                    ?: throw FirefoxSyncTransportException(401, "Access token expired and no refresh token is available")
                val tokens = transport.requestTokens(config, FirefoxAccountOAuth.encodeRefresh(config, refreshToken))
                secrets = secrets.copy(
                    accessToken = tokens.accessToken,
                    accessTokenExpiresAtEpochSeconds = now() + tokens.expiresInSeconds,
                    refreshToken = tokens.refreshToken ?: refreshToken,
                )
                vaultStore.save(FirefoxSyncVault(session = secrets, pendingLogin = vault.pendingLogin))
            }
            val keys = FirefoxSyncKeys(secrets.kSync, secrets.kid)
            val connection = try {
                when (val outcome = session.connect(config.tokenServerUrl, secrets.accessToken, keys)) {
                    is FirefoxSyncConnectOutcome.Connected -> outcome.connection
                    is FirefoxSyncConnectOutcome.StorageNotInitialized -> {
                        publish(stateFrom(settings, FirefoxSyncStatus.EngineMissing, "No Firefox client has synced this account yet"))
                        return false
                    }
                    is FirefoxSyncConnectOutcome.UnsupportedStorageVersion -> {
                        publish(stateFrom(settings, FirefoxSyncStatus.Incompatible, "Unsupported Sync storage version ${outcome.version}"))
                        return false
                    }
                }
            } finally {
                keys.destroy()
            }
            try {
                if (!force) {
                    val remote = transport.infoCollections(connection.credentials)[ZenSpacesCodec.COLLECTION]
                    if (remote != null && remote == cache.spacesLastModified) {
                        publish(stateFrom(settings, FirefoxSyncStatus.Ready, lastError = null))
                        return true
                    }
                }
                when (val outcome = session.fetchZenSpaces(connection)) {
                    is ZenSpacesFetchOutcome.Ready -> {
                        val updated = FirefoxSyncCache(
                            spacesLastModified = outcome.lastModified,
                            records = outcome.records,
                            skippedRecordIds = outcome.skippedRecordIds,
                            syncedAt = Instant.now(clock).toString(),
                        )
                        cache = updated
                        cacheStore.save(updated)
                        publish(stateFrom(settings, FirefoxSyncStatus.Ready, lastError = null))
                        true
                    }
                    is ZenSpacesFetchOutcome.EngineMissing -> {
                        publish(stateFrom(settings, FirefoxSyncStatus.EngineMissing, "Zen has not synced spaces on this account yet"))
                        false
                    }
                    is ZenSpacesFetchOutcome.UnsupportedEngineVersion -> {
                        publish(stateFrom(settings, FirefoxSyncStatus.Incompatible, "Zen spaces engine version ${outcome.version} is not supported"))
                        false
                    }
                }
            } finally {
                connection.close()
            }
        } catch (error: Exception) {
            publish(stateFrom(settings, statusFor(error), error.message ?: error.javaClass.simpleName))
            false
        } finally {
            secrets.clear()
        }
    }

    private fun initialState(): FirefoxSyncRepositoryState {
        val settings = settingsStore.load()
        val vault = vaultStore.load()
        return when {
            vault?.pendingLogin != null && settings == null -> FirefoxSyncRepositoryState.SIGNED_OUT.copy(status = FirefoxSyncStatus.SigningIn)
            settings == null || vault?.session == null -> FirefoxSyncRepositoryState.SIGNED_OUT
            else -> stateFrom(settings, FirefoxSyncStatus.Ready, lastError = null)
        }
    }

    private fun stateFrom(settings: FirefoxSyncSettings?, status: FirefoxSyncStatus, lastError: String?): FirefoxSyncRepositoryState {
        val snapshot = ZenSpacesCodec.assemble(cache.records)
        return FirefoxSyncRepositoryState(
            status = status,
            settings = settings,
            snapshot = snapshot,
            counts = FirefoxSyncRules.counts(snapshot, cache.skippedRecordIds.size),
            spacesLastModified = cache.spacesLastModified,
            lastSyncAt = cache.syncedAt,
            lastError = FirefoxSyncRules.boundedError(lastError),
            lastBridgeCommand = lastBridgeCommand,
        )
    }

    private fun statusFor(error: Exception): FirefoxSyncStatus = when {
        error is FirefoxSyncTransportException && (error.statusCode == 401 || error.statusCode == 400) -> FirefoxSyncStatus.AuthError
        error is FirefoxSyncTransportException && error.statusCode == null -> FirefoxSyncStatus.Offline
        error is GeneralSecurityException -> FirefoxSyncStatus.CryptoError
        error is IllegalArgumentException -> FirefoxSyncStatus.Incompatible
        else -> FirefoxSyncStatus.Offline
    }

    private fun now(): Long = Instant.now(clock).epochSecond

    private fun publish(value: FirefoxSyncRepositoryState) {
        state = value
        listeners.forEach { listener -> runCatching { listener(value) } }
    }

    private fun <T> submit(block: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(block, executor)
}
