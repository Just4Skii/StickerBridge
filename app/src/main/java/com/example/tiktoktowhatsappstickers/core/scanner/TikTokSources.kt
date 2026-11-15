package com.example.tiktoktowhatsappstickers.core.scanner

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * One file that might be a sticker.
 * [key] is TikTok's own cache key (the file name without extension).
 * [hintUrl] is the optional CDN URL extracted from TikTok's image_config directory.
 */
class Candidate(
    val key: String,
    val path: String,
    val size: Long,
    val hintUrl: String? = null,
    val read: () -> ByteArray?
)

/** A way of looking inside TikTok's storage on versions of Android that allow it natively. */
interface TikTokSource {
    val label: String
    fun candidates(): Sequence<Candidate>
}

private fun keyOf(name: String) = name.substringBeforeLast('.')

private fun extractHintUrl(bytes: ByteArray?): String? {
    if (bytes == null || bytes.isEmpty()) return null
    val text = String(bytes, Charsets.ISO_8859_1)
    val idx = text.indexOf("https://")
    if (idx < 0) return null
    val sb = StringBuilder()
    for (i in idx until minOf(text.length, idx + 512)) {
        val ch = text[i]
        if (ch.code < 32 || ch.code > 126 || ch == '\n' || ch == '\r') break
        sb.append(ch)
    }
    return sb.toString().takeIf { it.startsWith("https://") }
}

/** Plain file access. Works on Android 8-9, and any device where the OS permits direct access. */
class FileSystemSource : TikTokSource {
    override val label = "direct file access"

    private val roots: List<File> = CandidateRules.PACKAGES.flatMap { pkg ->
        CandidateRules.SUB_ROOTS.map { sub ->
            File(Environment.getExternalStorageDirectory(), "Android/data/$pkg/$sub")
        }
    }

    /** True only if the OS genuinely lets us list a TikTok folder (null listing = blocked). */
    fun isReadable(): Boolean = roots.any { r ->
        try {
            r.isDirectory && (r.list()?.isNotEmpty() == true)
        } catch (e: Exception) {
            false
        }
    }

    override fun candidates(): Sequence<Candidate> = sequence {
        for (root in roots) {
            if (!root.isDirectory) continue
            val all = CandidateRules.walk(root)
            val configUrls = HashMap<String, String>()
            val imageFiles = ArrayList<Pair<String, Long>>()
            for ((path, size) in all) {
                if (path.contains("image_config") && size in 40..4096) {
                    val url = extractHintUrl(runCatching { File(path).readBytes() }.getOrNull())
                    if (url != null) configUrls[keyOf(File(path).name)] = url
                } else {
                    imageFiles += path to size
                }
            }
            for ((path, size) in imageFiles) {
                val f = File(path)
                val key = keyOf(f.name)
                yield(Candidate(key, path, size, configUrls[key]) { runCatching { f.readBytes() }.getOrNull() })
            }
        }
    }
}

/** Storage Access Framework tree the user granted (works on Android 10-12 without any 3rd-party app). */
class SafSource(private val context: Context, private val tree: Uri) : TikTokSource {
    override val label = "granted TikTok folder"

    private data class Child(val docId: String, val name: String, val isDir: Boolean, val size: Long)

    private fun children(parentDocId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val out = ArrayList<Child>()
        try {
            context.contentResolver.query(
                uri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                ), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    out += Child(
                        c.getString(0), c.getString(1) ?: "",
                        c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        if (c.isNull(3)) 0L else c.getLong(3)
                    )
                }
            }
        } catch (e: Exception) {
            Log.d("SafSource", "list failed for $parentDocId: ${e.message}")
        }
        return out
    }

    /** Cheap probe: does the granted tree contain anything at all? */
    fun isReadable(): Boolean = children(DocumentsContract.getTreeDocumentId(tree)).isNotEmpty()

    override fun candidates(): Sequence<Candidate> = sequence {
        val stickerFirst = ArrayList<Candidate>()
        val rest = ArrayList<Candidate>()
        val configUrls = HashMap<String, String>()
        val stack = ArrayDeque<Pair<String, Int>>()
        stack.addLast(DocumentsContract.getTreeDocumentId(tree) to 0)
        while (stack.isNotEmpty() && stickerFirst.size + rest.size < 20_000) {
            val (docId, depth) = stack.removeLast()
            for (ch in children(docId)) {
                if (ch.isDir) {
                    if (depth < CandidateRules.MAX_DEPTH && !CandidateRules.skipDir(ch.name)) stack.addLast(ch.docId to depth + 1)
                } else if (ch.docId.contains("image_config") && ch.size in 40..4096) {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, ch.docId)
                    val bytes = runCatching { context.contentResolver.openInputStream(docUri)?.use { it.readBytes() } }.getOrNull()
                    extractHintUrl(bytes)?.let { configUrls[keyOf(ch.name)] = it }
                } else if (CandidateRules.acceptFile(ch.name, ch.size)) {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, ch.docId)
                    val key = keyOf(ch.name)
                    val cand = Candidate(key, ch.docId, ch.size, configUrls[key]) {
                        runCatching { context.contentResolver.openInputStream(docUri)?.use { it.readBytes() } }.getOrNull()
                    }
                    if (CandidateRules.isStickerPath(ch.docId)) stickerFirst += cand else rest += cand
                }
            }
        }
        yieldAll(stickerFirst)
        yieldAll(rest)
    }
}

/** Files the user picked or shared to the app explicitly (e.g. .cnt / WebP / GIF / PNG). */
class UriSource(private val context: Context, private val uris: List<Uri>) : TikTokSource {
    override val label = "selected files"

    override fun candidates(): Sequence<Candidate> = uris.asSequence().map { uri ->
        val rawName = uri.lastPathSegment?.substringAfterLast('/') ?: "picked"
        Candidate(keyOf(rawName), uri.toString(), 0L) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        }
    }
}
