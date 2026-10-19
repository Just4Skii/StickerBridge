package com.example.tiktoktowhatsappstickers.core.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Turns whatever image TikTok hands us (static or animated WebP, PNG, JPEG, GIF) into a sticker that
 * satisfies WhatsApp's fixed format:
 *
 *  - exactly 512 x 512 px canvas, content centred with a transparent margin
 *  - static  : WebP, <= 100 KB
 *  - animated: animated WebP, <= 500 KB, <= 10 s, every frame also 512 x 512
 *
 * Animated stickers are decoded frame by frame, composited, scaled to the 512 canvas and re-encoded, so
 * a 1080 px source ends up exactly as "properly sized" as a 300 px one (the old code only patched
 * header offsets, which silently produced broken files for anything not already <= 512).
 */
object WebpProcessingEngine {

    private const val TAG = "WebpProcessingEngine"
    const val WHATSAPP_STICKER_SIZE = 512
    const val WHATSAPP_TRAY_SIZE = 96
    const val MAX_STATIC_FILE_SIZE_BYTES = 100 * 1024 // 100 KB
    const val MAX_ANIMATED_FILE_SIZE_BYTES = 500 * 1024 // 500 KB
    const val PADDING_PX = 16

    private const val MAX_ANIMATION_MS = 10_000
    private const val MIN_FRAME_MS = 20
    private const val MAX_FRAMES = 120
    private const val MAX_DECODE_EDGE = 2048

    enum class Kind { WEBP, PNG, GIF, JPEG, UNKNOWN }

    class Normalized(val bytes: ByteArray, val animated: Boolean)

    data class ValidationResult(
        val isValid: Boolean,
        val width: Int = 0,
        val height: Int = 0,
        val sizeBytes: Long = 0,
        val isWebp: Boolean = false,
        val isAnimated: Boolean = false,
        val error: String? = null
    )

    // ------------------------------------------------------------------ detection

    fun sniff(b: ByteArray): Kind {
        if (b.size < 12) return Kind.UNKNOWN
        if (WebpContainer.isWebp(b)) return Kind.WEBP
        if (b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()) return Kind.PNG
        if (b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()) return Kind.GIF
        if (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()) return Kind.JPEG
        return Kind.UNKNOWN
    }

    fun isAnimatedBytes(b: ByteArray): Boolean = when (sniff(b)) {
        Kind.WEBP -> WebpContainer.parse(b)?.isAnimated == true
        Kind.GIF -> runCatching { (Movie.decodeByteArray(b, 0, b.size)?.duration() ?: 0) > 0 }.getOrDefault(false)
        else -> false
    }

    fun isAnimatedWebp(file: File): Boolean {
        if (!file.exists() || file.length() < 32 || file.length() > 8_000_000) return false
        return try {
            val bytes = file.readBytes()
            sniff(bytes) == Kind.WEBP && WebpContainer.parse(bytes)?.isAnimated == true
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ main entry

    /** Converts any supported image to a WhatsApp-compliant sticker, or null if it can't be done. */
    fun normalize(raw: ByteArray): Normalized? {
        return try {
            when (sniff(raw)) {
                Kind.WEBP -> {
                    val info = WebpContainer.parse(raw)
                    if (info != null && info.isAnimated) {
                        animatedFromWebp(raw, info)?.let { Normalized(it, true) } ?: staticFrom(raw)
                    } else staticFrom(raw)
                }
                Kind.GIF -> {
                    val movie = Movie.decodeByteArray(raw, 0, raw.size)
                    if (movie != null && movie.duration() > 0 && movie.width() > 0) {
                        animatedFromGif(movie)?.let { Normalized(it, true) } ?: staticFrom(raw)
                    } else staticFrom(raw)
                }
                Kind.PNG, Kind.JPEG -> staticFrom(raw)
                Kind.UNKNOWN -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "normalize failed", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "normalize ran out of memory", e)
            null
        }
    }

    /** Back-compat wrapper: animated file -> normalized animated sticker on disk. */
    fun processAnimatedSticker(sourceFile: File, outputFile: File): Boolean {
        val n = normalize(sourceFile.readBytes()) ?: return false
        if (!n.animated) return false
        outputFile.writeBytes(n.bytes)
        return true
    }

    /** Back-compat wrapper: any image -> STATIC sticker on disk (first frame for animations). */
    fun processStaticSticker(inputStream: InputStream, outputFile: File): Boolean {
        return try {
            val bytes = inputStream.readBytes()
            val out = staticFrom(bytes) ?: return false
            outputFile.writeBytes(out.bytes)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process static sticker", e)
            false
        }
    }

    // ------------------------------------------------------------------ static

    private fun staticFrom(raw: ByteArray): Normalized? {
        val src = decodeSampled(raw) ?: return null
        try {
            // Escalating margins shrink the artwork inside the fixed canvas if the file is still too heavy.
            for (pad in intArrayOf(PADDING_PX, 40, 72)) {
                val canvas = scaleAndPadToSquare(src, WHATSAPP_STICKER_SIZE, pad)
                try {
                    val lossless = if (Build.VERSION.SDK_INT >= 30) canvas.toWebp(100, true) else null
                    if (lossless != null && lossless.size <= MAX_STATIC_FILE_SIZE_BYTES) return Normalized(lossless, false)
                    var q = 90
                    while (q >= 10) {
                        val lossy = canvas.toWebp(q, false)
                        if (lossy.size <= MAX_STATIC_FILE_SIZE_BYTES) return Normalized(lossy, false)
                        q -= 10
                    }
                } finally {
                    canvas.recycle()
                }
            }
            return null
        } finally {
            src.recycle()
        }
    }

    private fun decodeSampled(raw: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODE_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
    }

    // ------------------------------------------------------------------ animated

    private class Attempt(val lossless: Boolean, val quality: Int, val step: Int, val pad: Int)

    private val ATTEMPTS = listOf(
        Attempt(true, 100, 1, PADDING_PX),
        Attempt(false, 85, 1, PADDING_PX),
        Attempt(false, 70, 1, PADDING_PX),
        Attempt(false, 55, 1, PADDING_PX),
        Attempt(false, 45, 2, PADDING_PX),
        Attempt(false, 35, 2, 28),
        Attempt(false, 28, 3, 40),
        Attempt(false, 20, 4, 56)
    )

    /** Produces one composited full-canvas frame at a time. Always accessed sequentially. */
    private interface FrameSource {
        val width: Int
        val height: Int
        val count: Int
        val loopCount: Int
        fun durationMs(i: Int): Int
        /** Renders frame [i] onto [target] (kept between calls so disposal/blending carry over). */
        fun render(i: Int, target: Bitmap, canvas: Canvas): Boolean
    }

    private fun animatedFromWebp(raw: ByteArray, info: WebpContainer.Info): ByteArray? =
        encodeAnimation { WebpFrameSource(info) }

    private fun animatedFromGif(movie: Movie): ByteArray? {
        val total = movie.duration().coerceAtLeast(1)
        val stepMs = max(40, ceil(total / MAX_FRAMES.toDouble()).toInt())
        return encodeAnimation { GifFrameSource(movie, stepMs, total) }
    }

    private fun encodeAnimation(newSource: () -> FrameSource): ByteArray? {
        for (a in ATTEMPTS) {
            if (a.lossless && Build.VERSION.SDK_INT < 30) continue
            val out = encodeOnce(newSource(), a) ?: continue
            if (out.size <= MAX_ANIMATED_FILE_SIZE_BYTES) return out
        }
        return null
    }

    private fun encodeOnce(source: FrameSource, a: Attempt): ByteArray? {
        if (source.count < 2) return null
        val step = max(a.step, ceil(source.count / MAX_FRAMES.toDouble()).toInt())
        val work = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(work)
        val frames = ArrayList<WebpContainer.EncodedFrame>()
        var elapsed = 0
        try {
            var i = 0
            while (i < source.count) {
                if (!source.render(i, work, canvas)) {
                    // Frames in the skipped group still need to be applied for correct compositing.
                    return null
                }
                // Advance through the rest of the group so blending/disposal state stays correct.
                var group = max(source.durationMs(i), MIN_FRAME_MS)
                var j = i + 1
                while (j < min(i + step, source.count)) {
                    if (!source.render(j, work, canvas)) return null
                    group += max(source.durationMs(j), MIN_FRAME_MS)
                    j++
                }
                if (elapsed + group > MAX_ANIMATION_MS && frames.isNotEmpty()) break
                val sized = scaleAndPadToSquare(work, WHATSAPP_STICKER_SIZE, a.pad)
                val encoded = try {
                    sized.toWebp(a.quality, a.lossless)
                } finally {
                    sized.recycle()
                }
                val chunks = WebpContainer.extractImageChunks(encoded)
                if (chunks.isEmpty()) return null
                val size = WebpContainer.stillSize(chunks)
                if (size != null && (size.first != WHATSAPP_STICKER_SIZE || size.second != WHATSAPP_STICKER_SIZE)) return null
                frames.add(WebpContainer.EncodedFrame(chunks, group))
                elapsed += group
                i = j
            }
        } finally {
            work.recycle()
        }
        if (frames.size < 2) return null
        return WebpContainer.buildAnimated(WHATSAPP_STICKER_SIZE, WHATSAPP_STICKER_SIZE, frames, source.loopCount)
    }

    private class WebpFrameSource(private val info: WebpContainer.Info) : FrameSource {
        override val width get() = info.canvasWidth
        override val height get() = info.canvasHeight
        override val count get() = info.frames.size
        override val loopCount get() = info.loopCount
        override fun durationMs(i: Int) = info.frames[i].durationMs

        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

        override fun render(i: Int, target: Bitmap, canvas: Canvas): Boolean {
            if (i > 0) {
                val prev = info.frames[i - 1]
                if (prev.disposeToBackground) canvas.drawRect(rect(prev), clear)
            }
            val f = info.frames[i]
            val still = WebpContainer.wrapFrameAsStill(f)
            val bmp = BitmapFactory.decodeByteArray(still, 0, still.size) ?: return false
            try {
                if (!f.blend) canvas.drawRect(rect(f), clear)
                canvas.drawBitmap(bmp, f.x.toFloat(), f.y.toFloat(), paint)
            } finally {
                bmp.recycle()
            }
            return true
        }

        private fun rect(f: WebpContainer.Frame) = Rect(f.x, f.y, f.x + f.width, f.y + f.height)
    }

    private class GifFrameSource(
        private val movie: Movie,
        private val stepMs: Int,
        private val totalMs: Int
    ) : FrameSource {
        override val width get() = movie.width()
        override val height get() = movie.height()
        override val count get() = max(1, ceil(totalMs / stepMs.toDouble()).toInt())
        override val loopCount get() = 0
        override fun durationMs(i: Int) = stepMs

        override fun render(i: Int, target: Bitmap, canvas: Canvas): Boolean {
            target.eraseColor(0)
            movie.setTime(min(i * stepMs, totalMs - 1))
            movie.draw(canvas, 0f, 0f)
            return true
        }
    }

    // ------------------------------------------------------------------ inspection & tray

    /** Checks an already-normalized sticker file against WhatsApp's rules. */
    fun inspectFile(file: File): ValidationResult {
        if (!file.exists()) return ValidationResult(isValid = false, error = "File not found")
        val size = file.length()
        return try {
            val bytes = file.readBytes()
            val info = WebpContainer.parse(bytes)
                ?: return ValidationResult(isValid = false, sizeBytes = size, error = "Not a WebP file")
            val animated = info.isAnimated
            val limit = if (animated) MAX_ANIMATED_FILE_SIZE_BYTES else MAX_STATIC_FILE_SIZE_BYTES
            val dimsOk = info.canvasWidth == WHATSAPP_STICKER_SIZE && info.canvasHeight == WHATSAPP_STICKER_SIZE
            val error = when {
                !dimsOk -> "Dimensions are ${info.canvasWidth}x${info.canvasHeight}, WhatsApp requires exactly 512x512"
                size > limit -> "File size ($size bytes) exceeds WhatsApp limit ($limit bytes)"
                else -> null
            }
            ValidationResult(error == null, info.canvasWidth, info.canvasHeight, size, true, animated, error)
        } catch (e: Exception) {
            ValidationResult(isValid = false, error = e.localizedMessage)
        }
    }

    /** Generates the 96x96 PNG (< 50 KB) tray icon WhatsApp requires. */
    fun createTrayIcon(sourceFile: File, outputFile: File): Boolean {
        try {
            val bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath) ?: return false
            val tray = scaleAndPadToSquare(bitmap, WHATSAPP_TRAY_SIZE, 4)
            FileOutputStream(outputFile).use { tray.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            tray.recycle()
            return outputFile.length() < 50 * 1024
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate tray icon", e)
            return false
        }
    }

    // ------------------------------------------------------------------ bitmap helpers

    private fun Bitmap.toWebp(quality: Int, lossless: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= 30) {
            if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        compress(format, quality, out)
        return out.toByteArray()
    }

    private fun scaleAndPadToSquare(src: Bitmap, targetSize: Int, padding: Int): Bitmap {
        val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val avail = targetSize - padding * 2
        val scale = min(avail.toFloat() / src.width, avail.toFloat() / src.height)
        val left = padding + (avail - src.width * scale) / 2f
        val top = padding + (avail - src.height * scale) / 2f
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(left, top)
        }
        canvas.drawBitmap(src, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return output
    }
}
