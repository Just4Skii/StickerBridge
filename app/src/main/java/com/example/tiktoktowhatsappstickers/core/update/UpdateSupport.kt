package com.example.tiktoktowhatsappstickers.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.example.tiktoktowhatsappstickers.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Mirrors update.json at the root of the repo. Publishing a higher versionCode there triggers updates. */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String = "",
    val changelog: List<String> = emptyList(),
    val mandatory: Boolean = false,
    val minSdk: Int = 24
)

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private val json = Json { ignoreUnknownKeys = true }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun parse(text: String): UpdateInfo? = try {
        json.decodeFromString<UpdateInfo>(text)
    } catch (e: Exception) {
        Log.w(TAG, "Bad update manifest", e)
        null
    }

    fun isNewer(info: UpdateInfo, installedVersionCode: Int, sdk: Int = Build.VERSION.SDK_INT): Boolean =
        info.versionCode > installedVersionCode && sdk >= info.minSdk

    /** Returns the published release if it is newer than what is installed, otherwise null. */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = BuildConfig.UPDATE_MANIFEST_URL + "?t=" + System.currentTimeMillis()
            client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val info = parse(res.body?.string().orEmpty()) ?: return@withContext null
                if (isNewer(info, BuildConfig.VERSION_CODE)) info else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Update check failed: ${e.message}")
            null
        }
    }
}

class ApkInstaller(private val context: Context) {

    sealed class InstallStart {
        object Launched : InstallStart()
        /** The user must allow "install unknown apps" for StickerBridge once, then tap Update again. */
        object NeedsPermission : InstallStart()
    }

    /** Downloads the APK to the app cache, reporting 0..100, and verifies its SHA-256 when published. */
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "StickerBridge-${info.versionCode}.apk")
        val req = Request.Builder().url(info.apkUrl).build()
        UpdateChecker.client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("Download failed: HTTP ${res.code}")
            val body = res.body ?: throw IllegalStateException("Empty download")
            val total = body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            body.byteStream().use { input ->
                out.outputStream().use { sink ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        if (total > 0) onProgress((read * 100 / total).toInt())
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256.isNotBlank() && !actual.equals(info.sha256, ignoreCase = true)) {
                out.delete()
                throw IllegalStateException("The downloaded update is corrupted (checksum mismatch). Try again.")
            }
        }
        onProgress(100)
        out
    }

    fun install(apk: File): InstallStart {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return InstallStart.NeedsPermission
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return InstallStart.Launched
    }
}
