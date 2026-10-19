package com.example.tiktoktowhatsappstickers.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Sticker(
    val imageFileName: String,
    val emojis: List<String> = listOf("✨"),
    val sizeBytes: Long = 0L,
    val isAnimated: Boolean = false,
    /** Stable identity = SHA-256 (first 16 bytes, hex) of the ORIGINAL TikTok image. Same sticker => same id. */
    val id: String = "",
    /** TikTok's own cache key (the cache file name) when known, e.g. "6QDD9aRClhSraVLEiCwb0rGpYeg". */
    val sourceKey: String = "",
    /** Filler copy added only so a pack reaches WhatsApp's 3-sticker minimum; replaced by real stickers later. */
    val isPadding: Boolean = false
)

@Serializable
data class StickerPack(
    val id: String,
    val name: String,
    val publisher: String,
    val trayImageFile: String,
    val publisherEmail: String = "contact@tiktok2whatsapp.app",
    val publisherWebsite: String = "https://tiktok.com",
    val privacyPolicyWebsite: String = "https://tiktok.com/privacy",
    val licenseAgreementWebsite: String = "https://tiktok.com/legal/terms-of-service",
    val animatedStickerPack: Boolean = false,
    val imageDataVersion: String = "1",
    val stickers: List<Sticker> = emptyList(),
    val isAddedToWhatsApp: Boolean = false,
    /** True for packs maintained automatically by the TikTok scanner (they grow instead of duplicating). */
    val autoManaged: Boolean = false
) {
    /** Number of real (non-filler) stickers. */
    val realStickerCount: Int get() = stickers.count { !it.isPadding }
}
