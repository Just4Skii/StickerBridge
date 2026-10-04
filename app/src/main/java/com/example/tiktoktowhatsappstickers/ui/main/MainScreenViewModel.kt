package com.example.tiktoktowhatsappstickers.ui.main

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tiktoktowhatsappstickers.core.engine.WebpProcessingEngine
import com.example.tiktoktowhatsappstickers.core.ingestion.TikTokIngester
import com.example.tiktoktowhatsappstickers.core.whatsapp.WhatsAppIntentManager
import com.example.tiktoktowhatsappstickers.data.model.Sticker
import com.example.tiktoktowhatsappstickers.data.model.StickerPack
import com.example.tiktoktowhatsappstickers.data.storage.StickerStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class MainUiState(
    val packs: List<StickerPack> = emptyList(),
    val isImporting: Boolean = false,
    val importProgressMessage: String = "",
    val errorMessage: String? = null,
    val selectedPack: StickerPack? = null,
    val isWhatsAppInstalled: Boolean = true
)

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val storageManager = StickerStorageManager.getInstance(application)
    private val tikTokIngester = TikTokIngester(application)
    private val whatsAppManager = WhatsAppIntentManager(application)
    private val cacheScanner = com.example.tiktoktowhatsappstickers.core.scanner.TikTokCacheScanner(application)

    private val _uiState = MutableStateFlow(
        MainUiState(
            isWhatsAppInstalled = whatsAppManager.isWhatsAppInstalled()
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun isDirectCacheAvailable(): Boolean = cacheScanner.isDirectCacheAccessible()

    init {
        viewModelScope.launch {
            storageManager.packsFlow.collect { packs ->
                // Check if any packs are already added to WhatsApp
                val updatedPacks = packs.map { pack ->
                    val isAdded = whatsAppManager.isPackAdded(pack.id)
                    pack.copy(isAddedToWhatsApp = isAdded)
                }
                _uiState.update { it.copy(packs = updatedPacks) }
            }
        }
    }

    fun importTikTokUrl(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImporting = true,
                    importProgressMessage = "Connecting to TikTok and extracting stickers...",
                    errorMessage = null
                )
            }
            when (val result = tikTokIngester.ingestUrl(url)) {
                is TikTokIngester.IngestionResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            selectedPack = result.pack,
                            errorMessage = null
                        )
                    }
                }
                is TikTokIngester.IngestionResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            errorMessage = result.message
                        )
                    }
                }
            }
        }
    }

    fun importFromMediaUris(uris: List<Uri>, packName: String = "My TikTok Pack") {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImporting = true,
                    importProgressMessage = "Converting ${uris.size} media items to WhatsApp WebP...",
                    errorMessage = null
                )
            }
            try {
                withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    val packId = "pack_" + UUID.randomUUID().toString().substring(0, 8)
                    val packDir = File(context.filesDir, "sticker_packs/$packId").apply { mkdirs() }
                    val stickers = mutableListOf<Sticker>()

                    uris.take(30).forEachIndexed { index, uri ->
                        val inputStream = context.contentResolver.openInputStream(uri)
                        if (inputStream != null) {
                            val fileName = "sticker_${index + 1}.webp"
                            val file = File(packDir, fileName)
                            val ok = WebpProcessingEngine.processStaticSticker(inputStream, file)
                            if (ok && file.exists()) {
                                stickers.add(
                                    Sticker(
                                        imageFileName = fileName,
                                        emojis = listOf("✨"),
                                        sizeBytes = file.length()
                                    )
                                )
                            }
                        }
                    }

                    // Pad to 3 stickers if user selected 1 or 2
                    while (stickers.isNotEmpty() && stickers.size < 3) {
                        val duplicateIndex = stickers.size + 1
                        val original = stickers.first()
                        val dupFileName = "sticker_$duplicateIndex.webp"
                        val dupFile = File(packDir, dupFileName)
                        File(packDir, original.imageFileName).copyTo(dupFile, overwrite = true)
                        stickers.add(
                            Sticker(
                                imageFileName = dupFileName,
                                emojis = original.emojis,
                                sizeBytes = dupFile.length()
                            )
                        )
                    }

                    if (stickers.size < 3) {
                        _uiState.update {
                            it.copy(
                                isImporting = false,
                                errorMessage = "Could not convert media. WhatsApp requires at least 3 stickers."
                            )
                        }
                        return@withContext
                    }

                    val trayFile = File(packDir, "tray_icon.png")
                    WebpProcessingEngine.createTrayIcon(File(packDir, stickers.first().imageFileName), trayFile)

                    val pack = StickerPack(
                        id = packId,
                        name = packName,
                        publisher = "TikTok to WhatsApp",
                        trayImageFile = "tray_icon.png",
                        animatedStickerPack = false,
                        stickers = stickers
                    )
                    storageManager.savePack(pack)

                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            selectedPack = pack,
                            errorMessage = null
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Media import error", e)
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        errorMessage = "Import failed: ${e.message}"
                    )
                }
            }
        }
    }

    fun selectPack(pack: StickerPack?) {
        _uiState.update { it.copy(selectedPack = pack) }
    }

    fun deletePack(packId: String) {
        storageManager.deletePack(packId)
        if (_uiState.value.selectedPack?.id == packId) {
            _uiState.update { it.copy(selectedPack = null) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun markAdded(packId: String) {
        storageManager.markPackAddedToWhatsApp(packId, true)
    }

    fun scanDeviceMediaAutomatically() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImporting = true,
                    importProgressMessage = "Scanning phone for TikTok stickers & media...",
                    errorMessage = null
                )
            }
            val result = cacheScanner.scanDeviceMediaAutomatically { progress ->
                _uiState.update { it.copy(importProgressMessage = progress) }
            }
            _uiState.update {
                it.copy(
                    isImporting = false,
                    selectedPack = result.packsCreated.firstOrNull(),
                    errorMessage = result.errorMessage
                )
            }
        }
    }

    fun scanDirectCache() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImporting = true,
                    importProgressMessage = "Scanning local TikTok cache...",
                    errorMessage = null
                )
            }
            val result = cacheScanner.scanDirectCache { progress ->
                _uiState.update { it.copy(importProgressMessage = progress) }
            }
            _uiState.update {
                it.copy(
                    isImporting = false,
                    selectedPack = result.packsCreated.firstOrNull(),
                    errorMessage = result.errorMessage
                )
            }
        }
    }

    fun scanDocumentTree(uri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImporting = true,
                    importProgressMessage = "Scanning selected TikTok directory...",
                    errorMessage = null
                )
            }
            val result = cacheScanner.scanDocumentTree(uri) { progress ->
                _uiState.update { it.copy(importProgressMessage = progress) }
            }
            _uiState.update {
                it.copy(
                    isImporting = false,
                    selectedPack = result.packsCreated.firstOrNull(),
                    errorMessage = result.errorMessage
                )
            }
        }
    }

    fun scanSelectedCacheFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImporting = true,
                    importProgressMessage = "Processing ${uris.size} selected TikTok cache files...",
                    errorMessage = null
                )
            }
            val result = cacheScanner.scanSelectedUris(uris) { progress ->
                _uiState.update { it.copy(importProgressMessage = progress) }
            }
            _uiState.update {
                it.copy(
                    isImporting = false,
                    selectedPack = result.packsCreated.firstOrNull(),
                    errorMessage = result.errorMessage
                )
            }
        }
    }
}
