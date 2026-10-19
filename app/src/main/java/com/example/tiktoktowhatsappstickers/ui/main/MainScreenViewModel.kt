package com.example.tiktoktowhatsappstickers.ui.main

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tiktoktowhatsappstickers.core.ingestion.TikTokIngester
import com.example.tiktoktowhatsappstickers.core.scanner.Route
import com.example.tiktoktowhatsappstickers.core.scanner.TikTokCacheScanner
import com.example.tiktoktowhatsappstickers.core.scanner.TikTokSource
import com.example.tiktoktowhatsappstickers.core.shizuku.ShizukuBridge
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

/** What the user must do once before TikTok's stickers can be read on this phone. */
sealed class AccessPrompt {
    object StoragePermission : AccessPrompt()
    data class FolderGrant(val suggested: Uri) : AccessPrompt()
    data class Shizuku(val state: ShizukuBridge.State, val folderGrantAlsoPossible: Boolean, val suggested: Uri) : AccessPrompt()
}

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

    private val permissionListener = runCatching {
        ShizukuBridge.addPermissionListener { granted ->
            if (granted) scanTikTok() else refreshAccessPrompt()
        }
    }.getOrNull()

    private val binderListeners = runCatching { ShizukuBridge.addBinderListeners { refreshAccessPrompt() } }.getOrNull()

    init {
        viewModelScope.launch {
            storageManager.packsFlow.collect { packs ->
                val updated = packs.map { it.copy(isAddedToWhatsApp = whatsAppManager.isPackAdded(it.id)) }
                _uiState.update { it.copy(packs = updated) }
            }
        }
    }

    override fun onCleared() {
        permissionListener?.let { runCatching { ShizukuBridge.removePermissionListener(it) } }
        binderListeners?.let { runCatching { ShizukuBridge.removeBinderListeners(it) } }
        super.onCleared()
    }

    // ------------------------------------------------------------------ TikTok scan

    /** The one-tap button: finds whichever access route works on this phone and fetches the stickers. */
    fun scanTikTok() {
        if (_uiState.value.isImporting) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImporting = true, importProgressMessage = "Checking how to reach your TikTok stickers…",
                    errorMessage = null, infoMessage = null, accessPrompt = null
                )
            }
            when (val route = scanner.resolveRoute()) {
                is Route.Ready -> runScan(route.source)
                Route.NeedStoragePermission -> prompt(AccessPrompt.StoragePermission)
                Route.NeedFolderGrant -> prompt(AccessPrompt.FolderGrant(scanner.suggestedFolderUri()))
                is Route.NeedShizuku -> prompt(AccessPrompt.Shizuku(route.state, route.folderGrantAlsoPossible, scanner.suggestedFolderUri()))
            }
        }
    }

    private fun prompt(p: AccessPrompt) {
        _uiState.update { it.copy(isImporting = false, accessPrompt = p) }
    }

    private suspend fun runScan(source: TikTokSource) {
        val result = scanner.scan(source) { progress -> _uiState.update { it.copy(importProgressMessage = progress) } }
        val info = if (result.added > 0) {
            buildString {
                append("Added ${result.added} new sticker${if (result.added == 1) "" else "s"}")
                if (result.alreadyHad > 0) append(" (${result.alreadyHad} were already in your library)")
                if (result.unusable > 0) append(". ${result.unusable} couldn't be converted")
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

    /** Re-evaluates the open access dialog (e.g. after returning from Shizuku) without scanning. */
    fun refreshAccessPrompt() {
        val current = _uiState.value.accessPrompt as? AccessPrompt.Shizuku ?: return
        val state = ShizukuBridge.state(getApplication())
        _uiState.update { it.copy(accessPrompt = current.copy(state = state)) }
    }

    fun dismissAccessPrompt() = _uiState.update { it.copy(accessPrompt = null) }

    fun onFolderGranted(uri: Uri?) {
        if (uri == null) return
        scanner.saveFolderGrant(uri)
        scanTikTok()
    }

    fun onStoragePermissionResult(granted: Boolean) {
        if (granted) scanTikTok()
        else _uiState.update { it.copy(errorMessage = "Storage permission is needed to read TikTok's sticker cache on this Android version.") }
    }

    fun requestShizukuPermission() = ShizukuBridge.requestPermission()

    fun installedTikTok(): String? = scanner.installedTikTokPackage()

    // ------------------------------------------------------------------ link & gallery imports

    fun importTikTokUrl(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(isImporting = true, importProgressMessage = "Connecting to TikTok and extracting stickers…", errorMessage = null, infoMessage = null)
            }
            when (val result = tikTokIngester.ingestUrl(url)) {
                is TikTokIngester.IngestionResult.Success -> _uiState.update {
                    it.copy(
                        isImporting = false, selectedPack = result.pack,
                        infoMessage = if (result.alreadyHad) "You already have this pack, so nothing was duplicated." else null
                    )
                }
                is TikTokIngester.IngestionResult.Error -> _uiState.update {
                    it.copy(isImporting = false, errorMessage = result.message)
                }
            }
        }
    }

    fun importFromMediaUris(uris: List<Uri>, packName: String = "My TikTok Pack") {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(isImporting = true, importProgressMessage = "Converting ${uris.size} item(s) to WhatsApp stickers…", errorMessage = null, infoMessage = null)
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
                        it.copy(isImporting = false, errorMessage = "None of the selected items could be turned into stickers. Pick images, GIFs or WebP files (videos can't be used).")
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isImporting = false, selectedPack = result.packs.first(),
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

    // ------------------------------------------------------------------ packs

    fun selectPack(pack: StickerPack?) = _uiState.update { it.copy(selectedPack = pack) }

    fun deletePack(packId: String) {
        storageManager.deletePack(packId)
        if (_uiState.value.selectedPack?.id == packId) _uiState.update { it.copy(selectedPack = null) }
    }

    fun clearError() = _uiState.update { it.copy(errorMessage = null) }
    fun clearInfo() = _uiState.update { it.copy(infoMessage = null) }

    fun markAdded(packId: String) = storageManager.markPackAddedToWhatsApp(packId, true)

    // ------------------------------------------------------------------ updates

    /** Checks the published update.json. [manual] = the user tapped "check", so say "up to date" too. */
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
