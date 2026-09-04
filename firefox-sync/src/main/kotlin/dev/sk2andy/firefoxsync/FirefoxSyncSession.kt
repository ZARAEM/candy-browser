package dev.sk2andy.firefoxsync

/** Everything needed to read and write one account's storage node until the token expires. */
class FirefoxSyncConnection internal constructor(
    val credentials: SyncStorageCredentials,
    val metaGlobal: SyncMetaGlobal,
    internal val collectionKeys: SyncCollectionKeys,
    val expiresAtEpochSeconds: Long,
) {
    fun zenSpacesEngine(): SyncEngineInfo? = metaGlobal.engines[ZenSpacesCodec.COLLECTION]

    fun close() = collectionKeys.destroy()
}

sealed interface ZenSpacesFetchOutcome {
    /** Records decoded from the collection plus ids Candy must leave untouched (unknown kinds). */
    data class Ready(
        val records: List<ZenSpacesRecord>,
        val snapshot: ZenSpacesSnapshot,
        val lastModified: Double?,
        val skippedRecordIds: List<String>,
    ) : ZenSpacesFetchOutcome

    /** No Zen client has synced spaces on this account yet. */
    data object EngineMissing : ZenSpacesFetchOutcome

    /** Zen changed its spaces schema; Candy must not read or write until it is updated. */
    data class UnsupportedEngineVersion(val version: Int) : ZenSpacesFetchOutcome
}

sealed interface FirefoxSyncConnectOutcome {
    data class Connected(val connection: FirefoxSyncConnection) : FirefoxSyncConnectOutcome
    data class UnsupportedStorageVersion(val version: Int) : FirefoxSyncConnectOutcome

    /** `meta/global` or `crypto/keys` is absent: a Firefox client must initialize the account first. */
    data object StorageNotInitialized : FirefoxSyncConnectOutcome
}

/**
 * Orchestrates one Firefox Sync login against Zen's `spaces` collection. The session stays pure
 * apart from the injected [transport]; callers own persistence and the UI-side applier.
 */
class FirefoxSyncSession(
    private val transport: FirefoxSyncTransport,
    private val recordCrypto: SyncRecordCrypto = SyncRecordCrypto(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    fun connect(tokenServerUrl: String, accessToken: String, keys: FirefoxSyncKeys): FirefoxSyncConnectOutcome {
        val credentials = transport.fetchStorageCredentials(tokenServerUrl, accessToken, keys.kid)
        val metaRecord = transport.getRecord(credentials, "meta", "global")
            ?: return FirefoxSyncConnectOutcome.StorageNotInitialized
        val metaGlobal = SyncStorageCodec.decodeMetaGlobal(metaRecord.payload)
        if (metaGlobal.storageVersion != SyncMetaGlobal.SUPPORTED_STORAGE_VERSION) {
            return FirefoxSyncConnectOutcome.UnsupportedStorageVersion(metaGlobal.storageVersion)
        }
        val keysRecord = transport.getRecord(credentials, "crypto", "keys")
            ?: return FirefoxSyncConnectOutcome.StorageNotInitialized
        val syncKeyBundle = SyncKeyBundle.fromKSync(keys.kSync)
        val collectionKeys = try {
            SyncStorageCodec.decodeCollectionKeys(
                recordCrypto.decrypt(syncKeyBundle, SyncEncryptedPayload.decode(keysRecord.payload)),
            )
        } finally {
            syncKeyBundle.destroy()
        }
        return FirefoxSyncConnectOutcome.Connected(
            FirefoxSyncConnection(
                credentials = credentials,
                metaGlobal = metaGlobal,
                collectionKeys = collectionKeys,
                expiresAtEpochSeconds = clock() + credentials.durationSeconds,
            ),
        )
    }

    fun fetchZenSpaces(connection: FirefoxSyncConnection, newerThan: Double? = null): ZenSpacesFetchOutcome {
        val engine = connection.zenSpacesEngine() ?: return ZenSpacesFetchOutcome.EngineMissing
        if (engine.version != ZenSpacesCodec.ENGINE_VERSION) {
            return ZenSpacesFetchOutcome.UnsupportedEngineVersion(engine.version)
        }
        val bundle = connection.collectionKeys.bundleFor(ZenSpacesCodec.COLLECTION)
        val records = mutableListOf<ZenSpacesRecord>()
        val skipped = mutableListOf<String>()
        var lastModified: Double? = null
        var offset: String? = null
        var pages = 0
        do {
            val page = transport.getCollection(
                credentials = connection.credentials,
                collection = ZenSpacesCodec.COLLECTION,
                newerThan = newerThan,
                offset = offset,
            )
            page.records.forEach { bso ->
                val cleartext = runCatching {
                    recordCrypto.decrypt(bundle, SyncEncryptedPayload.decode(bso.payload))
                }.getOrNull()
                val record = cleartext?.let(ZenSpacesCodec::decode)
                if (record == null) skipped += bso.id else records += record
            }
            lastModified = page.lastModified ?: lastModified
            offset = page.nextOffset
            pages++
        } while (offset != null && pages < MAX_PAGES)
        require(offset == null) { "Spaces collection exceeds the supported size" }
        return ZenSpacesFetchOutcome.Ready(
            records = records,
            snapshot = ZenSpacesCodec.assemble(records),
            lastModified = lastModified,
            skippedRecordIds = skipped,
        )
    }

    /**
     * Encrypts and uploads records in server-sized batches. The caller passes the collection's
     * last-modified timestamp so a concurrent Zen write fails with 412 instead of being clobbered.
     */
    fun uploadZenSpaces(
        connection: FirefoxSyncConnection,
        records: List<ZenSpacesRecord>,
        ifUnmodifiedSince: Double?,
    ): SyncPostResult {
        val engine = connection.zenSpacesEngine()
        require(engine != null && engine.version == ZenSpacesCodec.ENGINE_VERSION) { "Zen spaces engine is unavailable" }
        require(records.isNotEmpty()) { "Nothing to upload" }
        val bundle = connection.collectionKeys.bundleFor(ZenSpacesCodec.COLLECTION)
        val bsos = records.map { record ->
            SyncBso(id = record.id, payload = recordCrypto.encrypt(bundle, ZenSpacesCodec.encode(record)).encode())
        }
        var modified = ifUnmodifiedSince
        val success = mutableListOf<String>()
        val failed = linkedMapOf<String, String>()
        bsos.chunked(FirefoxSyncTransport.MAX_POST_RECORDS).forEach { batch ->
            val result = transport.postRecords(connection.credentials, ZenSpacesCodec.COLLECTION, batch, modified)
            success += result.success
            failed += result.failed
            modified = result.modified
        }
        return SyncPostResult(modified = requireNotNull(modified), success = success, failed = failed)
    }

    companion object {
        const val MAX_PAGES = 200
    }
}
