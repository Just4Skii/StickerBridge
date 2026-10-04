package com.example.tiktoktowhatsappstickers.core.ingestion

import android.content.Context
import android.util.Log
import com.example.tiktoktowhatsappstickers.core.engine.WebpProcessingEngine
import com.example.tiktoktowhatsappstickers.data.model.Sticker
import com.example.tiktoktowhatsappstickers.data.model.StickerPack
import com.example.tiktoktowhatsappstickers.data.storage.StickerStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class TikTokIngester(private val context: Context) {

    companion object {
        private const val TAG = "TikTokIngester"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val storageManager = StickerStorageManager.getInstance(context)

    sealed class IngestionResult {
        data class Success(val pack: StickerPack) : IngestionResult()
        data class Error(val message: String) : IngestionResult()
    }

    /**
     * Ingests a TikTok URL (sticker set, video, or direct image CDN URL).
     */
    suspend fun ingestUrl(url: String, customPackName: String? = null): IngestionResult =
        withContext(Dispatchers.IO) {
            try {
                val cleanUrl = url.trim()
                if (cleanUrl.isBlank() || !cleanUrl.startsWith("http")) {
                    return@withContext IngestionResult.Error("Invalid URL provided")
                }

                Log.d(TAG, "Ingesting TikTok URL: $cleanUrl")
                val request = Request.Builder()
                    .url(cleanUrl)
                    .header("User-Agent", USER_AGENT)
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext IngestionResult.Error("Network error: HTTP ${response.code}")
                }

                val finalUrl = response.request.url.toString()
                val contentType = response.header("Content-Type") ?: ""
                val bodyString = response.body?.string() ?: ""

                // Case 1: Direct Image/WebP download
                if (contentType.startsWith("image/") || finalUrl.endsWith(".webp") || finalUrl.endsWith(".png")) {
                    return@withContext importSingleImageAsPack(cleanUrl, customPackName)
                }

                // Case 2: TikTok Web Page - Parse sticker URLs, photo slides, or video preview assets
                val stickerUrls = extractImageUrlsFromTikTokPage(bodyString)
                if (stickerUrls.isEmpty()) {
                    return@withContext IngestionResult.Error("No media assets found in this link. Tip: On TikTok, stickers are saved in your app favorites — tap '1-Tap Auto-Scan' below to import them directly!")
                }

                // Create a new StickerPack
                val packId = "tt_" + UUID.randomUUID().toString().substring(0, 8)
                val packDir = File(context.filesDir, "sticker_packs/$packId").apply { mkdirs() }
                val packName = customPackName ?: ("TikTok Pack #" + packId.takeLast(4))

                val savedStickers = mutableListOf<Sticker>()

                // Download each sticker image
                stickerUrls.take(30).forEachIndexed { index, imgUrl ->
                    try {
                        val imgReq = Request.Builder().url(imgUrl).header("User-Agent", USER_AGENT).build()
                        val imgRes = client.newCall(imgReq).execute()
                        if (imgRes.isSuccessful) {
                            val imgBytes = imgRes.body?.bytes()
                            if (imgBytes != null && imgBytes.isNotEmpty()) {
                                val stickerFileName = "sticker_${index + 1}.webp"
                                val stickerFile = File(packDir, stickerFileName)
                                val success = WebpProcessingEngine.processStaticSticker(
                                    imgBytes.inputStream(),
                                    stickerFile
                                )
                                if (success && stickerFile.exists()) {
                                    savedStickers.add(
                                        Sticker(
                                            imageFileName = stickerFileName,
                                            emojis = listOf("🔥", "✨"),
                                            sizeBytes = stickerFile.length()
                                        )
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to download sticker $index from $imgUrl", e)
                    }
                }

                if (savedStickers.isEmpty()) {
                    return@withContext IngestionResult.Error("Could not extract any stickers from this link.")
                }

                // WhatsApp requires at least 3 stickers in a pack.
                // If only 1 or 2 stickers were found, pad them so WhatsApp accepts the pack immediately.
                while (savedStickers.size < 3) {
                    val duplicateIndex = savedStickers.size + 1
                    val original = savedStickers.first()
                    val dupFileName = "sticker_$duplicateIndex.webp"
                    val dupFile = File(packDir, dupFileName)
                    File(packDir, original.imageFileName).copyTo(dupFile, overwrite = true)
                    savedStickers.add(
                        Sticker(
                            imageFileName = dupFileName,
                            emojis = original.emojis,
                            sizeBytes = dupFile.length()
                        )
                    )
                }

                // Generate Tray Icon from first sticker
                val trayFileName = "tray_icon.png"
                val trayFile = File(packDir, trayFileName)
                val firstStickerFile = File(packDir, savedStickers.first().imageFileName)
                WebpProcessingEngine.createTrayIcon(firstStickerFile, trayFile)

                val newPack = StickerPack(
                    id = packId,
                    name = packName,
                    publisher = "TikTok Creator",
                    trayImageFile = trayFileName,
                    animatedStickerPack = false,
                    stickers = savedStickers
                )

                storageManager.savePack(newPack)
                IngestionResult.Success(newPack)
            } catch (e: Exception) {
                Log.e(TAG, "Error ingesting TikTok URL: $url", e)
                IngestionResult.Error(e.localizedMessage ?: "Unknown error occurred")
            }
        }

    private fun importSingleImageAsPack(imageUrl: String, customPackName: String?): IngestionResult {
        return try {
            val req = Request.Builder().url(imageUrl).header("User-Agent", USER_AGENT).build()
            val res = client.newCall(req).execute()
            val bytes = res.body?.bytes() ?: return IngestionResult.Error("Empty image received")

            val packId = "tt_single_" + UUID.randomUUID().toString().substring(0, 6)
            val packDir = File(context.filesDir, "sticker_packs/$packId").apply { mkdirs() }

            val savedStickers = mutableListOf<Sticker>()
            // WhatsApp requires at least 3 stickers in a pack. We create 3 variants or duplicates
            for (i in 1..3) {
                val fileName = "sticker_$i.webp"
                val file = File(packDir, fileName)
                WebpProcessingEngine.processStaticSticker(bytes.inputStream(), file)
                savedStickers.add(Sticker(imageFileName = fileName, emojis = listOf("✨"), sizeBytes = file.length()))
            }

            val trayFile = File(packDir, "tray_icon.png")
            WebpProcessingEngine.createTrayIcon(File(packDir, savedStickers.first().imageFileName), trayFile)

            val pack = StickerPack(
                id = packId,
                name = customPackName ?: "TikTok Sticker",
                publisher = "TikTok to WhatsApp",
                trayImageFile = "tray_icon.png",
                stickers = savedStickers
            )
            storageManager.savePack(pack)
            IngestionResult.Success(pack)
        } catch (e: Exception) {
            IngestionResult.Error("Failed to import image: ${e.message}")
        }
    }

    private fun extractImageUrlsFromTikTokPage(html: String): List<String> {
        val unescaped = html.replace("\\/", "/").replace("\\u002F", "/")
        val urls = LinkedHashSet<String>()
        // Match tiktokcdn image / webp URLs
        val pattern = Pattern.compile("https://[a-zA-Z0-9.-]+\\.(?:tiktokcdn|byteoversea)\\.com/[^\"'\\s<>]+\\.(?:webp|png|jpeg|jpg)")
        val matcher = pattern.matcher(unescaped)
        while (matcher.find()) {
            val url = matcher.group()
            if (!url.contains("avatar") && !url.contains("favicon") && !url.contains("logo") && !url.contains("profile")) {
                urls.add(url)
            }
        }
        // Match TikTok CDN URLs without extension (e.g. tos-maliva media)
        val tosPattern = Pattern.compile("https://[a-zA-Z0-9.-]+\\.(?:tiktokcdn|byteoversea)\\.com/tos-[a-zA-Z0-9~_.-]+(?:~tplv-[a-zA-Z0-9_.-]+)?")
        val tosMatcher = tosPattern.matcher(unescaped)
        while (tosMatcher.find()) {
            val url = tosMatcher.group()
            if (!url.contains("avatar") && !url.contains("profile")) {
                urls.add(url)
            }
        }
        return urls.toList()
    }
}
