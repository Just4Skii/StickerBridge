package com.example.tiktoktowhatsappstickers.core.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.tiktoktowhatsappstickers.core.engine.WebpProcessingEngine
import kotlin.math.max
import kotlin.math.min

/**
 * Decides whether an image from TikTok's cache is a real sticker, as opposed to avatars, video covers
 * or UI graphics. Uses TikTok's image_config CDN URL (when present), folder hints, animation and alpha.
 */
object StickerClassifier {

    data class Verdict(val accept: Boolean, val reason: String)

    private val NON_STICKER_HINTS = listOf(
        "avatar", "profile", "avt-00", "sc=avatar", "cover", "thumb",
        "banner", "icon", "splash", "ad_", "photomode", "qrcode"
    )
    private val STICKER_URL_HINTS = listOf(
        "video2sticker", "tiktok-dm-sticker", "store_sticker", "static_sticker", "stable_sticker", "sticker"
    )

    fun classify(bytes: ByteArray, path: String, hintUrl: String? = null): Verdict {
        if (bytes.size < CandidateRules.MIN_BYTES || bytes.size > CandidateRules.MAX_BYTES) {
            return Verdict(false, "size")
        }
        val kind = WebpProcessingEngine.sniff(bytes)
        if (kind == WebpProcessingEngine.Kind.UNKNOWN) return Verdict(false, "not an image")
        // Photos/covers/screenshots are JPEG; stickers use WebP/PNG/GIF.
        if (kind == WebpProcessingEngine.Kind.JPEG) return Verdict(false, "jpeg")

        val lowerUrl = hintUrl?.lowercase().orEmpty()
        if (lowerUrl.isNotEmpty()) {
            if (NON_STICKER_HINTS.any { it in lowerUrl }) return Verdict(false, "non-sticker url")
            if (STICKER_URL_HINTS.any { it in lowerUrl }) return Verdict(true, "sticker url")
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return Verdict(false, "undecodable")
        if (min(w, h) < 48 || max(w, h) > 1600) return Verdict(false, "dimensions ${w}x$h")
        val aspect = w.toFloat() / h
        if (aspect < 0.5f || aspect > 2.0f) return Verdict(false, "aspect")

        val inStickerFolder = CandidateRules.isStickerPath(path)
        val lowerPath = path.lowercase()
        if (!inStickerFolder && NON_STICKER_HINTS.any { it in lowerPath }) return Verdict(false, "non-sticker path")

        if (WebpProcessingEngine.isAnimatedBytes(bytes)) return Verdict(true, "animated")
        if (inStickerFolder) return Verdict(true, "sticker folder")
        return if (hasTransparency(bytes, w, h)) Verdict(true, "transparent") else Verdict(false, "opaque")
    }

    private fun hasTransparency(bytes: ByteArray, w: Int, h: Int): Boolean {
        var sample = 1
        while (max(w, h) / sample > 96) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return false
        try {
            if (!bmp.hasAlpha()) return false
            val bw = bmp.width
            val bh = bmp.height
            val corners = intArrayOf(
                bmp.getPixel(0, 0), bmp.getPixel(bw - 1, 0), bmp.getPixel(0, bh - 1), bmp.getPixel(bw - 1, bh - 1)
            )
            if (corners.any { (it ushr 24) < 40 }) return true
            var transparent = 0
            val total = bw * bh
            val px = IntArray(total)
            bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
            for (p in px) if ((p ushr 24) < 40) transparent++
            return transparent * 100 / total >= 8
        } finally {
            bmp.recycle()
        }
    }
}
