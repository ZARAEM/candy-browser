package dev.sk2andy.materialbrowser.data

import android.content.Context
import android.util.AtomicFile
import dev.sk2andy.materialbrowser.browser.BrowserSpaceSnapshot
import java.io.File
import java.nio.charset.StandardCharsets

/** Versioned AtomicFile store for spaces and the active space per profile. */
class BrowserSpaceStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))

    @Synchronized
    fun load(): BrowserSpaceSnapshot {
        if (!file.baseFile.isFile || file.baseFile.length() > BrowserSpaceCodec.MAX_JSON_BYTES) return BrowserSpaceSnapshot.EMPTY
        val bytes = runCatching {
            file.openRead().use { input -> input.readNBytes(BrowserSpaceCodec.MAX_JSON_BYTES + 1) }
        }.getOrNull() ?: return BrowserSpaceSnapshot.EMPTY
        if (bytes.size > BrowserSpaceCodec.MAX_JSON_BYTES) return BrowserSpaceSnapshot.EMPTY
        return BrowserSpaceCodec.decode(bytes.toString(StandardCharsets.UTF_8))
    }

    @Synchronized
    fun save(snapshot: BrowserSpaceSnapshot): Boolean {
        val bytes = BrowserSpaceCodec.encode(snapshot).toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > BrowserSpaceCodec.MAX_JSON_BYTES) return false
        return file.writeSafely { it.write(bytes) }
    }

    @Synchronized
    fun clear() = file.delete()

    private companion object {
        const val FILE_NAME = "browser_spaces_v1.json"
    }
}
