package com.example.tiktoktowhatsappstickers.ui.pack_detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.tiktoktowhatsappstickers.data.model.Sticker
import com.example.tiktoktowhatsappstickers.data.model.StickerPack
import com.example.tiktoktowhatsappstickers.theme.*
import java.io.File

@Composable
fun PackDetailDialog(
    pack: StickerPack,
    onDismiss: () -> Unit,
    onAddToWhatsApp: (StickerPack) -> Unit,
    onDeletePack: (String) -> Unit
) {
    val context = LocalContext.current
    val packDir = File(context.filesDir, "sticker_packs/${pack.id}")
    var selectedStickerPreview by remember { mutableStateOf<Sticker?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val trayFile = File(packDir, pack.trayImageFile)
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = trayFile,
                            contentDescription = "Tray Icon",
                            modifier = Modifier.size(48.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pack.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Created by ${pack.publisher}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Metadata Chips Row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                if (pack.animatedStickerPack) "Animated WebP" else "Static WebP",
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = if (pack.animatedStickerPack) TikTokPink.copy(alpha = 0.15f) else TikTokCyan.copy(alpha = 0.15f),
                            labelColor = if (pack.animatedStickerPack) TikTokPink else TikTokCyan
                        )
                    )

                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text("${pack.stickers.size} Stickers (512x512)", fontWeight = FontWeight.Medium)
                        }
                    )

                    if (pack.isAddedToWhatsApp) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text("Added to WhatsApp ✓", fontWeight = FontWeight.Bold) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = WhatsAppGreen.copy(alpha = 0.15f),
                                labelColor = WhatsAppGreen
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Enlarged Sticker Quick Preview banner (if tapped)
                AnimatedVisibility(visible = selectedStickerPreview != null) {
                    selectedStickerPreview?.let { sticker ->
                        val previewFile = File(packDir, sticker.imageFileName)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = previewFile,
                                    contentDescription = "Preview",
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF101018))
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "WhatsApp Sticker Specification",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = WhatsAppGreen,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Size: ${sticker.sizeBytes / 1024} KB • 512x512 px",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Emojis: ${sticker.emojis.joinToString(" ")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { selectedStickerPreview = null }) {
                                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close Preview")
                                }
                            }
                        }
                    }
                }

                Text(
                    text = "Pack Contents (Tap to inspect)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Stickers Grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(pack.stickers) { sticker ->
                        val stickerFile = File(packDir, sticker.imageFileName)
                        val isSelected = selectedStickerPreview?.imageFileName == sticker.imageFileName

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedStickerPreview = sticker },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) WhatsAppGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.5.dp,
                                if (isSelected) WhatsAppGreen else Color.Transparent
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF0F0F14)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = stickerFile,
                                        contentDescription = "Sticker",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = sticker.emojis.joinToString(" "),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Delete Confirmation Dialog
                if (showDeleteConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirm = false },
                        title = { Text("Delete Sticker Pack?") },
                        text = { Text("Are you sure you want to delete '${pack.name}'? This cannot be undone.") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showDeleteConfirm = false
                                    onDeletePack(pack.id)
                                    onDismiss()
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Delete", fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirm = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                // Bottom Action Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier
                            .size(52.dp)
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Pack",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }

                    Button(
                        onClick = { onAddToWhatsApp(pack) },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WhatsAppGreen
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (pack.isAddedToWhatsApp) "Update on WhatsApp" else "Add to WhatsApp",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
