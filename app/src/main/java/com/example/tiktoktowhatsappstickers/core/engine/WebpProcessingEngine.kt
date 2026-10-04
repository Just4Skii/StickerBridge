package com.example.tiktoktowhatsappstickers.core.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.min

object WebpProcessingEngine {

    private const val TAG = "WebpProcessingEngine"
    const val WHATSAPP_STICKER_SIZE = 512
    const val WHATSAPP_TRAY_SIZE = 96
    const val MAX_STATIC_FILE_SIZE_BYTES = 100 * 1024 // 100 KB
    const val MAX_ANIMATED_FILE_SIZE_BYTES = 500 * 1024 // 500 KB
    const val PADDING_PX = 16

    data class ValidationResult(
        val isValid: Boolean,
        val width: Int = 0,
        val height: Int = 0,
        val sizeBytes: Long = 0,
        val isWebp: Boolean = false,
        val isAnimated: Boolean = false,
        val error: String? = null
    )

    /**
     * Checks if a file is an animated WebP file.
     */
    fun isAnimatedWebp(file: File): Boolean {
        if (!file.exists() || file.length() < 32) return false
        return try {
            file.inputStream().use { stream ->
                val buf = ByteArray(128)
                val read = stream.read(buf)
                if (read < 32) return false
                isAnimatedWebpBytes(buf.copyOf(read))
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if byte array contains an animated WebP header.
     */
    fun isAnimatedWebpBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 30) return false
        val isRiff = bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
        val isWebp = bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        if (!isRiff || !isWebp) return false

        val isVp8x = bytes[12] == 'V'.code.toByte() && bytes[13] == 'P'.code.toByte() &&
                bytes[14] == '8'.code.toByte() && bytes[15] == 'X'.code.toByte()
        if (isVp8x && bytes.size >= 21) {
            val flags = bytes[20].toInt()
            if ((flags and 0x02) != 0) return true
        }

        val text = String(bytes, Charsets.US_ASCII)
        return text.contains("ANIM") || text.contains("ANMF")
    }

    /**
     * Converts / pads an animated WebP file to WhatsApp-compliant 512x512 animated WebP.
     * Preserves all frames, delays, alpha channels, and bitstreams without transcoding loss.
     */
    fun processAnimatedSticker(sourceFile: File, outputFile: File): Boolean {
        try {
            val bytes = sourceFile.readBytes()
            if (bytes.size < 32 || !isAnimatedWebpBytes(bytes)) return false

            val isVp8x = bytes[12] == 'V'.code.toByte() && bytes[13] == 'P'.code.toByte() &&
                    bytes[14] == '8'.code.toByte() && bytes[15] == 'X'.code.toByte()
            if (!isVp8x || bytes.size < 30) return false

            val origW = (((bytes[24].toInt() and 0xFF) or
                    ((bytes[25].toInt() and 0xFF) shl 8) or
                    ((bytes[26].toInt() and 0xFF) shl 16))) + 1

            val origH = (((bytes[27].toInt() and 0xFF) or
                    ((bytes[28].toInt() and 0xFF) shl 8) or
                    ((bytes[29].toInt() and 0xFF) shl 16))) + 1

            val targetSize = WHATSAPP_STICKER_SIZE // 512

            // If already 512x512 and <= 500KB, copy directly
            if (origW == targetSize && origH == targetSize && bytes.size <= MAX_ANIMATED_FILE_SIZE_BYTES) {
                sourceFile.copyTo(outputFile, overwrite = true)
                return true
            }

            // Calculate even padding offsets to center within 512x512
            val rawPadX = (targetSize - origW).coerceAtLeast(0) / 2
            val rawPadY = (targetSize - origH).coerceAtLeast(0) / 2
            val padX = (rawPadX / 2) * 2
            val padY = (rawPadY / 2) * 2

            val newWMinus1 = targetSize - 1
            val newHMinus1 = targetSize - 1

            // Update canvas dimensions in VP8X chunk
            bytes[24] = (newWMinus1 and 0xFF).toByte()
            bytes[25] = ((newWMinus1 shr 8) and 0xFF).toByte()
            bytes[26] = ((newWMinus1 shr 16) and 0xFF).toByte()

            bytes[27] = (newHMinus1 and 0xFF).toByte()
            bytes[28] = ((newHMinus1 shr 8) and 0xFF).toByte()
            bytes[29] = ((newHMinus1 shr 16) and 0xFF).toByte()

            val dxUnits = padX / 2
            val dyUnits = padY / 2

            // Traverse chunks and adjust frame offsets in every ANMF chunk
            var offset = 12 // after RIFF header
            while (offset + 8 <= bytes.size) {
                val fourCC = String(bytes, offset, 4, Charsets.US_ASCII)
                val chunkSize = (bytes[offset + 4].toLong() and 0xFF) or
                        ((bytes[offset + 5].toLong() and 0xFF) shl 8) or
                        ((bytes[offset + 6].toLong() and 0xFF) shl 16) or
                        ((bytes[offset + 7].toLong() and 0xFF) shl 24)

                if (fourCC == "ANMF" && offset + 8 + 6 <= bytes.size) {
                    val p = offset + 8
                    val currX = (bytes[p].toInt() and 0xFF) or
                            ((bytes[p + 1].toInt() and 0xFF) shl 8) or
                            ((bytes[p + 2].toInt() and 0xFF) shl 16)

                    val currY = (bytes[p + 3].toInt() and 0xFF) or
                            ((bytes[p + 4].toInt() and 0xFF) shl 8) or
                            ((bytes[p + 5].toInt() and 0xFF) shl 16)

                    val newX = currX + dxUnits
                    val newY = currY + dyUnits

                    bytes[p] = (newX and 0xFF).toByte()
                    bytes[p + 1] = ((newX shr 8) and 0xFF).toByte()
                    bytes[p + 2] = ((newX shr 16) and 0xFF).toByte()

                    bytes[p + 3] = (newY and 0xFF).toByte()
                    bytes[p + 4] = ((newY shr 8) and 0xFF).toByte()
                    bytes[p + 5] = ((newY shr 16) and 0xFF).toByte()
                }

                val paddedSize = chunkSize + (chunkSize % 2)
                offset += 8 + paddedSize.toInt()
            }

            outputFile.writeBytes(bytes)
            return outputFile.exists() && outputFile.length() <= MAX_ANIMATED_FILE_SIZE_BYTES
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process animated sticker", e)
            return false
        }
    }

    /**
     * Inspects raw file bytes to check for WebP signature and animated chunks.
     */
    fun inspectFile(file: File): ValidationResult {
        if (!file.exists()) {
            return ValidationResult(isValid = false, error = "File not found")
        }
        val size = file.length()
        try {
            val bytes = file.inputStream().use { stream ->
                val buf = ByteArray(64)
                val read = stream.read(buf)
                buf.copyOf(read)
            }
            if (bytes.size < 16) {
                return ValidationResult(isValid = false, error = "File too small")
            }

            // RIFF header
            val isRiff = bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                    bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
            val isWebp = bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                    bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()

            if (!isRiff || !isWebp) {
                return ValidationResult(isValid = false, isWebp = false, sizeBytes = size, error = "Not a WebP file")
            }

            // Check for ANIM chunk (Animated WebP)
            val isAnimated = isAnimatedWebpBytes(bytes)

            // Decode bounds
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)

            val width = options.outWidth
            val height = options.outHeight

            val maxAllowed = if (isAnimated) MAX_ANIMATED_FILE_SIZE_BYTES else MAX_STATIC_FILE_SIZE_BYTES
            val isSizeOk = size <= maxAllowed
            val isDimensionsOk = width == WHATSAPP_STICKER_SIZE && height == WHATSAPP_STICKER_SIZE

            val errorMsg = when {
                !isDimensionsOk -> "Dimensions are ${width}x${height}, WhatsApp requires exactly 512x512"
                !isSizeOk -> "File size ($size bytes) exceeds WhatsApp limit ($maxAllowed bytes)"
                else -> null
            }

            return ValidationResult(
                isValid = errorMsg == null,
                width = width,
                height = height,
                sizeBytes = size,
                isWebp = true,
                isAnimated = isAnimated,
                error = errorMsg
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inspect file ${file.name}", e)
            return ValidationResult(isValid = false, error = e.localizedMessage)
        }
    }

    /**
     * Converts any input image (PNG, JPEG, WebP) into an official WhatsApp static sticker:
     * - Exactly 512x512 pixels
     * - 16px transparent margin
     * - Alpha transparency preserved
     * - WebP format under 100 KB
     */
    fun processStaticSticker(inputStream: InputStream, outputFile: File): Boolean {
        try {
            val originalBitmap = BitmapFactory.decodeStream(inputStream) ?: return false
            val scaledSticker = scaleAndPadToSquare(originalBitmap, WHATSAPP_STICKER_SIZE, PADDING_PX)

            var quality = 90
            var compressedBytes: ByteArray
            do {
                val stream = ByteArrayOutputStream()
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                scaledSticker.compress(format, quality, stream)
                compressedBytes = stream.toByteArray()
                quality -= 10
            } while (compressedBytes.size > MAX_STATIC_FILE_SIZE_BYTES && quality >= 40)

            FileOutputStream(outputFile).use { out ->
                out.write(compressedBytes)
            }

            originalBitmap.recycle()
            scaledSticker.recycle()
            return outputFile.length() <= MAX_STATIC_FILE_SIZE_BYTES
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process static sticker", e)
            return false
        }
    }

    /**
     * Generates a 96x96 tray icon required by WhatsApp for sticker packs.
     */
    fun createTrayIcon(sourceFile: File, outputFile: File): Boolean {
        try {
            val bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath) ?: return false
            val trayBitmap = scaleAndPadToSquare(bitmap, WHATSAPP_TRAY_SIZE, 4)

            FileOutputStream(outputFile).use { out ->
                trayBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            bitmap.recycle()
            trayBitmap.recycle()
            return outputFile.length() < 50 * 1024 // Tray icon must be < 50 KB
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate tray icon", e)
            return false
        }
    }

    private fun scaleAndPadToSquare(src: Bitmap, targetSize: Int, padding: Int): Bitmap {
        val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val availSize = targetSize - (padding * 2)
        val scale = min(availSize.toFloat() / src.width, availSize.toFloat() / src.height)

        val scaledW = src.width * scale
        val scaledH = src.height * scale

        val left = padding + (availSize - scaledW) / 2f
        val top = padding + (availSize - scaledH) / 2f

        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(left, top)
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, matrix, paint)
        return output
    }
}
