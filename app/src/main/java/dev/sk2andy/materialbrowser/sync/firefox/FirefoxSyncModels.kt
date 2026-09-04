package dev.sk2andy.materialbrowser.sync.firefox

import dev.sk2andy.firefoxsync.FirefoxAccountLoginAttempt
import dev.sk2andy.firefoxsync.ZenSpacesRecord
import dev.sk2andy.firefoxsync.ZenSpacesSnapshot

enum class FirefoxSyncStatus {
    SignedOut,
    SigningIn,
    Ready,
    Syncing,
    Offline,
    AuthError,
    EngineMissing,
    Incompatible,
    CryptoError,
}

/** Non-secret account facts shown in settings. */
data class FirefoxSyncSettings(
    val accountUid: String,
    val accountEmail: String?,
    val signedInAt: String,
)

/** Secrets for one signed-in Mozilla account session. Keystore-protected at rest. */
data class FirefoxSyncSessionSecrets(
    val accessToken: String,
    val accessTokenExpiresAtEpochSeconds: Long,
    val refreshToken: String?,
    val kSync: ByteArray,
    val kid: String,
) {
    fun copyForUse(): FirefoxSyncSessionSecrets = copy(kSync = kSync.copyOf())

    fun clear() = kSync.fill(0)

    override fun equals(other: Any?): Boolean = other is FirefoxSyncSessionSecrets &&
        accessToken == other.accessToken &&
        accessTokenExpiresAtEpochSeconds == other.accessTokenExpiresAtEpochSeconds &&
        refreshToken == other.refreshToken &&
        kSync.contentEquals(other.kSync) &&
        kid == other.kid

    override fun hashCode(): Int = listOf(accessToken, accessTokenExpiresAtEpochSeconds, refreshToken, kSync.contentHashCode(), kid).hashCode()

    override fun toString(): String = "FirefoxSyncSessionSecrets(kid=$kid)"
}

/** Vault contents: either a login in progress, a signed-in session, or nothing. */
data class FirefoxSyncVault(
    val session: FirefoxSyncSessionSecrets? = null,
    val pendingLogin: FirefoxAccountLoginAttempt? = null,
) {
    fun clear() {
        session?.clear()
        pendingLogin?.destroy()
    }
}

/** Last decoded `spaces` collection, kept so the UI works offline and unchanged syncs are skipped. */
data class FirefoxSyncCache(
    val spacesLastModified: Double?,
    val records: List<ZenSpacesRecord>,
    val skippedRecordIds: List<String>,
    val syncedAt: String?,
) {
    companion object {
        val EMPTY = FirefoxSyncCache(spacesLastModified = null, records = emptyList(), skippedRecordIds = emptyList(), syncedAt = null)
    }
}

data class FirefoxSyncSnapshotCounts(
    val containers: Int,
    val spaces: Int,
    val pinnedTabs: Int,
    val essentialTabs: Int,
    val folders: Int,
    val splits: Int,
    val skipped: Int,
)

data class FirefoxSyncRepositoryState(
    val status: FirefoxSyncStatus,
    val settings: FirefoxSyncSettings?,
    val snapshot: ZenSpacesSnapshot,
    val counts: FirefoxSyncSnapshotCounts,
    val spacesLastModified: Double?,
    val lastSyncAt: String?,
    val lastError: String?,
    val lastBridgeCommand: String? = null,
) {
    val isSignedIn: Boolean get() = settings != null && status != FirefoxSyncStatus.SignedOut && status != FirefoxSyncStatus.SigningIn

    companion object {
        val SIGNED_OUT = FirefoxSyncRepositoryState(
            status = FirefoxSyncStatus.SignedOut,
            settings = null,
            snapshot = ZenSpacesSnapshot.EMPTY,
            counts = FirefoxSyncSnapshotCounts(0, 0, 0, 0, 0, 0, 0),
            spacesLastModified = null,
            lastSyncAt = null,
            lastError = null,
        )
    }
}

sealed interface FirefoxSyncLoginOutcome {
    data object SignedIn : FirefoxSyncLoginOutcome
    data object NoLoginInProgress : FirefoxSyncLoginOutcome
    data object MissingSyncKeys : FirefoxSyncLoginOutcome
    data object AuthenticationFailed : FirefoxSyncLoginOutcome
    data object Failed : FirefoxSyncLoginOutcome
}

/** Pure projections shared by the repository and the settings UI. */
object FirefoxSyncRules {
    const val MAX_ERROR_LENGTH = 512
    const val ACCESS_TOKEN_REFRESH_MARGIN_SECONDS = 60L

    fun counts(snapshot: ZenSpacesSnapshot, skipped: Int): FirefoxSyncSnapshotCounts = FirefoxSyncSnapshotCounts(
        containers = snapshot.containers.size,
        spaces = snapshot.spaces.size,
        pinnedTabs = snapshot.tabs.values.count { !it.essential },
        essentialTabs = snapshot.tabs.values.count { it.essential },
        folders = snapshot.folders.size,
        splits = snapshot.splits.size,
        skipped = skipped,
    )

    fun accessTokenNeedsRefresh(secrets: FirefoxSyncSessionSecrets, nowEpochSeconds: Long): Boolean =
        secrets.accessTokenExpiresAtEpochSeconds - nowEpochSeconds <= ACCESS_TOKEN_REFRESH_MARGIN_SECONDS

    fun boundedError(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_ERROR_LENGTH)
}
