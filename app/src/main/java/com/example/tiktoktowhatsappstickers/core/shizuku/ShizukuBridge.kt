package com.example.tiktoktowhatsappstickers.core.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.content.pm.PackageManager
import android.util.Log
import com.example.tiktoktowhatsappstickers.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * Thin wrapper over the Shizuku API. Shizuku is a free open-source app that lets other apps use adb-level
 * access after a one-time wireless-debugging pairing. On Android 13+ it is the only no-root way to read
 * another app's Android/data folder, which is where TikTok caches your stickers.
 */
object ShizukuBridge {

    private const val TAG = "ShizukuBridge"
    const val PACKAGE = "moe.shizuku.privileged.api"
    const val RELEASES_URL = "https://github.com/RikkaApps/Shizuku/releases/latest"
    const val PERMISSION_REQUEST_CODE = 4242

    enum class State { NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, READY }

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun state(context: Context): State {
        if (!isRunning()) return if (isInstalled(context)) State.NOT_RUNNING else State.NOT_INSTALLED
        return try {
            if (Shizuku.isPreV11()) State.NOT_RUNNING
            else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) State.READY
            else State.NEEDS_PERMISSION
        } catch (e: Throwable) {
            State.NOT_RUNNING
        }
    }

    fun requestPermission() {
        try {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Throwable) {
            Log.w(TAG, "requestPermission failed", e)
        }
    }

    fun addPermissionListener(onResult: (granted: Boolean) -> Unit): Shizuku.OnRequestPermissionResultListener {
        val l = Shizuku.OnRequestPermissionResultListener { code, result ->
            if (code == PERMISSION_REQUEST_CODE) onResult(result == PackageManager.PERMISSION_GRANTED)
        }
        Shizuku.addRequestPermissionResultListener(l)
        return l
    }

    fun removePermissionListener(l: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.removeRequestPermissionResultListener(l)
    }

    fun addBinderListeners(onChange: () -> Unit): Pair<Shizuku.OnBinderReceivedListener, Shizuku.OnBinderDeadListener> {
        val r = Shizuku.OnBinderReceivedListener { onChange() }
        val d = Shizuku.OnBinderDeadListener { onChange() }
        Shizuku.addBinderReceivedListener(r)
        Shizuku.addBinderDeadListener(d)
        return r to d
    }

    fun removeBinderListeners(p: Pair<Shizuku.OnBinderReceivedListener, Shizuku.OnBinderDeadListener>) {
        Shizuku.removeBinderReceivedListener(p.first)
        Shizuku.removeBinderDeadListener(p.second)
    }

    /** Starts (or re-uses) the privileged file service. Returns null if it can't be reached in time. */
    suspend fun connect(): IFileService? = withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine { cont ->
            val args = Shizuku.UserServiceArgs(
                ComponentName(BuildConfig.APPLICATION_ID, FileUserService::class.java.name)
            ).daemon(false).processNameSuffix("files").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)

            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (cont.isActive) {
                        cont.resume(if (binder != null && binder.pingBinder()) IFileService.Stub.asInterface(binder) else null)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {}
            }
            try {
                Shizuku.bindUserService(args, conn)
            } catch (e: Throwable) {
                Log.w(TAG, "bindUserService failed", e)
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
