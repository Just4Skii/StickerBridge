package com.example.tiktoktowhatsappstickers.core.scanner

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.tiktoktowhatsappstickers.core.engine.WebpProcessingEngine
import com.example.tiktoktowhatsappstickers.data.model.Sticker
import com.example.tiktoktowhatsappstickers.data.model.StickerPack
import com.example.tiktoktowhatsappstickers.data.storage.StickerStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

class TikTokCacheScanner(private val context: Context) {

    companion object {
        private const val TAG = "TikTokCacheScanner"

        const val TIKTOK_PACKAGE = "com.zhiliaoapp.musically"
        const val TIKTOK_CACHE_SUBPATH = "Android/data/com.zhiliaoapp.musically/cache/picture/fresco_custom_cache"
        const val PACK_MAX_STICKERS = 24
        const val PACK_MIN_STICKERS = 3
    }

    private val storageManager = StickerStorageManager.getInstance(context)

    data class ScanResult(
        val totalStickersFound: Int,
        val packsCreated: List<StickerPack>,
        val errorMessage: String? = null
    )

    /**
     * Automatic 1-Click Scanner:
     * Scans for genuine TikTok favorited stickers and sticker cache assets.
     * Separates animated (GIF/WebP) stickers and static stickers into distinct WhatsApp-compliant packs.
     * Strictly filters out screenshots, camera photos, and non-stickers.
     */
    suspend fun scanDeviceMediaAutomatically(onProgress: (String) -> Unit): ScanResult = withContext(Dispatchers.IO) {
        onProgress("Scanning for genuine TikTok favorited stickers...")

        val allDiscoveredFiles = mutableListOf<File>()

        val stickerDirs = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "TikTokStickers"),
            File(Environment.getExternalStorageDirectory(), "Pictures/TikTokStickers"),
            File(Environment.getExternalStorageDirectory(), "TikTokStickers"),
            File(context.getExternalFilesDir(null), "TikTokStickers"),
            File(Environment.getExternalStorageDirectory(), "$TIKTOK_CACHE_SUBPATH/stable_sticker"),
            File(Environment.getExternalStorageDirectory(), "$TIKTOK_CACHE_SUBPATH/store_sticker"),
            File(Environment.getExternalStorageDirectory(), "$TIKTOK_CACHE_SUBPATH/static_sticker")
        )

        for (dir in stickerDirs) {
            try {
                if (dir.exists()) {
                    dir.walkTopDown().filter { it.isFile }.forEach { file ->
                        if (isRealStickerFile(file)) {
                            allDiscoveredFiles.add(file)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Sticker dir scan note for ${dir.path}: ${e.message}")
            }
        }

        // Deduplicate files by size & name
        val uniqueFiles = allDiscoveredFiles.distinctBy { "${it.length()}_${it.name}" }

        if (uniqueFiles.isEmpty()) {
            return@withContext ScanResult(
                totalStickersFound = 0,
                packsCreated = emptyList(),
                errorMessage = "No TikTok favorited stickers found. Make sure you have favorited stickers in TikTok!"
            )
        }

        // Partition into animated (GIF/WebP) and static stickers (WhatsApp requirement: never mix in 1 pack)
        val animFiles = mutableListOf<File>()
        val staticFiles = mutableListOf<File>()

        for (file in uniqueFiles) {
            if (WebpProcessingEngine.isAnimatedWebp(file)) {
                animFiles.add(file)
            } else {
                staticFiles.add(file)
            }
        }

        val allCreatedPacks = mutableListOf<StickerPack>()

        // 1. Process animated stickers (preserves moving animation, pads to 512x512)
        if (animFiles.isNotEmpty()) {
            onProgress("Processing ${animFiles.size} animated stickers (keeping animations)...")
            val animPacks = convertAnimatedFilesIntoPacks(
                files = animFiles,
                onProgress = onProgress,
                packTitle = "TikTok Animated Stickers"
            )
            allCreatedPacks.addAll(animPacks)
        }

        // 2. Process static stickers
        if (staticFiles.isNotEmpty()) {
            onProgress("Processing ${staticFiles.size} static stickers...")
            val staticPacks = convertStaticFilesIntoPacks(
                files = staticFiles,
                onProgress = onProgress,
                packTitle = "TikTok Favorited Stickers"
            )
            allCreatedPacks.addAll(staticPacks)
        }

        ScanResult(uniqueFiles.size, allCreatedPacks)
    }

    /**
     * Checks if the direct cache directory is accessible.
     */
    fun isDirectCacheAccessible(): Boolean {
        return try {
            val cacheDir = File(Environment.getExternalStorageDirectory(), TIKTOK_CACHE_SUBPATH)
            cacheDir.exists() && (cacheDir.listFiles()?.isNotEmpty() == true)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Scans the directly accessible cache directory.
     */
    suspend fun scanDirectCache(onProgress: (String) -> Unit): ScanResult = scanDeviceMediaAutomatically(onProgress)

    /**
     * Scans a user-selected SAF DocumentFile directory (e.g. chosen via ACTION_OPEN_DOCUMENT_TREE).
     */
    suspend fun scanDocumentTree(treeUri: Uri, onProgress: (String) -> Unit): ScanResult = withContext(Dispatchers.IO) {
        try {
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext ScanResult(0, emptyList(), "Could not access selected directory")

            val discoveredDocUris = mutableListOf<Uri>()
            onProgress("Scanning selected directory for TikTok stickers...")
            traverseDocumentFile(rootDoc, discoveredDocUris)

            if (discoveredDocUris.isEmpty()) {
                return@withContext ScanResult(0, emptyList(), "No valid sticker files found in the selected folder.")
            }

            onProgress("Found ${discoveredDocUris.size} stickers. Converting to WhatsApp packs...")
            val createdPacks = convertUrisIntoPacks(discoveredDocUris, onProgress)
            ScanResult(discoveredDocUris.size, createdPacks)
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning document tree", e)
            ScanResult(0, emptyList(), "Error scanning directory: ${e.message}")
        }
    }

    /**
     * Imports a list of individual document Uris (e.g. chosen via OpenMultipleDocuments).
     */
    suspend fun scanSelectedUris(uris: List<Uri>, onProgress: (String) -> Unit): ScanResult = withContext(Dispatchers.IO) {
        try {
            if (uris.isEmpty()) {
                return@withContext ScanResult(0, emptyList(), "No files selected.")
            }

            val validUris = uris.filter { uri ->
                try {
                    context.contentResolver.openInputStream(uri)?.use { isWebpOrImageStream(it) } == true
                } catch (e: Exception) {
                    false
                }
            }

            if (validUris.isEmpty()) {
                return@withContext ScanResult(0, emptyList(), "None of the selected files were valid sticker images.")
            }

            onProgress("Processing ${validUris.size} selected TikTok stickers...")
            val createdPacks = convertUrisIntoPacks(validUris, onProgress)
            ScanResult(validUris.size, createdPacks)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing selected URIs", e)
            ScanResult(0, emptyList(), "Processing failed: ${e.message}")
        }
    }

    private fun traverseDocumentFile(dir: DocumentFile, results: MutableList<Uri>) {
        val files = dir.listFiles()
        for (file in files) {
            if (file.isDirectory) {
                traverseDocumentFile(file, results)
            } else if (file.isFile && file.length() in 3500..3500000) {
                val name = file.name ?: ""
                if (name.contains("screenshot", ignoreCase = true)) continue

                try {
                    context.contentResolver.openInputStream(file.uri)?.use { stream ->
                        if (isWebpOrImageStream(stream)) {
                            results.add(file.uri)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not inspect ${file.name}", e)
                }
            }
        }
    }

    /**
     * Strictly verifies that a file is a sticker and NOT a phone screenshot or camera photo.
     */
    private fun isRealStickerFile(file: File): Boolean {
        val path = file.absolutePath
        val name = file.name

        // Reject screenshots, screen recordings, camera shots
        if (path.contains("screenshot", ignoreCase = true) ||
            path.contains("screenrecorder", ignoreCase = true) ||
            path.contains("DCIM/Camera", ignoreCase = true) ||
            name.contains("screenshot", ignoreCase = true)
        ) {
            return false
        }

        // File size must be between 3.5 KB and 3.5 MB
        val length = file.length()
        if (length < 3500 || length > 3500000) {
            return false
        }

        // Must have valid WebP, PNG, GIF, or JPEG header
        if (!isWebpOrImageFile(file)) {
            return false
        }

        // If it's an animated WebP, accept directly (animations are never phone screenshots)
        if (WebpProcessingEngine.isAnimatedWebp(file)) {
            return true
        }

        // Check image bounds to reject phone screenshots (e.g. 1080x2400)
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            val w = options.outWidth
            val h = options.outHeight

            if (w <= 0 || h <= 0) return false

            // Reject high-resolution camera photos
            if (w > 1200 || h > 1200) return false

            // Reject tall phone screenshots (aspect ratio != square)
            val ratio = w.toFloat() / h.toFloat()
            if (ratio < 0.62f || ratio > 1.62f) return false

            // Reject tall aspect ratios typical of mobile phone screens
            if (w >= 700 && h >= 1400) return false

            true
        } catch (e: Exception) {
            false
        }
    }

    private fun isWebpOrImageFile(file: File): Boolean {
        return try {
            file.inputStream().use { isWebpOrImageStream(it) }
        } catch (e: Exception) {
            false
        }
    }

    private fun isWebpOrImageStream(stream: InputStream): Boolean {
        val buf = ByteArray(16)
        val read = stream.read(buf)
        if (read < 12) return false

        // Check RIFF .... WEBP
        val isRiff = buf[0] == 'R'.code.toByte() && buf[1] == 'I'.code.toByte() &&
                buf[2] == 'F'.code.toByte() && buf[3] == 'F'.code.toByte()
        val isWebp = buf[8] == 'W'.code.toByte() && buf[9] == 'E'.code.toByte() &&
                buf[10] == 'B'.code.toByte() && buf[11] == 'P'.code.toByte()

        // Check PNG (\x89PNG)
        val isPng = buf[0] == 0x89.toByte() && buf[1] == 'P'.code.toByte() &&
                buf[2] == 'N'.code.toByte() && buf[3] == 'G'.code.toByte()

        // Check GIF (GIF87a / GIF89a)
        val isGif = buf[0] == 'G'.code.toByte() && buf[1] == 'I'.code.toByte() && buf[2] == 'F'.code.toByte()

        // Check JPEG (\xFF\xD8\xFF)
        val isJpeg = buf[0] == 0xFF.toByte() && buf[1] == 0xD8.toByte() && buf[2] == 0xFF.toByte()

        return (isRiff && isWebp) || isPng || isGif || isJpeg
    }

    /**
     * Converts ANIMATED stickers into WhatsApp animated sticker packs (animated_sticker_pack = true).
     * Preserves multi-frame animation, timing, and transparency.
     */
    private fun convertAnimatedFilesIntoPacks(
        files: List<File>,
        onProgress: (String) -> Unit,
        packTitle: String = "TikTok Animated Stickers"
    ): List<StickerPack> {
        val packs = mutableListOf<StickerPack>()
        val chunks = files.chunked(PACK_MAX_STICKERS)

        chunks.forEachIndexed { packIndex, chunkFiles ->
            val packNumber = packIndex + 1
            val displayName = if (chunks.size == 1) packTitle else "$packTitle #$packNumber"
            onProgress("Building $displayName (${chunkFiles.size} animated stickers)...")

            val packId = "tt_anim_${UUID.randomUUID().toString().substring(0, 8)}"
            val packDir = File(context.filesDir, "sticker_packs/$packId").apply { mkdirs() }
            val stickers = mutableListOf<Sticker>()

            chunkFiles.forEachIndexed { stickerIndex, srcFile ->
                val fileName = "sticker_${stickerIndex + 1}.webp"
                val destFile = File(packDir, fileName)
                try {
                    val success = WebpProcessingEngine.processAnimatedSticker(srcFile, destFile)
                    if (success && destFile.exists()) {
                        stickers.add(
                            Sticker(
                                imageFileName = fileName,
                                emojis = listOf("⚡", "🔥"),
                                sizeBytes = destFile.length(),
                                isAnimated = true
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process animated sticker ${srcFile.name}", e)
                }
            }

            // WhatsApp requires at least 3 stickers in a pack
            while (stickers.isNotEmpty() && stickers.size < PACK_MIN_STICKERS) {
                val dupIndex = stickers.size + 1
                val first = stickers.first()
                val dupName = "sticker_$dupIndex.webp"
                val dupFile = File(packDir, dupName)
                File(packDir, first.imageFileName).copyTo(dupFile, overwrite = true)
                stickers.add(
                    Sticker(
                        imageFileName = dupName,
                        emojis = first.emojis,
                        sizeBytes = dupFile.length(),
                        isAnimated = true
                    )
                )
            }

            if (stickers.size >= PACK_MIN_STICKERS) {
                val trayFileName = "tray_icon.png"
                val trayFile = File(packDir, trayFileName)
                WebpProcessingEngine.createTrayIcon(File(packDir, stickers.first().imageFileName), trayFile)

                val pack = StickerPack(
                    id = packId,
                    name = displayName,
                    publisher = "TikTok to WhatsApp",
                    trayImageFile = trayFileName,
                    animatedStickerPack = true,
                    stickers = stickers
                )
                storageManager.savePack(pack)
                packs.add(pack)
            }
        }
        return packs
    }

    /**
     * Converts STATIC stickers into WhatsApp static sticker packs (animated_sticker_pack = false).
     */
    private fun convertStaticFilesIntoPacks(
        files: List<File>,
        onProgress: (String) -> Unit,
        packTitle: String = "TikTok Favorited Stickers"
    ): List<StickerPack> {
        val packs = mutableListOf<StickerPack>()
        val chunks = files.chunked(PACK_MAX_STICKERS)

        chunks.forEachIndexed { packIndex, chunkFiles ->
            val packNumber = packIndex + 1
            val displayName = if (chunks.size == 1) packTitle else "$packTitle #$packNumber"
            onProgress("Building $displayName (${chunkFiles.size} static stickers)...")

            val packId = "tt_static_${UUID.randomUUID().toString().substring(0, 8)}"
            val packDir = File(context.filesDir, "sticker_packs/$packId").apply { mkdirs() }
            val stickers = mutableListOf<Sticker>()

            chunkFiles.forEachIndexed { stickerIndex, srcFile ->
                val fileName = "sticker_${stickerIndex + 1}.webp"
                val destFile = File(packDir, fileName)
                try {
                    val success = srcFile.inputStream().use { stream ->
                        WebpProcessingEngine.processStaticSticker(stream, destFile)
                    }
                    if (success && destFile.exists()) {
                        stickers.add(
                            Sticker(
                                imageFileName = fileName,
                                emojis = listOf("✨", "💖"),
                                sizeBytes = destFile.length(),
                                isAnimated = false
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process static sticker ${srcFile.name}", e)
                }
            }

            // WhatsApp requires at least 3 stickers in a pack
            while (stickers.isNotEmpty() && stickers.size < PACK_MIN_STICKERS) {
                val dupIndex = stickers.size + 1
                val first = stickers.first()
                val dupName = "sticker_$dupIndex.webp"
                val dupFile = File(packDir, dupName)
                File(packDir, first.imageFileName).copyTo(dupFile, overwrite = true)
                stickers.add(
                    Sticker(
                        imageFileName = dupName,
                        emojis = first.emojis,
                        sizeBytes = dupFile.length(),
                        isAnimated = false
                    )
                )
            }

            if (stickers.size >= PACK_MIN_STICKERS) {
                val trayFileName = "tray_icon.png"
                val trayFile = File(packDir, trayFileName)
                WebpProcessingEngine.createTrayIcon(File(packDir, stickers.first().imageFileName), trayFile)

                val pack = StickerPack(
                    id = packId,
                    name = displayName,
                    publisher = "TikTok to WhatsApp",
                    trayImageFile = trayFileName,
                    animatedStickerPack = false,
                    stickers = stickers
                )
                storageManager.savePack(pack)
                packs.add(pack)
            }
        }
        return packs
    }

    private fun convertUrisIntoPacks(uris: List<Uri>, onProgress: (String) -> Unit): List<StickerPack> {
        val packs = mutableListOf<StickerPack>()
        val chunks = uris.chunked(PACK_MAX_STICKERS)

        chunks.forEachIndexed { packIndex, chunkUris ->
            val packNumber = packIndex + 1
            val displayName = if (chunks.size == 1) "TikTok Stickers" else "TikTok Stickers #$packNumber"
            onProgress("Building $displayName (${chunkUris.size} stickers)...")

            val packId = "tt_${UUID.randomUUID().toString().substring(0, 8)}"
            val packDir = File(context.filesDir, "sticker_packs/$packId").apply { mkdirs() }
            val stickers = mutableListOf<Sticker>()

            chunkUris.forEachIndexed { stickerIndex, srcUri ->
                val fileName = "sticker_${stickerIndex + 1}.webp"
                val destFile = File(packDir, fileName)
                try {
                    val success = context.contentResolver.openInputStream(srcUri)?.use { stream ->
                        WebpProcessingEngine.processStaticSticker(stream, destFile)
                    } ?: false
                    if (success && destFile.exists()) {
                        stickers.add(
                            Sticker(
                                imageFileName = fileName,
                                emojis = listOf("✨", "🔥"),
                                sizeBytes = destFile.length()
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process URI $srcUri", e)
                }
            }

            while (stickers.isNotEmpty() && stickers.size < PACK_MIN_STICKERS) {
                val dupIndex = stickers.size + 1
                val first = stickers.first()
                val dupName = "sticker_$dupIndex.webp"
                val dupFile = File(packDir, dupName)
                File(packDir, first.imageFileName).copyTo(dupFile, overwrite = true)
                stickers.add(Sticker(imageFileName = dupName, emojis = first.emojis, sizeBytes = dupFile.length()))
            }

            if (stickers.size >= PACK_MIN_STICKERS) {
                val trayFileName = "tray_icon.png"
                val trayFile = File(packDir, trayFileName)
                WebpProcessingEngine.createTrayIcon(File(packDir, stickers.first().imageFileName), trayFile)

                val pack = StickerPack(
                    id = packId,
                    name = displayName,
                    publisher = "TikTok to WhatsApp",
                    trayImageFile = trayFileName,
                    stickers = stickers
                )
                storageManager.savePack(pack)
                packs.add(pack)
            }
        }
        return packs
    }
}
