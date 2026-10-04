package com.example.tiktoktowhatsappstickers.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Sticker(
    val imageFileName: String,
    val emojis: List<String> = listOf("✨"),
    val sizeBytes: Long = 0L,
    val isAnimated: Boolean = false
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
    val isAddedToWhatsApp: Boolean = false
)
