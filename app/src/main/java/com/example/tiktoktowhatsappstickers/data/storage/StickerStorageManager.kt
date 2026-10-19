package com.example.tiktoktowhatsappstickers.data.storage

import android.content.Context
import android.util.Log
import com.example.tiktoktowhatsappstickers.data.model.StickerPack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists sticker packs under filesDir/sticker_packs and publishes them as a Flow.
 *
 * The library starts EMPTY: nothing is pre-seeded. Every pack in here came from the user's own TikTok
 * data (scan, link, shared or picked media).
 */
class StickerStorageManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "StickerStorageManager"
        private const val PACKS_DIR = "sticker_packs"
        private const val CONTENTS_FILE = "contents.json"
        private const val REJECTED_FILE = "rejected_ids.txt"
        private const val MIGRATION_FILE = ".migrated_v2"

        /** Demo packs seeded by v1.0 ("fed in" mock data). Removed on upgrade. */
        private val LEGACY_SAMPLE_IDS = setOf("tiktok_viral_memes", "tiktok_reactions")

        /** v1.0 scans minted a brand-new random pack on every run, which is what caused duplicates. */
        private val LEGACY_SCAN_PREFIXES = listOf("tt_anim_", "tt_static_")
        private val LEGACY_SCAN_NAME = Regex("^TikTok (Animated |Favorited )?Stickers( #\\d+)?$")
        private val LEGACY_SCAN_ID = Regex("^tt_[0-9a-f]{8}$")

        @Volatile
        private var instance: StickerStorageManager? = null

        fun getInstance(context: Context): StickerStorageManager {
            return instance ?: synchronized(this) {
                instance ?: StickerStorageManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val baseDir: File = File(context.filesDir, PACKS_DIR).apply {
        if (!exists()) mkdirs()
    }

    private val contentsFile: File = File(baseDir, CONTENTS_FILE)
    private val rejectedFile: File = File(baseDir, REJECTED_FILE)

    private val _packsFlow = MutableStateFlow<List<StickerPack>>(emptyList())
    val packsFlow: StateFlow<List<StickerPack>> = _packsFlow.asStateFlow()

    private val rejected: MutableSet<String> = HashSet()

    init {
        loadPacks()
        loadRejected()
        migrateFromV1IfNeeded()
    }

    fun getPacks(): List<StickerPack> = _packsFlow.value

    fun getPack(packId: String): StickerPack? = _packsFlow.value.find { it.id == packId }

    fun getPackDir(packId: String): File = File(baseDir, packId).apply { mkdirs() }

    fun getStickerFile(packId: String, fileName: String): File = File(File(baseDir, packId), fileName)

    /** Every real sticker id currently stored, across all packs. */
    fun knownStickerIds(): Set<String> =
        _packsFlow.value.flatMap { p -> p.stickers.filter { !it.isPadding && it.id.isNotEmpty() }.map { it.id } }.toHashSet()

    /** Content ids that were examined and are not usable stickers; skipped quickly on later scans. */
    @Synchronized
    fun isRejected(id: String): Boolean = id in rejected

    @Synchronized
    fun markRejected(ids: Collection<String>) {
        if (rejected.addAll(ids)) {
            try {
                rejectedFile.writeText(rejected.joinToString("\n"))
            } catch (e: Exception) {
                Log.w(TAG, "Could not persist rejected ids", e)
            }
        }
    }

    @Synchronized
    fun savePack(pack: StickerPack) {
        val current = _packsFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == pack.id }
        if (index != -1) {
            current[index] = pack
        } else {
            current.add(0, pack)
        }
        persistPacks(current)
    }

    @Synchronized
    fun deletePack(packId: String) {
        val current = _packsFlow.value.toMutableList()
        current.removeAll { it.id == packId }
        val packDir = File(baseDir, packId)
        if (packDir.exists()) {
            packDir.deleteRecursively()
        }
        persistPacks(current)
    }

    @Synchronized
    fun markPackAddedToWhatsApp(packId: String, added: Boolean) {
        val current = _packsFlow.value.map { pack ->
            if (pack.id == packId) pack.copy(isAddedToWhatsApp = added) else pack
        }
        persistPacks(current)
    }

    private fun loadPacks() {
        if (!contentsFile.exists()) {
            _packsFlow.value = emptyList()
            return
        }
        try {
            _packsFlow.value = json.decodeFromString<List<StickerPack>>(contentsFile.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Error loading packs from $contentsFile", e)
            _packsFlow.value = emptyList()
        }
    }

    private fun loadRejected() {
        try {
            if (rejectedFile.exists()) rejected.addAll(rejectedFile.readLines().filter { it.isNotBlank() })
        } catch (e: Exception) {
            Log.w(TAG, "Could not read rejected ids", e)
        }
    }

    private fun persistPacks(packs: List<StickerPack>) {
        try {
            contentsFile.writeText(json.encodeToString(packs))
            _packsFlow.value = packs
        } catch (e: Exception) {
            Log.e(TAG, "Error saving packs to $contentsFile", e)
        }
    }

    /** One-time cleanup of everything v1.0 invented: demo packs and the random-id scan packs. */
    @Synchronized
    private fun migrateFromV1IfNeeded() {
        val marker = File(baseDir, MIGRATION_FILE)
        if (marker.exists()) return
        val doomed = _packsFlow.value.filter { p ->
            p.id in LEGACY_SAMPLE_IDS ||
                LEGACY_SCAN_PREFIXES.any { p.id.startsWith(it) } ||
                (LEGACY_SCAN_ID.matches(p.id) && LEGACY_SCAN_NAME.matches(p.name))
        }
        if (doomed.isNotEmpty()) {
            doomed.forEach { File(baseDir, it.id).deleteRecursively() }
            persistPacks(_packsFlow.value - doomed.toSet())
            Log.i(TAG, "Migration removed ${doomed.size} legacy packs")
        }
        runCatching { marker.writeText("ok") }
    }
}
