package com.example.tiktoktowhatsappstickers.core.scanner

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.tiktoktowhatsappstickers.data.model.StickerPack
import com.example.tiktoktowhatsappstickers.data.storage.StickerLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the app can use to read TikTok's local cache without any 3rd-party app. */
sealed class Route {
    data class Ready(val source: TikTokSource) : Route()
    /** Android 8-9: standard storage permission. */
    object NeedStoragePermission : Route()
    /** Android 10-12: built-in system folder grant on TikTok's cache folder. */
    object NeedFolderGrant : Route()
    /**
     * Android 13+: Android locks other apps out of /Android/data unless accessed via the built-in
     * Android Files app or via StickerBridge's live in-app TikTok Grabber.
     */
    object Android13Restricted : Route()
}

class TikTokCacheScanner(private val context: Context) {

    companion object {
        private const val TAG = "TikTokCacheScanner"
        private const val PREFS = "stickerbridge_prefs"
        private const val KEY_TREE = "tiktok_tree_uri"
        private const val BATCH = 40
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }

    data class ScanResult(
        val filesExamined: Int,
        val stickersFound: Int,
        val added: Int,
        val alreadyHad: Int,
        val unusable: Int,
        val packs: List<StickerPack>,
        val via: String,
        val message: String?
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val library = StickerLibrary(context)

    fun installedTikTokPackage(): String? = CandidateRules.PACKAGES.firstOrNull {
        try {
            context.packageManager.getPackageInfo(it, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    // ------------------------------------------------------------------ access

    suspend fun resolveRoute(): Route = withContext(Dispatchers.IO) {
        val direct = FileSystemSource()
        if (direct.isReadable()) return@withContext Route.Ready(direct)

        savedTree()?.let { tree ->
            val saf = SafSource(context, tree)
            if (saf.isReadable()) return@withContext Route.Ready(saf)
        }

        when {
            Build.VERSION.SDK_INT <= 28 -> {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) Route.Ready(direct) else Route.NeedStoragePermission
            }
            Build.VERSION.SDK_INT <= 32 -> Route.NeedFolderGrant
            else -> Route.Android13Restricted
        }
    }

    /** Deep URI directly inside TikTok's sticker cache folder. */
    fun suggestedFolderUri(): Uri {
        val pkg = installedTikTokPackage() ?: CandidateRules.PACKAGES.first()
        return DocumentsContract.buildDocumentUri(
            EXTERNAL_STORAGE_AUTHORITY,
            "primary:Android/data/$pkg/cache/picture/fresco_custom_cache"
        )
    }

    /**
     * Opens Android's built-in System Files app (`com.google.android.documentsui`) straight at TikTok's
     * sticker cache folder so users on Android 13+ can select & Share `.cnt` files to StickerBridge
     * without installing any 3rd-party app.
     */
    fun systemFilesIntent(): Intent {
        val pkg = installedTikTokPackage() ?: CandidateRules.PACKAGES.first()
        val folderUri = DocumentsContract.buildDocumentUri(
            EXTERNAL_STORAGE_AUTHORITY,
            "primary:Android/data/$pkg/cache/picture/fresco_custom_cache"
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(folderUri, "vnd.android.document/directory")
            component = ComponentName(
                "com.google.android.documentsui",
                "com.android.documentsui.files.FilesActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun saveFolderGrant(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist folder grant", e)
        }
        prefs.edit().putString(KEY_TREE, uri.toString()).apply()
    }

    private fun savedTree(): Uri? {
        val s = prefs.getString(KEY_TREE, null) ?: return null
        val uri = Uri.parse(s)
        val still = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        return if (still) uri else null
    }

    // ------------------------------------------------------------------ scanning

    suspend fun scan(source: TikTokSource, onProgress: (String) -> Unit): ScanResult = withContext(Dispatchers.IO) {
        var examined = 0
        var found = 0
        var added = 0
        var already = 0
        var unusable = 0
        val touched = LinkedHashMap<String, StickerPack>()
        val batch = ArrayList<StickerLibrary.Raw>()

        fun flush() {
            if (batch.isEmpty()) return
            val out = library.addToAutoPacks(batch.toList(), onProgress)
            added += out.added
            already += out.alreadyHad
            unusable += out.unusable
            out.touchedPacks.forEach { touched[it.id] = it }
            batch.clear()
        }

        onProgress("Looking inside TikTok's storage (${source.label})…")
        try {
            for (c in source.candidates()) {
                examined++
                if (examined % 25 == 0) onProgress("Checked $examined files, found $found stickers…")
                val bytes = c.read() ?: continue
                if (!StickerClassifier.classify(bytes, c.path, c.hintUrl).accept) continue
                found++
                batch += StickerLibrary.Raw(c.key, bytes)
                if (batch.size >= BATCH) flush()
            }
            flush()
        } catch (e: Exception) {
            Log.e(TAG, "Scan failed", e)
            return@withContext ScanResult(examined, found, added, already, unusable, touched.values.toList(), source.label, "Scan failed: ${e.message}")
        }

        val message = when {
            examined == 0 -> "Couldn't read files via ${source.label}. Use the Live TikTok Grabber tab to capture comment or chat stickers directly."
            found == 0 -> "Checked $examined files but none were stickers. View or favorite your stickers in TikTok first, then try again."
            added == 0 && already > 0 -> "No new stickers found — all $already are already in your library."
            else -> null
        }
        ScanResult(examined, found, added, already, unusable, touched.values.toList(), source.label, message)
    }
}
