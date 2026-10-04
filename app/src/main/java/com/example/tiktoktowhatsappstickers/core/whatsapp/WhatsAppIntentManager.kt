package com.example.tiktoktowhatsappstickers.core.whatsapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.example.tiktoktowhatsappstickers.BuildConfig
import com.example.tiktoktowhatsappstickers.data.model.StickerPack

class WhatsAppIntentManager(private val context: Context) {

    companion object {
        private const val TAG = "WhatsAppIntentManager"

        const val EXTRA_STICKER_PACK_ID = "extra_sticker_pack_id"
        const val EXTRA_STICKER_PACK_AUTHORITY = "extra_sticker_pack_authority"
        const val EXTRA_STICKER_PACK_NAME = "extra_sticker_pack_name"

        const val ACTION_ENABLE_STICKER_PACK = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
        const val ACTION_ENABLE_ADD_PACK = "com.whatsapp.intent.action.ENABLE_ADD_PACK"
        const val PACKAGE_WHATSAPP = "com.whatsapp"
        const val PACKAGE_WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    }

    fun isWhatsAppInstalled(): Boolean {
        return isPackageInstalled(PACKAGE_WHATSAPP) || isPackageInstalled(PACKAGE_WHATSAPP_BUSINESS)
    }

    fun getInstalledWhatsAppPackage(): String? {
        return when {
            isPackageInstalled(PACKAGE_WHATSAPP) -> PACKAGE_WHATSAPP
            isPackageInstalled(PACKAGE_WHATSAPP_BUSINESS) -> PACKAGE_WHATSAPP_BUSINESS
            else -> null
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Builds the Intent to trigger WhatsApp's "Add Sticker Pack" system dialog.
     */
    fun createAddPackIntent(pack: StickerPack): Intent? {
        val targetPackage = getInstalledWhatsAppPackage() ?: return null
        val authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY

        // Try modern ENABLE_STICKER_PACK first, then fall back to legacy ENABLE_ADD_PACK
        val candidateActions = listOf(
            ACTION_ENABLE_STICKER_PACK,
            ACTION_ENABLE_ADD_PACK
        )

        for (action in candidateActions) {
            val intent = Intent(action).apply {
                `package` = targetPackage
                putExtra(EXTRA_STICKER_PACK_ID, pack.id)
                putExtra(EXTRA_STICKER_PACK_AUTHORITY, authority)
                putExtra(EXTRA_STICKER_PACK_NAME, pack.name)

                // Extra compatibility keys used across WhatsApp builds
                putExtra("sticker_pack_id", pack.id)
                putExtra("sticker_pack_authority", authority)
                putExtra("sticker_pack_name", pack.name)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                return intent
            }
        }

        // Direct fallback targeting the package
        return Intent(ACTION_ENABLE_STICKER_PACK).apply {
            `package` = targetPackage
            putExtra(EXTRA_STICKER_PACK_ID, pack.id)
            putExtra(EXTRA_STICKER_PACK_AUTHORITY, authority)
            putExtra(EXTRA_STICKER_PACK_NAME, pack.name)
            putExtra("sticker_pack_id", pack.id)
            putExtra("sticker_pack_authority", authority)
            putExtra("sticker_pack_name", pack.name)
        }
    }

    /**
     * Checks if WhatsApp has already whitelisted/added this sticker pack.
     */
    fun isPackAdded(packId: String): Boolean {
        val targetPackage = getInstalledWhatsAppPackage() ?: return false
        val authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY
        
        // Whitelist provider authority in modern WhatsApp is .provider.sticker_whitelist_check
        val providerAuthorities = listOf(
            "$targetPackage.provider.sticker_whitelist_check",
            "$targetPackage.provider.sticker"
        )

        for (providerAuth in providerAuthorities) {
            val queryUri = Uri.parse("content://$providerAuth/is_whitelisted")
                .buildUpon()
                .appendQueryParameter("authority", authority)
                .appendQueryParameter("identifier", packId)
                .build()

            try {
                val cursor = context.contentResolver.query(queryUri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        return it.getInt(0) == 1
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Whitelist check exception for $providerAuth: ${e.message}")
            }
        }
        return false
    }
}
