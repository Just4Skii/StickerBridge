package com.example.tiktoktowhatsappstickers.data.storage

import android.content.Context
import android.util.Log
import com.example.tiktoktowhatsappstickers.core.engine.WebpProcessingEngine
import com.example.tiktoktowhatsappstickers.data.model.Sticker
import com.example.tiktoktowhatsappstickers.data.model.StickerPack
import java.io.File
import java.security.MessageDigest

/**
 * Single place that turns raw TikTok images into WhatsApp packs, with deduplication.
 *
 * Identity: every sticker is identified by the SHA-256 of its original bytes. A sticker that is already
 * in ANY pack is never stored twice, no matter how often you scan or import. Auto packs have stable ids
 * (tt_auto_static_1, tt_auto_anim_1, ...) so a new scan tops up the existing pack instead of creating
 * another one.
 */
class StickerLibrary(
    private val context: Context,
    private val storage: StickerStorageManager = StickerStorageManager.getInstance(context)
) {

    /** An unprocessed image plus TikTok's cache key for it (file name), when known. */
    class Raw(val key: String, val bytes: ByteArray)

    data class Outcome(
        val added: Int,
        val alreadyHad: Int,
        val unusable: Int,
        val touchedPacks: List<StickerPack>
    )

    data class ManualResult(
        val packs: List<StickerPack>,
        /** True when an identical pack already existed and nothing new was created. */
        val alreadyHad: Boolean,
        val unusable: Int
    )

    private class Prepared(val id: String, val key: String, val bytes: ByteArray, val animated: Boolean)

    companion object {
        private const val TAG = "StickerLibrary"
        const val MAX_PER_PACK = 30
        const val MIN_PER_PACK = 3
        const val PUBLISHER = "StickerBridge"
        private const val AUTO_ANIM = "tt_auto_anim_"
        private const val AUTO_STATIC = "tt_auto_static_"

        /** Stable sticker id: first 16 bytes of SHA-256 over the original image, hex-encoded. */
        fun idOf(bytes: ByteArray): String {
            val d = MessageDigest.getInstance("SHA-256").digest(bytes)
            return d.take(16).joinToString("") { "%02x".format(it) }
        }
    }

    // ------------------------------------------------------------------ auto packs (scanner)

    @Synchronized
    fun addToAutoPacks(raws: List<Raw>, onProgress: (String) -> Unit = {}): Outcome {
        val known = storage.knownStickerIds().toHashSet()
        var duplicates = 0
        var unusable = 0
        val fresh = LinkedHashMap<String, Prepared>()
        val newlyRejected = ArrayList<String>()

        raws.forEachIndexed { i, r ->
            val id = idOf(r.bytes)
            when {
                id in known || id in fresh -> duplicates++
                storage.isRejected(id) -> unusable++
                else -> {
                    onProgress("Converting sticker ${i + 1} of ${raws.size}…")
                    val n = WebpProcessingEngine.normalize(r.bytes)
                    if (n == null) {
                        unusable++
                        newlyRejected += id
                    } else {
                        fresh[id] = Prepared(id, r.key, n.bytes, n.animated)
                    }
                }
            }
        }
        storage.markRejected(newlyRejected)

        val touched = LinkedHashMap<String, StickerPack>()
        for (animated in listOf(true, false)) {
            val queue = fresh.values.filter { it.animated == animated }.toMutableList()
            if (queue.isEmpty()) continue
            for (pack in autoPacks(animated)) {
                if (queue.isEmpty()) break
                if (pack.realStickerCount >= MAX_PER_PACK) continue
                touched[pack.id] = appendTo(pack, queue)
            }
            while (queue.isNotEmpty()) {
                val pack = newAutoPack(animated)
                touched[pack.id] = appendTo(pack, queue)
            }
        }
        return Outcome(fresh.size, duplicates, unusable, touched.values.toList())
    }

    private fun autoPacks(animated: Boolean): List<StickerPack> {
        val prefix = if (animated) AUTO_ANIM else AUTO_STATIC
        return storage.getPacks()
            .filter { it.autoManaged && it.id.startsWith(prefix) }
            .sortedBy { it.id.removePrefix(prefix).toIntOrNull() ?: Int.MAX_VALUE }
    }

    private fun newAutoPack(animated: Boolean): StickerPack {
        val prefix = if (animated) AUTO_ANIM else AUTO_STATIC
        val next = (autoPacks(animated).maxOfOrNull { it.id.removePrefix(prefix).toIntOrNull() ?: 0 } ?: 0) + 1
        val base = if (animated) "TikTok Animated Stickers" else "TikTok Stickers"
        return StickerPack(
            id = "$prefix$next",
            name = if (next == 1) base else "$base #$next",
            publisher = PUBLISHER,
            trayImageFile = "tray_icon.png",
            animatedStickerPack = animated,
            autoManaged = true
        )
    }

    /** Moves as many queued stickers into [pack] as fit, rebuilds filler/tray/version, saves it. */
    private fun appendTo(pack: StickerPack, queue: MutableList<Prepared>): StickerPack {
        val dir = storage.getPackDir(pack.id)
        val real = pack.stickers.filter { !it.isPadding }.toMutableList()
        val take = queue.take(MAX_PER_PACK - real.size)
        queue.removeAll(take.toSet())
        for (p in take) {
            val name = "s_${p.id}.webp"
            File(dir, name).writeBytes(p.bytes)
            real += Sticker(
                imageFileName = name,
                emojis = listOf("✨"),
                sizeBytes = p.bytes.size.toLong(),
                isAnimated = p.animated,
                id = p.id,
                sourceKey = p.key
            )
        }
        return finalizePack(pack, dir, real)
    }

    private fun finalizePack(pack: StickerPack, dir: File, real: List<Sticker>): StickerPack {
        dir.listFiles { f -> f.name.startsWith("pad_") }?.forEach { it.delete() }
        val all = real.toMutableList()
        // WhatsApp refuses packs with fewer than 3 stickers. Filler is flagged and removed as soon as
        // real stickers arrive, so it never lingers as a "duplicate".
        var k = 0
        while (all.size < MIN_PER_PACK && real.isNotEmpty()) {
            val src = real[k % real.size]
            val name = "pad_$k.webp"
            File(dir, src.imageFileName).copyTo(File(dir, name), overwrite = true)
            all += src.copy(imageFileName = name, id = "${src.id}~pad$k", isPadding = true)
            k++
        }
        File(dir, pack.trayImageFile).takeIf { real.isNotEmpty() }?.let { tray ->
            WebpProcessingEngine.createTrayIcon(File(dir, real.first().imageFileName), tray)
        }
        val updated = pack.copy(
            stickers = all,
            imageDataVersion = ((pack.imageDataVersion.toIntOrNull() ?: 0) + 1).toString()
        )
        storage.savePack(updated)
        return updated
    }

    // ------------------------------------------------------------------ manual packs (link / gallery / share)

    /**
     * Builds a user pack. Duplicate images inside the batch are collapsed, and if an identical pack
     * already exists it is returned instead of making another copy.
     */
    @Synchronized
    fun createManualPack(name: String, raws: List<Raw>, publisher: String = PUBLISHER): ManualResult {
        var unusable = 0
        val prepared = LinkedHashMap<String, Prepared>()
        for (r in raws) {
            val id = idOf(r.bytes)
            if (id in prepared) continue
            val n = WebpProcessingEngine.normalize(r.bytes)
            if (n == null) {
                unusable++
                continue
            }
            prepared[id] = Prepared(id, r.key, n.bytes, n.animated)
        }
        if (prepared.isEmpty()) return ManualResult(emptyList(), false, unusable)

        val result = ArrayList<StickerPack>()
        var existed = true
        for (animated in listOf(false, true)) {
            val items = prepared.values.filter { it.animated == animated }.take(MAX_PER_PACK)
            if (items.isEmpty()) continue
            val setKey = idOf(items.map { it.id }.sorted().joinToString(",").toByteArray())
            val packId = "pack_${setKey.take(10)}"
            val existing = storage.getPack(packId)
            if (existing != null) {
                result += existing
                continue
            }
            existed = false
            val label = if (animated && prepared.values.any { !it.animated }) "$name (animated)" else name
            val base = StickerPack(
                id = packId,
                name = label,
                publisher = publisher,
                trayImageFile = "tray_icon.png",
                animatedStickerPack = animated,
                autoManaged = false
            )
            val dir = storage.getPackDir(packId)
            val stickers = items.map { p ->
                val file = "s_${p.id}.webp"
                File(dir, file).writeBytes(p.bytes)
                Sticker(file, listOf("✨"), p.bytes.size.toLong(), p.animated, p.id, p.key)
            }
            result += finalizePack(base, dir, stickers)
        }
        Log.i(TAG, "Manual pack '$name': ${result.size} pack(s), existed=$existed")
        return ManualResult(result, existed, unusable)
    }
}
