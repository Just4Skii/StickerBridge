package com.example.tiktoktowhatsappstickers.core.shizuku

import android.os.ParcelFileDescriptor
import com.example.tiktoktowhatsappstickers.core.scanner.CandidateRules
import java.io.File
import kotlin.system.exitProcess

/**
 * Executes inside the Shizuku server's process with shell (or root) privileges. Shell is allowed to read
 * /sdcard/Android/data/<pkg>, which a normal app cannot do on Android 11+.
 *
 * Shizuku instantiates this class reflectively, so it needs a public no-arg constructor.
 */
class FileUserService() : IFileService.Stub() {

    private var cachedRoot: String? = null
    private var cached: List<String> = emptyList()

    override fun destroy() {
        exitProcess(0)
    }

    override fun exists(path: String): Boolean = File(path).exists()

    override fun listPage(root: String, skip: Int, take: Int): Array<String> {
        if (skip == 0 || cachedRoot != root) {
            cachedRoot = root
            cached = CandidateRules.walk(File(root)).map { (path, size) -> "$size|$path" }
        }
        return cached.drop(skip).take(take).toTypedArray()
    }

    override fun open(path: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
}
