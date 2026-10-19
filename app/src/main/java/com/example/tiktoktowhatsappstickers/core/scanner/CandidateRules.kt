package com.example.tiktoktowhatsappstickers.core.scanner

import java.io.File

/**
 * Shared, Android-free rules for deciding which files inside TikTok's storage are worth reading.
 * Used by the normal file reader and by the Shizuku service process, so both look at the same things.
 */
object CandidateRules {

    const val MIN_BYTES = 2_000L
    const val MAX_BYTES = 4_000_000L
    const val MAX_DEPTH = 12

    /** TikTok builds worldwide: international, Asia-Pacific ("trill") and Lite. */
    val PACKAGES = listOf(
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.zhiliaoapp.musically.go"
    )

    /** Where TikTok keeps image caches (Fresco writes raw image bytes as <key>.cnt). */
    val SUB_ROOTS = listOf("cache", "files")

    private val SKIP_EXT = setOf(
        "mp4", "m4a", "mp3", "aac", "wav", "ogg", "webm", "mov", "m3u8", "ts",
        "json", "db", "log", "xml", "so", "dex", "jar", "apk", "zip", "txt", "pb", "ttf", "otf",
        "tmp", "lock", "journal", "idx", "index", "sqlite", "html", "js", "css"
    )

    private val SKIP_DIR_HINTS = listOf(
        "video", "exo", "webview", "code_cache", "okhttp", "log", "crash", "so_", "lib", "plugin",
        "audio", "music", "mmkv", "sp_", "databases", "shared_prefs"
    )

    fun androidDataRoot(storageRoot: String = "/storage/emulated/0") = "$storageRoot/Android/data"

    fun skipDir(name: String): Boolean {
        val n = name.lowercase()
        if ("sticker" in n) return false // never skip anything that says sticker
        return SKIP_DIR_HINTS.any { it in n }
    }

    fun acceptFile(name: String, size: Long): Boolean {
        if (size < MIN_BYTES || size > MAX_BYTES) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext !in SKIP_EXT
    }

    fun isStickerPath(path: String) = "sticker" in path.lowercase()

    /** Walks [root] and returns "size|path" lines for plausible candidates, sticker folders first. */
    fun walk(root: File, maxFiles: Int = 20_000): List<Pair<String, Long>> {
        val sticker = ArrayList<Pair<String, Long>>()
        val other = ArrayList<Pair<String, Long>>()
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty() && sticker.size + other.size < maxFiles) {
            val (dir, depth) = stack.removeLast()
            val children = try {
                dir.listFiles()
            } catch (e: Exception) {
                null
            } ?: continue
            for (c in children) {
                if (c.isDirectory) {
                    if (depth < MAX_DEPTH && !skipDir(c.name)) stack.addLast(c to depth + 1)
                } else {
                    val len = c.length()
                    if (acceptFile(c.name, len)) {
                        (if (isStickerPath(c.path)) sticker else other).add(c.path to len)
                    }
                }
            }
        }
        return sticker + other
    }
}
