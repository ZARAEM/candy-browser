package dev.sk2andy.firefoxsync

/** Storage credentials issued by the Firefox Sync token server for one node. */
data class SyncStorageCredentials(
    val hawkId: String,
    val hawkKey: String,
    val uid: Long,
    val apiEndpoint: String,
    val durationSeconds: Long,
    val hashedFxaUid: String?,
)

/** One basic storage object as stored on a Sync 1.5 node. */
data class SyncBso(
    val id: String,
    val payload: String,
    val modified: Double? = null,
    val sortIndex: Int? = null,
    val ttlSeconds: Int? = null,
)

data class SyncCollectionPage(
    val records: List<SyncBso>,
    val lastModified: Double?,
    val nextOffset: String?,
)

data class SyncPostResult(
    val modified: Double,
    val success: List<String>,
    val failed: Map<String, String>,
)

data class SyncEngineInfo(
    val version: Int,
    val syncId: String,
)

/** Cleartext `meta/global` record describing the account's storage layout. */
data class SyncMetaGlobal(
    val syncId: String,
    val storageVersion: Int,
    val engines: Map<String, SyncEngineInfo>,
    val declined: List<String>,
) {
    companion object {
        const val SUPPORTED_STORAGE_VERSION = 5
    }
}

/** Decrypted `crypto/keys` record: the default bundle plus per-collection overrides. */
class SyncCollectionKeys(
    val default: SyncKeyBundle,
    val collections: Map<String, SyncKeyBundle>,
) {
    fun bundleFor(collection: String): SyncKeyBundle = collections[collection] ?: default

    fun destroy() {
        default.destroy()
        collections.values.forEach(SyncKeyBundle::destroy)
    }
}
