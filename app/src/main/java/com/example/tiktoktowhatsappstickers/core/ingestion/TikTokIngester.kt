package com.example.tiktoktowhatsappstickers.core.ingestion

import android.content.Context
import android.util.Log
import com.example.tiktoktowhatsappstickers.data.model.StickerPack
import com.example.tiktoktowhatsappstickers.data.storage.StickerLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

    private val library = StickerLibrary(context)

    sealed class IngestionResult {
        data class Success(val pack: StickerPack, val alreadyHad: Boolean = false) : IngestionResult()
        data class Error(val message: String) : IngestionResult()
    }

    /**
     * Ingests a TikTok URL (sticker set, video, photo carousel, or direct image CDN URL).
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
                val rawBodyBytes = response.body?.bytes() ?: ByteArray(0)

                // Case 1: Direct Image/WebP/GIF download
                if (contentType.startsWith("image/") ||
                    finalUrl.endsWith(".webp", ignoreCase = true) ||
                    finalUrl.endsWith(".png", ignoreCase = true) ||
                    finalUrl.endsWith(".gif", ignoreCase = true)
                ) {
                    if (rawBodyBytes.isEmpty()) {
                        return@withContext IngestionResult.Error("Empty image received")
                    }
                    val key = finalUrl.substringAfterLast('/').substringBefore('?').ifBlank { "direct_image" }
                    val manual = library.createManualPack(
                        name = customPackName ?: "TikTok Sticker",
                        raws = listOf(StickerLibrary.Raw(key, rawBodyBytes)),
                        publisher = "TikTok Creator"
                    )
                    val first = manual.packs.firstOrNull()
                        ?: return@withContext IngestionResult.Error("Could not convert image into a 512×512 WhatsApp sticker.")
                    return@withContext IngestionResult.Success(first, manual.alreadyHad)
                }

                // Case 2: TikTok Web Page - Parse sticker URLs, photo slides, or sticker assets
                val bodyString = String(rawBodyBytes, Charsets.UTF_8)
                val stickerUrls = extractImageUrlsFromTikTokPage(bodyString)
                if (stickerUrls.isEmpty()) {
                    return@withContext IngestionResult.Error(
                        "No sticker assets found in this link. Tip: On TikTok, stickers are saved in your app cache — tap '1-Tap Fetch TikTok Stickers' below to import them directly!"
                    )
                }

                val raws = ArrayList<StickerLibrary.Raw>()
                stickerUrls.take(StickerLibrary.MAX_PER_PACK).forEachIndexed { index, imgUrl ->
                    try {
                        val imgReq = Request.Builder().url(imgUrl).header("User-Agent", USER_AGENT).build()
                        val imgRes = client.newCall(imgReq).execute()
                        if (imgRes.isSuccessful) {
                            val imgBytes = imgRes.body?.bytes()
                            if (imgBytes != null && imgBytes.isNotEmpty()) {
                                val key = imgUrl.substringAfterLast('/').substringBefore('?').ifBlank { "url_$index" }
                                raws += StickerLibrary.Raw(key, imgBytes)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to download asset $index from $imgUrl", e)
                    }
                }

                if (raws.isEmpty()) {
                    return@withContext IngestionResult.Error("Could not download any media from this link.")
                }

                val packName = customPackName ?: "TikTok Link Pack"
                val manual = library.createManualPack(
                    name = packName,
                    raws = raws,
                    publisher = "TikTok Creator"
                )
                val first = manual.packs.firstOrNull()
                    ?: return@withContext IngestionResult.Error("Could not extract valid stickers from this link.")
                IngestionResult.Success(first, manual.alreadyHad)
            } catch (e: Exception) {
                Log.e(TAG, "Error ingesting TikTok URL: $url", e)
                IngestionResult.Error(e.localizedMessage ?: "Unknown error occurred")
            }
        }

    private fun extractImageUrlsFromTikTokPage(html: String): List<String> {
        val unescaped = html.replace("\\/", "/").replace("\\u002F", "/")
        val urls = LinkedHashSet<String>()
        // Match tiktokcdn image / webp / gif URLs
        val pattern = Pattern.compile("https://[a-zA-Z0-9.-]+\\.(?:tiktokcdn|byteoversea)\\.com/[^\"'\\s<>]+\\.(?:webp|png|gif|jpeg|jpg)")
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
