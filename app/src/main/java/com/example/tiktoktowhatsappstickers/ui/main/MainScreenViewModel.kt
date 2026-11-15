package com.example.tiktoktowhatsappstickers.ui.main

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tiktoktowhatsappstickers.core.ingestion.TikTokIngester
import com.example.tiktoktowhatsappstickers.core.scanner.Route
import com.example.tiktoktowhatsappstickers.core.scanner.TikTokCacheScanner
import com.example.tiktoktowhatsappstickers.core.scanner.TikTokSource
import com.example.tiktoktowhatsappstickers.core.scanner.UriSource
import com.example.tiktoktowhatsappstickers.core.update.ApkInstaller
import com.example.tiktoktowhatsappstickers.core.update.UpdateChecker
import com.example.tiktoktowhatsappstickers.core.update.UpdateInfo
import com.example.tiktoktowhatsappstickers.core.whatsapp.WhatsAppIntentManager
import com.example.tiktoktowhatsappstickers.data.model.StickerPack
import com.example.tiktoktowhatsappstickers.data.storage.StickerLibrary
import com.example.tiktoktowhatsappstickers.data.storage.StickerStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Built-in OS prompts (zero 3rd-party apps required). */
sealed class AccessPrompt {
    object StoragePermission : AccessPrompt()
    data class FolderGrant(val suggested: Uri) : AccessPrompt()
    object Android13FilesHelper : AccessPrompt()
}

data class GrabberSession(
    val url: String,
    val title: String
)

sealed class UpdateUi {
    object None : UpdateUi()
    data class Available(val info: UpdateInfo) : UpdateUi()
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateUi()
    data class Ready(val info: UpdateInfo, val apk: File) : UpdateUi()
    data class Error(val message: String, val info: UpdateInfo?) : UpdateUi()
}

data class MainUiState(
    val packs: List<StickerPack> = emptyList(),
    val isImporting: Boolean = false,
    val importProgressMessage: String = "",
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val selectedPack: StickerPack? = null,
    val isWhatsAppInstalled: Boolean = true,
    val accessPrompt: AccessPrompt? = null,
    val grabberSession: GrabberSession? = null,
    val update: UpdateUi = UpdateUi.None,
    val updateCheckMessage: String? = null
)

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val storageManager = StickerStorageManager.getInstance(application)
    private val library = StickerLibrary(application)
    private val tikTokIngester = TikTokIngester(application)
    private val whatsAppManager = WhatsAppIntentManager(application)
    private val scanner = TikTokCacheScanner(application)
    private val installer = ApkInstaller(application)

    private val _uiState = MutableStateFlow(MainUiState(isWhatsAppInstalled = whatsAppManager.isWhatsAppInstalled()))
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            storageManager.packsFlow.collect { packs ->
                val updated = packs.map { it.copy(isAddedToWhatsApp = whatsAppManager.isPackAdded(it.id)) }
                _uiState.update { it.copy(packs = updated) }
            }
        }
    }

    // ------------------------------------------------------------------ TikTok Video Comment & Live Grabber

    fun openVideoCommentsGrabber(rawInput: String) {
        val url = extractFirstUrl(rawInput)
        if (url == null) {
            _uiState.update { it.copy(errorMessage = "Paste a valid TikTok video link (https://...).") }
            return
        }
        val low = url.lowercase()
        // If someone pasted a direct CDN image/sticker URL, download & normalize it straight away.
        if (low.endsWith(".webp") || low.endsWith(".png") || low.endsWith(".gif") || ".awebp" in low || "ibyteimg.com" in low) {
            importDirectUrl(url)
            return
        }
        _uiState.update {
            it.copy(
                errorMessage = null,
                infoMessage = null,
                grabberSession = GrabberSession(url = url, title = "Video Comment Stickers")
            )
        }
    }

    fun openTikTokLiveBrowser() {
        _uiState.update {
            it.copy(
                errorMessage = null,
                infoMessage = null,
                grabberSession = GrabberSession(
                    url = "https://www.tiktok.com/messages",
                    title = "TikTok Live Grabber (DMs & Comments)"
                )
            )
        }
    }

    fun closeGrabber() {
        _uiState.update { it.copy(grabberSession = null) }
    }

    fun importCapturedStickers(packName: String, raws: List<StickerLibrary.Raw>) {
        _uiState.update {
            it.copy(
                grabberSession = null,
                isImporting = true,
                importProgressMessage = "Converting ${raws.size} captured sticker(s) to 512×512…"
            )
        }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    library.createManualPack(packName, raws)
                }
                if (result.packs.isEmpty()) {
                    _uiState.update {
                        it.copy(isImporting = false, errorMessage = "None of the captured items could be converted into WhatsApp stickers.")
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            selectedPack = result.packs.first(),
                            infoMessage = if (result.alreadyHad) {
                                "You already have these exact stickers — no duplicates created."
                            } else {
                                "Saved ${result.packs.sumOf { p -> p.realStickerCount }} unique 512×512 sticker(s)!"
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Captured import failed", e)
                _uiState.update { it.copy(isImporting = false, errorMessage = "Failed to save stickers: ${e.message}") }
            }
        }
    }

    private fun extractFirstUrl(text: String): String? {
        val match = Regex("https?://\\S+").find(text.trim()) ?: return null
        return match.value.trimEnd('.', ',', ')', ']')
    }

    private fun importDirectUrl(url: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isImporting = true, importProgressMessage = "Downloading & sizing sticker to 512×512…", errorMessage = null, infoMessage = null)
            }
            when (val result = tikTokIngester.ingestUrl(url)) {
                is TikTokIngester.IngestionResult.Success -> _uiState.update {
                    it.copy(
                        isImporting = false,
                        selectedPack = result.pack,
                        infoMessage = if (result.alreadyHad) "You already have this sticker, so nothing was duplicated." else null
                    )
                }
                is TikTokIngester.IngestionResult.Error -> _uiState.update {
                    it.copy(isImporting = false, errorMessage = result.message)
                }
            }
        }
    }

    // ------------------------------------------------------------------ Native TikTok Cache & Files Scan (Zero 3rd-party apps)

    fun scanTikTokCache() {
        if (_uiState.value.isImporting) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImporting = true,
                    importProgressMessage = "Checking TikTok sticker folders…",
                    errorMessage = null,
                    infoMessage = null,
                    accessPrompt = null
                )
            }
            when (val route = scanner.resolveRoute()) {
                is Route.Ready -> runScan(route.source)
                Route.NeedStoragePermission -> _uiState.update {
                    it.copy(isImporting = false, accessPrompt = AccessPrompt.StoragePermission)
                }
                Route.NeedFolderGrant -> _uiState.update {
                    it.copy(isImporting = false, accessPrompt = AccessPrompt.FolderGrant(scanner.suggestedFolderUri()))
                }
                Route.Android13Restricted -> _uiState.update {
                    it.copy(isImporting = false, accessPrompt = AccessPrompt.Android13FilesHelper)
                }
            }
        }
    }

    private suspend fun runScan(source: TikTokSource) {
        val result = scanner.scan(source) { progress -> _uiState.update { it.copy(importProgressMessage = progress) } }
        val info = if (result.added > 0) {
            buildString {
                append("Added ${result.added} new sticker${if (result.added == 1) "" else "s"}")
                if (result.alreadyHad > 0) append(" (${result.alreadyHad} skipped as duplicates)")
                if (result.unusable > 0) append(". ${result.unusable} skipped")
            }
        } else null
        _uiState.update {
            it.copy(
                isImporting = false,
                selectedPack = result.packs.firstOrNull() ?: it.selectedPack,
                errorMessage = result.message.takeIf { m -> m != null && result.added == 0 && result.alreadyHad == 0 },
                infoMessage = info ?: result.message.takeIf { result.added == 0 && result.alreadyHad > 0 }
            )
        }
    }

    fun scanSelectedFiles(uris: List<Uri>) {
        if (uris.isEmpty() || _uiState.value.isImporting) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(isImporting = true, importProgressMessage = "Scanning ${uris.size} selected file(s)…", errorMessage = null, infoMessage = null)
            }
            runScan(UriSource(getApplication(), uris))
        }
    }

    fun systemFilesIntent(): Intent = scanner.systemFilesIntent()

    fun dismissAccessPrompt() = _uiState.update { it.copy(accessPrompt = null) }

    fun onFolderGranted(uri: Uri?) {
        if (uri == null) return
        scanner.saveFolderGrant(uri)
        scanTikTokCache()
    }

    fun onStoragePermissionResult(granted: Boolean) {
        if (granted) scanTikTokCache()
        else _uiState.update { it.copy(errorMessage = "Storage permission is needed to read TikTok's sticker cache on this Android version.") }
    }

    // ------------------------------------------------------------------ Gallery / Share Import

    fun importFromMediaUris(uris: List<Uri>, packName: String = "My Sticker Pack") {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(isImporting = true, importProgressMessage = "Converting ${uris.size} item(s) to 512×512 stickers…", errorMessage = null, infoMessage = null)
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    val ctx = getApplication<Application>()
                    val raws = uris.take(StickerLibrary.MAX_PER_PACK).mapNotNull { uri ->
                        val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                        bytes?.let { StickerLibrary.Raw(uri.lastPathSegment?.substringAfterLast('/') ?: "picked", it) }
                    }
                    library.createManualPack(packName, raws)
                }
                if (result.packs.isEmpty()) {
                    _uiState.update {
                        it.copy(isImporting = false, errorMessage = "None of the selected items could be turned into stickers. Pick PNG, WebP, GIF, or .cnt sticker files.")
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            selectedPack = result.packs.first(),
                            infoMessage = if (result.alreadyHad) "You already have this pack, so nothing was duplicated." else null
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Media import error", e)
                _uiState.update { it.copy(isImporting = false, errorMessage = "Import failed: ${e.message}") }
            }
        }
    }

    // ------------------------------------------------------------------ Packs

    fun selectPack(pack: StickerPack?) = _uiState.update { it.copy(selectedPack = pack) }

    fun deletePack(packId: String) {
        storageManager.deletePack(packId)
        if (_uiState.value.selectedPack?.id == packId) _uiState.update { it.copy(selectedPack = null) }
    }

    fun clearError() = _uiState.update { it.copy(errorMessage = null) }
    fun clearInfo() = _uiState.update { it.copy(infoMessage = null) }

    fun markAdded(packId: String) = storageManager.markPackAddedToWhatsApp(packId, true)

    // ------------------------------------------------------------------ OTA Updates

    fun checkForUpdate(manual: Boolean = false) {
        if (_uiState.value.update is UpdateUi.Downloading) return
        viewModelScope.launch {
            val info = UpdateChecker.checkForUpdate()
            _uiState.update {
                when {
                    info != null -> it.copy(update = UpdateUi.Available(info), updateCheckMessage = null)
                    manual -> it.copy(updateCheckMessage = "You're on the latest version.")
                    else -> it
                }
            }
        }
    }

    fun startUpdate() {
        val info = when (val u = _uiState.value.update) {
            is UpdateUi.Available -> u.info
            is UpdateUi.Error -> u.info
            is UpdateUi.Ready -> { installReady(u); return }
            else -> null
        } ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(update = UpdateUi.Downloading(info, 0)) }
            try {
                val apk = installer.download(info) { pct ->
                    _uiState.update { s -> s.copy(update = UpdateUi.Downloading(info, pct)) }
                }
                val ready = UpdateUi.Ready(info, apk)
                _uiState.update { it.copy(update = ready) }
                installReady(ready)
            } catch (e: Exception) {
                _uiState.update { it.copy(update = UpdateUi.Error(e.message ?: "Update download failed", info)) }
            }
        }
    }

    private fun installReady(ready: UpdateUi.Ready) {
        installer.install(ready.apk)
    }

    fun dismissUpdateMessage() = _uiState.update { it.copy(updateCheckMessage = null) }
}
