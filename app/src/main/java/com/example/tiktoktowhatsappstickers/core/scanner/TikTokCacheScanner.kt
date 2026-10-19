package com.example.tiktoktowhatsappstickers.core.scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.tiktoktowhatsappstickers.core.shizuku.ShizukuBridge
import com.example.tiktoktowhatsappstickers.data.model.StickerPack
import com.example.tiktoktowhatsappstickers.data.storage.StickerLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the app has to do before it can read TikTok's stickers on this particular phone. */
sealed class Route {
    data class Ready(val source: TikTokSource) : Route()
    /** Android 8-9: ask for the normal storage permission. */
    object NeedStoragePermission : Route()
    /** Android 10-12: user grants TikTok's folder once through the system picker. */
    object NeedFolderGrant : Route()
    /** Android 11+: one-time Shizuku setup (the only no-root route on Android 13+). */
    data class NeedShizuku(val state: ShizukuBridge.State, val folderGrantAlsoPossible: Boolean) : Route()
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

        val shizuku = ShizukuBridge.state(context)
        if (shizuku == ShizukuBridge.State.READY) {
            ShizukuBridge.connect()?.let { return@withContext Route.Ready(ShizukuSource(it)) }
        }

        when {
            Build.VERSION.SDK_INT <= 28 -> {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) Route.Ready(direct) else Route.NeedStoragePermission
            }
            Build.VERSION.SDK_INT <= 32 -> {
                // Shizuku is a valid alternative here, but the folder grant is the easier first try.
                if (savedTree() == null) Route.NeedFolderGrant
                else Route.NeedShizuku(shizuku, folderGrantAlsoPossible = true)
            }
            else -> Route.NeedShizuku(shizuku, folderGrantAlsoPossible = false)
        }
    }

    /** Where the folder picker should open: straight at TikTok's folder. */
    fun suggestedFolderUri(): Uri {
        val pkg = installedTikTokPackage() ?: CandidateRules.PACKAGES.first()
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:Android/data/$pkg")
    }

    fun saveFolderGrant(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist folder grant", e)
        }
        prefs.edit().putString(KEY_TREE, uri.toString()).apply()
    }

    fun clearFolderGrant() = prefs.edit().remove(KEY_TREE).apply()

    private fun savedTree(): Uri? {
        val s = prefs.getString(KEY_TREE, null) ?: return null
        val uri = Uri.parse(s)
        val still = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        return if (still) uri else null
    }

    // ------------------------------------------------------------------ scanning

    /**
     * Reads every candidate file in [source], keeps only genuine stickers, and merges them into the
     * library. Stickers already stored (by content id) are skipped, so scanning twice never duplicates.
     */
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
                if (!StickerClassifier.classify(bytes, c.path).accept) continue
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
            examined == 0 -> "Couldn't see any TikTok files via ${source.label}. Is TikTok installed and logged in on this phone?"
            found == 0 -> "Checked $examined files in TikTok's cache but none were stickers. TikTok only keeps stickers it has " +
                "shown you: open TikTok → a chat → the sticker panel → ⭐ Favorites, scroll through them once, then scan again."
            added == 0 && already > 0 -> "No new stickers. All $already are already in your library."
            else -> null
        }
        ScanResult(examined, found, added, already, unusable, touched.values.toList(), source.label, message)
    }
}
