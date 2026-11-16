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

/** Clean 1-click local scan route (zero folder browsing or 3rd-party apps). */
sealed class Route {
    data class Ready(val source: TikTokSource) : Route()
    data class NeedMediaPermission(val permission: String) : Route()
}

class TikTokCacheScanner(private val context: Context) {

    companion object {
        private const val TAG = "TikTokCacheScanner"
        private const val PREFS = "stickerbridge_prefs"
        private const val KEY_TREE = "tiktok_tree_uri"
        private const val BATCH = 40
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

    fun requiredMediaPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    suspend fun resolveRoute(): Route = withContext(Dispatchers.IO) {
        val sources = ArrayList<TikTokSource>()
        val direct = FileSystemSource()
        if (direct.isReadable()) sources += direct

        savedTree()?.let { tree ->
            val saf = SafSource(context, tree)
            if (saf.isReadable()) sources += saf
        }

        val perm = requiredMediaPermission()
        val mediaGranted = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        if (mediaGranted) {
            sources += MediaStoreStickerSource(context)
        } else if (sources.isEmpty()) {
            return@withContext Route.NeedMediaPermission(perm)
        }

        if (sources.size == 1) Route.Ready(sources.first())
        else Route.Ready(CompositeSource("device stickers & GIFs", sources))
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

        val isDeviceMedia = source !is UriSource && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        fun flush() {
            if (batch.isEmpty()) return
            val out = library.addToAutoPacks(batch.toList(), isDeviceMedia = isDeviceMedia, onProgress = onProgress)
            added += out.added
            already += out.alreadyHad
            unusable += out.unusable
            out.touchedPacks.forEach { touched[it.id] = it }
            batch.clear()
        }

        onProgress("Scanning ${source.label}…")
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
            examined == 0 -> "No stickers or GIFs saved in Downloads/Pictures yet. Use Pull Video Comments or DMs & Saved to grab directly from TikTok!"
            found == 0 -> "Checked $examined images — none were stickers/GIFs yet. Use Pull Video Comments or DMs & Saved to grab TikTok stickers."
            added == 0 && already > 0 -> "No new stickers found — all $already are already in your library."
            else -> null
        }
        ScanResult(examined, found, added, already, unusable, touched.values.toList(), source.label, message)
    }
}
