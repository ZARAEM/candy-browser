package dev.sk2andy.materialbrowser.data.sync.firefox

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import dev.sk2andy.materialbrowser.data.sync.AndroidSyncSecretProtector
import dev.sk2andy.materialbrowser.data.sync.readBytesLimited
import dev.sk2andy.materialbrowser.data.writeSafely
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncCache
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncSettings
import dev.sk2andy.materialbrowser.sync.firefox.FirefoxSyncVault
import java.io.File

interface FirefoxSyncSettingsStore {
    fun load(): FirefoxSyncSettings?
    fun save(value: FirefoxSyncSettings): Boolean
    fun clear(): Boolean
}

interface FirefoxSyncVaultStore {
    fun load(): FirefoxSyncVault?
    fun save(value: FirefoxSyncVault): Boolean
    fun clear()
}

interface FirefoxSyncCacheStore {
    fun load(): FirefoxSyncCache
    fun save(value: FirefoxSyncCache): Boolean
    fun clear()
}

class AndroidFirefoxSyncSettingsStore internal constructor(
    private val preferences: SharedPreferences,
) : FirefoxSyncSettingsStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    override fun load(): FirefoxSyncSettings? = preferences.getString(KEY_SETTINGS, null)?.let { raw ->
        runCatching { FirefoxSyncStateCodec.decodeSettings(raw) }.getOrNull()
    }

    override fun save(value: FirefoxSyncSettings): Boolean = preferences.edit()
        .putString(KEY_SETTINGS, FirefoxSyncStateCodec.encodeSettings(value))
        .commit()

    override fun clear(): Boolean = preferences.edit().remove(KEY_SETTINGS).commit()

    private companion object {
        const val PREFERENCES_NAME = "candy_firefox_sync_settings"
        const val KEY_SETTINGS = "account"
    }
}

class AndroidFirefoxSyncVaultStore(
    context: Context,
    private val protector: AndroidSyncSecretProtector = AndroidSyncSecretProtector(KEYSTORE_ALIAS),
) : FirefoxSyncVaultStore {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "candy_firefox_sync_vault_v1"))

    @Synchronized
    override fun load(): FirefoxSyncVault? = runCatching {
        val encrypted = file.openRead().use { it.readBytesLimited(MAX_VAULT_BYTES) }
        val plaintext = protector.decrypt(encrypted, VAULT_AAD)
        try {
            FirefoxSyncStateCodec.decodeVault(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }.getOrNull()

    @Synchronized
    override fun save(value: FirefoxSyncVault): Boolean {
        val plaintext = FirefoxSyncStateCodec.encodeVault(value)
        return try {
            require(plaintext.size <= MAX_VAULT_BYTES)
            val encrypted = protector.encrypt(plaintext, VAULT_AAD)
            file.writeSafely { it.write(encrypted) }
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    override fun clear() = file.delete()

    companion object {
        const val KEYSTORE_ALIAS = "candy_firefox_sync_local_v1"
        private val VAULT_AAD = "candy-firefox-sync/android-vault/v1".toByteArray()
        private const val MAX_VAULT_BYTES = 32 * 1_024
    }
}

class AndroidFirefoxSyncCacheStore(
    context: Context,
    private val protector: AndroidSyncSecretProtector = AndroidSyncSecretProtector(AndroidFirefoxSyncVaultStore.KEYSTORE_ALIAS),
) : FirefoxSyncCacheStore {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "candy_firefox_sync_cache_v1"))

    @Synchronized
    override fun load(): FirefoxSyncCache = runCatching {
        val encrypted = file.openRead().use { it.readBytesLimited(MAX_ENCRYPTED_CACHE_BYTES) }
        val plaintext = protector.decrypt(encrypted, CACHE_AAD)
        try {
            FirefoxSyncStateCodec.decodeCache(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }.getOrDefault(FirefoxSyncCache.EMPTY)

    @Synchronized
    override fun save(value: FirefoxSyncCache): Boolean {
        val plaintext = FirefoxSyncStateCodec.encodeCache(value)
        return try {
            require(plaintext.size <= MAX_PLAINTEXT_CACHE_BYTES)
            val encrypted = protector.encrypt(plaintext, CACHE_AAD)
            file.writeSafely { it.write(encrypted) }
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    override fun clear() = file.delete()

    private companion object {
        val CACHE_AAD = "candy-firefox-sync/android-cache/v1".toByteArray()
        const val MAX_PLAINTEXT_CACHE_BYTES = 8 * 1_024 * 1_024
        const val MAX_ENCRYPTED_CACHE_BYTES = 9 * 1_024 * 1_024
    }
}
