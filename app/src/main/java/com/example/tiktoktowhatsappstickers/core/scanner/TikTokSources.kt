package com.example.tiktoktowhatsappstickers.core.scanner

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import com.example.tiktoktowhatsappstickers.core.shizuku.IFileService
import java.io.File

/** One file that might be a sticker. [key] is TikTok's own cache key (the file name without extension). */
class Candidate(val key: String, val path: String, val size: Long, val read: () -> ByteArray?)

/** A way of looking inside TikTok's storage. Which one works depends on the Android version. */
interface TikTokSource {
    val label: String
    fun candidates(): Sequence<Candidate>
}

private fun keyOf(name: String) = name.substringBeforeLast('.')

/** Plain file access. Works on Android 8-9, and anywhere the OS happens to allow it. */
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
            for ((path, size) in CandidateRules.walk(root)) {
                val f = File(path)
                yield(Candidate(keyOf(f.name), path, size) { runCatching { f.readBytes() }.getOrNull() })
            }
        }
    }
}

/** Storage Access Framework tree the user granted (Android 10-12 can still grant Android/data/<pkg>). */
class SafSource(private val context: Context, private val tree: Uri) : TikTokSource {
    override val label = "granted folder"

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
        val stack = ArrayDeque<Pair<String, Int>>()
        stack.addLast(DocumentsContract.getTreeDocumentId(tree) to 0)
        while (stack.isNotEmpty() && stickerFirst.size + rest.size < 20_000) {
            val (docId, depth) = stack.removeLast()
            for (ch in children(docId)) {
                if (ch.isDir) {
                    if (depth < CandidateRules.MAX_DEPTH && !CandidateRules.skipDir(ch.name)) stack.addLast(ch.docId to depth + 1)
                } else if (CandidateRules.acceptFile(ch.name, ch.size)) {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, ch.docId)
                    val cand = Candidate(keyOf(ch.name), ch.docId, ch.size) {
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

/** Privileged reads through Shizuku (Android 11+ without root). */
class ShizukuSource(private val service: IFileService) : TikTokSource {
    override val label = "Shizuku"

    override fun candidates(): Sequence<Candidate> = sequence {
        val dataRoot = CandidateRules.androidDataRoot()
        for (pkg in CandidateRules.PACKAGES) {
            for (sub in CandidateRules.SUB_ROOTS) {
                val root = "$dataRoot/$pkg/$sub"
                if (!runCatching { service.exists(root) }.getOrDefault(false)) continue
                var skip = 0
                while (true) {
                    val page = runCatching { service.listPage(root, skip, 400) }.getOrNull() ?: break
                    if (page.isEmpty()) break
                    for (line in page) {
                        val size = line.substringBefore('|').toLongOrNull() ?: continue
                        val path = line.substringAfter('|')
                        yield(Candidate(keyOf(path.substringAfterLast('/')), path, size) {
                            runCatching {
                                service.open(path).let { pfd ->
                                    android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
                                }
                            }.getOrNull()
                        })
                    }
                    skip += page.size
                }
            }
        }
    }
}

/** Files the user picked or shared to the app explicitly. */
class UriSource(private val context: Context, private val uris: List<Uri>) : TikTokSource {
    override val label = "selected files"

    override fun candidates(): Sequence<Candidate> = uris.asSequence().map { uri ->
        Candidate(uri.lastPathSegment?.substringAfterLast('/') ?: "picked", uri.toString(), 0L) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        }
    }
}
