package com.example.tiktoktowhatsappstickers.ui.main

import com.example.tiktoktowhatsappstickers.core.engine.WebpProcessingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
