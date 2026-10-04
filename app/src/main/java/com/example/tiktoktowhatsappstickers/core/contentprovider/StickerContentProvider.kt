package com.example.tiktoktowhatsappstickers.core.contentprovider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import android.util.Log
import com.example.tiktoktowhatsappstickers.BuildConfig
import com.example.tiktoktowhatsappstickers.data.storage.StickerStorageManager
import java.io.File
import java.io.FileNotFoundException

class StickerContentProvider : ContentProvider() {

    companion object {
        private const val TAG = "StickerContentProvider"

        const val STICKER_PACK_IDENTIFIER_IN_QUERY = "sticker_pack_identifier"
        const val STICKER_PACK_NAME_IN_QUERY = "sticker_pack_name"
        const val STICKER_PACK_PUBLISHER_IN_QUERY = "sticker_pack_publisher"
        const val STICKER_PACK_ICON_IN_QUERY = "sticker_pack_icon"
        const val ANDROID_APP_DOWNLOAD_LINK_IN_QUERY = "android_play_store_link"
        const val IOS_APP_DOWNLOAD_LINK_IN_QUERY = "ios_app_download_link"
        const val PUBLISHER_EMAIL = "sticker_pack_publisher_email"
        const val PUBLISHER_WEBSITE = "sticker_pack_publisher_website"
        const val PRIVACY_POLICY_WEBSITE = "sticker_pack_privacy_policy_website"
        const val LICENSE_AGREEMENT_WEBSITE = "sticker_pack_license_agreement_website"
        const val IMAGE_DATA_VERSION = "image_data_version"
        const val AVOID_CACHE = "whatsapp_will_not_cache_stickers"
        const val ANIMATED_STICKER_PACK = "animated_sticker_pack"

        const val STICKER_FILE_NAME_IN_QUERY = "sticker_file_name"
        const val STICKER_FILE_EMOJI_IN_QUERY = "sticker_emoji"
        const val STICKER_FILE_ACCESSIBILITY_TEXT_IN_QUERY = "sticker_accessibility_text"

        private const val METADATA = 1
        private const val METADATA_SINGLE_PACK = 2
        private const val STICKERS = 3
        private const val STICKERS_ASSET = 4

        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH)

        init {
            val authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY
            MATCHER.addURI(authority, "metadata", METADATA)
            MATCHER.addURI(authority, "metadata/*", METADATA_SINGLE_PACK)
            MATCHER.addURI(authority, "stickers/*", STICKERS)
            MATCHER.addURI(authority, "stickers_asset/*/*", STICKERS_ASSET)
        }
    }

    private lateinit var storageManager: StickerStorageManager

    override fun onCreate(): Boolean {
        val context = context ?: return false
        storageManager = StickerStorageManager.getInstance(context)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val code = MATCHER.match(uri)
        Log.d(TAG, "query: uri=$uri, matchCode=$code")
        val cursor = when (code) {
            METADATA -> getPackForAllMetadata()
            METADATA_SINGLE_PACK -> {
                val packId = uri.lastPathSegment ?: return null
                getCursorForSinglePack(packId)
            }
            STICKERS -> {
                val packId = uri.lastPathSegment ?: return null
                getStickersForPack(packId)
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
        context?.contentResolver?.let { cursor?.setNotificationUri(it, uri) }
        return cursor
    }

    private fun getPackForAllMetadata(): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                STICKER_PACK_IDENTIFIER_IN_QUERY,
                STICKER_PACK_NAME_IN_QUERY,
                STICKER_PACK_PUBLISHER_IN_QUERY,
                STICKER_PACK_ICON_IN_QUERY,
                ANDROID_APP_DOWNLOAD_LINK_IN_QUERY,
                IOS_APP_DOWNLOAD_LINK_IN_QUERY,
                PUBLISHER_EMAIL,
                PUBLISHER_WEBSITE,
                PRIVACY_POLICY_WEBSITE,
                LICENSE_AGREEMENT_WEBSITE,
                IMAGE_DATA_VERSION,
                AVOID_CACHE,
                ANIMATED_STICKER_PACK
            )
        )
        val packs = storageManager.getPacks()
        for (pack in packs) {
            cursor.addRow(
                arrayOf<Any?>(
                    pack.id,
                    pack.name,
                    pack.publisher,
                    pack.trayImageFile,
                    "", // Play Store link
                    "", // App Store link
                    pack.publisherEmail,
                    pack.publisherWebsite,
                    pack.privacyPolicyWebsite,
                    pack.licenseAgreementWebsite,
                    pack.imageDataVersion,
                    0,  // avoid_cache (0 = false)
                    if (pack.animatedStickerPack) 1 else 0
                )
            )
        }
        return cursor
    }

    private fun getCursorForSinglePack(packId: String): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                STICKER_PACK_IDENTIFIER_IN_QUERY,
                STICKER_PACK_NAME_IN_QUERY,
                STICKER_PACK_PUBLISHER_IN_QUERY,
                STICKER_PACK_ICON_IN_QUERY,
                ANDROID_APP_DOWNLOAD_LINK_IN_QUERY,
                IOS_APP_DOWNLOAD_LINK_IN_QUERY,
                PUBLISHER_EMAIL,
                PUBLISHER_WEBSITE,
                PRIVACY_POLICY_WEBSITE,
                LICENSE_AGREEMENT_WEBSITE,
                IMAGE_DATA_VERSION,
                AVOID_CACHE,
                ANIMATED_STICKER_PACK
            )
        )
        val pack = storageManager.getPack(packId) ?: return cursor
        cursor.addRow(
            arrayOf<Any?>(
                pack.id,
                pack.name,
                pack.publisher,
                pack.trayImageFile,
                "",
                "",
                pack.publisherEmail,
                pack.publisherWebsite,
                pack.privacyPolicyWebsite,
                pack.licenseAgreementWebsite,
                pack.imageDataVersion,
                0, // avoid_cache
                if (pack.animatedStickerPack) 1 else 0
            )
        )
        return cursor
    }

    private fun getStickersForPack(packId: String): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                STICKER_FILE_NAME_IN_QUERY,
                STICKER_FILE_EMOJI_IN_QUERY,
                STICKER_FILE_ACCESSIBILITY_TEXT_IN_QUERY
            )
        )
        val pack = storageManager.getPack(packId) ?: return cursor
        for (sticker in pack.stickers) {
            cursor.addRow(
                arrayOf(
                    sticker.imageFileName,
                    TextUtils.join(",", sticker.emojis),
                    ""
                )
            )
        }
        return cursor
    }

    override fun getType(uri: Uri): String? {
        val matchCode = MATCHER.match(uri)
        val authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY
        return when (matchCode) {
            METADATA -> "vnd.android.cursor.dir/vnd.$authority.metadata"
            METADATA_SINGLE_PACK -> "vnd.android.cursor.item/vnd.$authority.metadata"
            STICKERS -> "vnd.android.cursor.dir/vnd.$authority.stickers"
            STICKERS_ASSET -> {
                val fileName = uri.lastPathSegment ?: ""
                if (fileName.endsWith(".png", ignoreCase = true)) {
                    "image/png"
                } else {
                    "image/webp"
                }
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val matchCode = MATCHER.match(uri)
        if (matchCode == STICKERS_ASSET) {
            val pathSegments = uri.pathSegments
            val packId = pathSegments[1]
            val fileName = pathSegments[2]
            val file = storageManager.getStickerFile(packId, fileName)
            if (file.exists()) {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
            } else {
                Log.e(TAG, "File does not exist: ${file.absolutePath}")
                throw FileNotFoundException("File not found for $uri")
            }
        }
        throw IllegalArgumentException("Invalid URI for openAssetFile: $uri")
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        return openAssetFile(uri, mode)?.parcelFileDescriptor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
