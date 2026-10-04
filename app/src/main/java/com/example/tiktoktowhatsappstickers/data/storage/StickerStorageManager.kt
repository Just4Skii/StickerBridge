package com.example.tiktoktowhatsappstickers.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.example.tiktoktowhatsappstickers.data.model.Sticker
import com.example.tiktoktowhatsappstickers.data.model.StickerPack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

class StickerStorageManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "StickerStorageManager"
        private const val PACKS_DIR = "sticker_packs"
        private const val CONTENTS_FILE = "contents.json"

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

    private val _packsFlow = MutableStateFlow<List<StickerPack>>(emptyList())
    val packsFlow: StateFlow<List<StickerPack>> = _packsFlow.asStateFlow()

    init {
        loadPacks()
        if (_packsFlow.value.isEmpty()) {
            createSamplePacks()
        }
    }

    fun getPacks(): List<StickerPack> {
        return _packsFlow.value
    }

    fun getPack(packId: String): StickerPack? {
        return _packsFlow.value.find { it.id == packId }
    }

    fun getStickerFile(packId: String, fileName: String): File {
        val packDir = File(baseDir, packId)
        return File(packDir, fileName)
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
            val content = contentsFile.readText()
            val list = json.decodeFromString<List<StickerPack>>(content)
            _packsFlow.value = list
        } catch (e: Exception) {
            Log.e(TAG, "Error loading packs from $contentsFile", e)
            _packsFlow.value = emptyList()
        }
    }

    private fun persistPacks(packs: List<StickerPack>) {
        try {
            val content = json.encodeToString(packs)
            contentsFile.writeText(content)
            _packsFlow.value = packs
        } catch (e: Exception) {
            Log.e(TAG, "Error saving packs to $contentsFile", e)
        }
    }

    /**
     * Creates default sample packs so the user can immediately test WhatsApp transfer.
     */
    private fun createSamplePacks() {
        try {
            val samplePacks = mutableListOf<StickerPack>()

            // 1. "TikTok Viral Memes" (Static Pack)
            val pack1Id = "tiktok_viral_memes"
            val pack1Dir = File(baseDir, pack1Id).apply { mkdirs() }
            val pack1Tray = "tray_icon.png"
            createColoredIcon(File(pack1Dir, pack1Tray), 96, 96, "TT", Color.parseColor("#FE2C55"))

            val stickers1 = mutableListOf<Sticker>()
            val sampleCaptions = listOf(
                "POV 💀" to listOf("💀", "😭"),
                "Wait For It ⏳" to listOf("⏳", "👀"),
                "Real 💯" to listOf("💯", "🔥"),
                "Cap 🧢" to listOf("🧢", "🤨"),
                "Main Character ✨" to listOf("✨", "💅"),
                "Viral Moment 🚀" to listOf("🚀", "🤩")
            )

            sampleCaptions.forEachIndexed { index, (caption, emojis) ->
                val fileName = "sticker_${index + 1}.webp"
                val file = File(pack1Dir, fileName)
                createStickerBitmap(file, caption, Color.parseColor("#121212"), Color.parseColor("#FE2C55"))
                stickers1.add(Sticker(imageFileName = fileName, emojis = emojis, sizeBytes = file.length()))
            }

            val pack1 = StickerPack(
                id = pack1Id,
                name = "TikTok Viral Memes",
                publisher = "TikTok to WhatsApp",
                trayImageFile = pack1Tray,
                animatedStickerPack = false,
                stickers = stickers1
            )
            samplePacks.add(pack1)

            // 2. "TikTok Reactions" (Static Pack)
            val pack2Id = "tiktok_reactions"
            val pack2Dir = File(baseDir, pack2Id).apply { mkdirs() }
            val pack2Tray = "tray_icon.png"
            createColoredIcon(File(pack2Dir, pack2Tray), 96, 96, "RX", Color.parseColor("#25F4EE"))

            val stickers2 = mutableListOf<Sticker>()
            val reactions = listOf(
                "Side Eye 😒" to listOf("😒", "👀"),
                "Screaming 😱" to listOf("😱", "🚨"),
                "No Way 🤯" to listOf("🤯", "💥"),
                "Vibing 🎧" to listOf("🎧", "😎")
            )
            reactions.forEachIndexed { index, (caption, emojis) ->
                val fileName = "reaction_${index + 1}.webp"
                val file = File(pack2Dir, fileName)
                createStickerBitmap(file, caption, Color.parseColor("#121212"), Color.parseColor("#25F4EE"))
                stickers2.add(Sticker(imageFileName = fileName, emojis = emojis, sizeBytes = file.length()))
            }

            val pack2 = StickerPack(
                id = pack2Id,
                name = "TikTok Reactions",
                publisher = "TikTok to WhatsApp",
                trayImageFile = pack2Tray,
                animatedStickerPack = false,
                stickers = stickers2
            )
            samplePacks.add(pack2)

            persistPacks(samplePacks)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create sample packs", e)
        }
    }

    private fun createColoredIcon(file: File, width: Int, height: Int, text: String, bgColor: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        val radius = width / 4f
        canvas.drawRoundRect(4f, 4f, (width - 4).toFloat(), (height - 4).toFloat(), radius, radius, paint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = width * 0.45f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val yPos = (height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(text, width / 2f, yPos, textPaint)

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
    }

    private fun createStickerBitmap(file: File, caption: String, bgColor: Int, accentColor: Int) {
        // WhatsApp stickers must be exactly 512x512
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 16px transparent margin around sticker
        val contentPadding = 24f
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeWidth = 14f
        }

        val rectRadius = 60f
        canvas.drawRoundRect(
            contentPadding,
            contentPadding,
            size - contentPadding,
            size - contentPadding,
            rectRadius,
            rectRadius,
            cardPaint
        )
        canvas.drawRoundRect(
            contentPadding,
            contentPadding,
            size - contentPadding,
            size - contentPadding,
            rectRadius,
            rectRadius,
            borderPaint
        )

        // Watermark badge
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            textSize = 34f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("TikTok Sticker", size / 2f, 110f, badgePaint)

        // Caption text
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 52f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val yPos = (size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f) + 20f
        canvas.drawText(caption, size / 2f, yPos, textPaint)

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP, 90, out)
        }
        bitmap.recycle()
    }
}
