package com.example.tiktoktowhatsappstickers.ui.main

import com.example.tiktoktowhatsappstickers.core.engine.WebpContainer
import com.example.tiktoktowhatsappstickers.core.engine.WebpProcessingEngine
import com.example.tiktoktowhatsappstickers.core.scanner.CandidateRules
import com.example.tiktoktowhatsappstickers.data.storage.StickerLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainScreenViewModelTest {
    @Test
    fun webpProcessingEngine_constants_areValidForWhatsApp() {
        assertEquals(512, WebpProcessingEngine.WHATSAPP_STICKER_SIZE)
        assertEquals(96, WebpProcessingEngine.WHATSAPP_TRAY_SIZE)
        assertEquals(100 * 1024, WebpProcessingEngine.MAX_STATIC_FILE_SIZE_BYTES)
        assertEquals(500 * 1024, WebpProcessingEngine.MAX_ANIMATED_FILE_SIZE_BYTES)
    }

    @Test
    fun nonExistentFile_failsInspection() {
        val result = WebpProcessingEngine.inspectFile(File("non_existent_file.webp"))
        assertFalse(result.isValid)
    }

    @Test
    fun stickerLibrary_idOf_isDeterministicAndUnique() {
        val sampleA = "tiktok_sticker_payload_1".toByteArray()
        val sampleB = "tiktok_sticker_payload_2".toByteArray()
        val idA1 = StickerLibrary.idOf(sampleA)
        val idA2 = StickerLibrary.idOf(sampleA)
        val idB = StickerLibrary.idOf(sampleB)

        assertEquals(32, idA1.length)
        assertEquals(idA1, idA2)
        assertNotEquals(idA1, idB)
    }

    @Test
    fun webpContainer_buildsAndParses512x512AnimatedWebp() {
        // Construct a minimal valid static WebP containing a VP8L chunk
        val vp8lPayload = byteArrayOf(0x2F, 0x00, 0x00, 0x00, 0x00)
        val riffSize = 4 + 8 + vp8lPayload.size + (vp8lPayload.size and 1)
        val staticWebp = ByteArray(8 + riffSize)
        "RIFF".toByteArray().copyInto(staticWebp, 0)
        staticWebp[4] = (riffSize and 0xFF).toByte()
        "WEBP".toByteArray().copyInto(staticWebp, 8)
        "VP8L".toByteArray().copyInto(staticWebp, 12)
        staticWebp[16] = vp8lPayload.size.toByte()
        vp8lPayload.copyInto(staticWebp, 20)

        val chunks = WebpContainer.extractImageChunks(staticWebp)
        assertTrue(chunks.isNotEmpty())

        val animated = WebpContainer.buildAnimated(
            width = 512,
            height = 512,
            frames = listOf(
                WebpContainer.EncodedFrame(chunks, durationMs = 80),
                WebpContainer.EncodedFrame(chunks, durationMs = 80)
            )
        )

        val parsed = WebpContainer.parse(animated)
        assertNotNull(parsed)
        assertEquals(512, parsed!!.canvasWidth)
        assertEquals(512, parsed.canvasHeight)
        assertTrue(parsed.isAnimated)
        assertEquals(2, parsed.frames.size)
    }

    @Test
    fun candidateRules_filtersOutNonStickerExtensionsAndKeepsCacheFiles() {
        assertTrue(CandidateRules.acceptFile("abc123def.cnt", 12_000))
        assertTrue(CandidateRules.acceptFile("sticker_01.webp", 24_000))
        assertFalse(CandidateRules.acceptFile("video.mp4", 50_000))
        assertFalse(CandidateRules.acceptFile("tiny.cnt", 100))
    }
}
