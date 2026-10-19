package com.example.tiktoktowhatsappstickers.ui.main

import android.Manifest
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.tiktoktowhatsappstickers.BuildConfig
import com.example.tiktoktowhatsappstickers.core.shizuku.ShizukuBridge
import com.example.tiktoktowhatsappstickers.data.model.StickerPack
import com.example.tiktoktowhatsappstickers.theme.*
import com.example.tiktoktowhatsappstickers.ui.pack_detail.PackDetailDialog
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onAddToWhatsAppIntent: (StickerPack) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var inputUrl by remember { mutableStateOf("") }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    // Auto-detect TikTok link on clipboard when app opens
    LaunchedEffect(Unit) {
        val clip = clipboardManager.getText()?.text
        if (!clip.isNullOrBlank() && (clip.contains("tiktok.com") || clip.contains("vm.tiktok.com"))) {
            inputUrl = clip.trim()
        }
    }

    // Photo/GIF/WebP Picker Launcher
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 30)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.importFromMediaUris(uris)
        }
    }

    // SAF Document Tree Folder Picker (pre-targeted at TikTok's Android/data folder)
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        viewModel.onFolderGranted(uri)
    }

    // Legacy Storage Permission Launcher (Android 8 - 9)
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        viewModel.onStoragePermissionResult(granted)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // App Brand Badge
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(TikTokPink, TikTokCyan)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "SB",
                                color = Color.White,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Black
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "TikTok",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = " ➔ ",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TikTokPink,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "WhatsApp",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Black,
                                    color = WhatsAppGreen
                                )
                            }
                            Text(
                                text = "v${BuildConfig.VERSION_NAME} • 512×512 Deduplicated Engine",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Check for App Update Icon Button
                        IconButton(
                            onClick = { viewModel.checkForUpdate(manual = true) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdateAlt,
                                contentDescription = "Check for Updates",
                                tint = TikTokCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // WhatsApp Status Pill
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (uiState.isWhatsAppInstalled) WhatsAppGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(
                                            if (uiState.isWhatsAppInstalled) WhatsAppGreen else MaterialTheme.colorScheme.error,
                                            CircleShape
                                        )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (uiState.isWhatsAppInstalled) "WA Ready" else "No WA",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (uiState.isWhatsAppInstalled) WhatsAppGreen else MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // OTA Update Banner (shown when a new version is published in update.json)
            UpdateBanner(
                updateUi = uiState.update,
                updateCheckMessage = uiState.updateCheckMessage,
                onStartUpdate = { viewModel.startUpdate() },
                onDismissMessage = { viewModel.dismissUpdateMessage() }
            )

            // Import Studio Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(listOf(TikTokPink.copy(alpha = 0.5f), TikTokCyan.copy(alpha = 0.5f)))
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AddReaction,
                            contentDescription = null,
                            tint = TikTokPink,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Fetch & Convert TikTok Stickers",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Scan TikTok's sticker cache directly, or import from a link / gallery. Every sticker gets a unique ID and is scaled to 512×512.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 1-Click Automatic TikTok Sticker Scanner
                    Button(
                        onClick = { viewModel.scanTikTok() },
                        enabled = !uiState.isImporting,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TikTokCyan,
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = Color.Black
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "⚡ 1-Tap Fetch TikTok Stickers",
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.Black
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Input Field for Link
                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Paste TikTok link or direct sticker URL…") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TikTokPink,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        trailingIcon = {
                            if (inputUrl.isNotEmpty()) {
                                IconButton(onClick = { inputUrl = "" }) {
                                    Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear")
                                }
                            } else {
                                IconButton(onClick = {
                                    val clipboard =
                                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    if (clipboard.hasPrimaryClip() &&
                                        clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
                                    ) {
                                        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                        if (text.startsWith("http")) {
                                            inputUrl = text
                                        }
                                    }
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = "Paste",
                                        tint = TikTokCyan
                                    )
                                }
                            }
                        },
                        shape = RoundedCornerShape(14.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Action Buttons Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                if (inputUrl.isNotBlank()) {
                                    viewModel.importTikTokUrl(inputUrl)
                                    inputUrl = ""
                                }
                            },
                            enabled = inputUrl.isNotBlank() && !uiState.isImporting,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TikTokPink)
                        ) {
                            Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Import Link", fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                mediaPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            enabled = !uiState.isImporting,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Icon(imageVector = Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Gallery Import", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // Loading State
                    AnimatedVisibility(visible = uiState.isImporting) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp)),
                                color = TikTokPink
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = uiState.importProgressMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = TikTokCyan,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Info / Deduplication Alert
                    uiState.infoMessage?.let { info ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = WhatsAppGreen.copy(alpha = 0.14f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = WhatsAppGreen
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = info,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { viewModel.clearInfo() }, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Dismiss",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Error Alert
                    uiState.errorMessage?.let { error ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { viewModel.clearError() }, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Dismiss",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Section Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Your Sticker Library",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${uiState.packs.size} Pack${if (uiState.packs.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Sticker Packs List or Honest Empty State
            if (uiState.packs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesomeMotion,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No Sticker Packs Yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Open TikTok, view or favorite your stickers in DMs or comments, then tap '1-Tap Fetch TikTok Stickers' above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.packs, key = { it.id }) { pack ->
                        StickerPackCard(
                            pack = pack,
                            onCardClick = { viewModel.selectPack(pack) },
                            onAddToWhatsApp = { onAddToWhatsAppIntent(pack) }
                        )
                    }
                }
            }
        }
    }

    // Detail Dialog Preview
    uiState.selectedPack?.let { pack ->
        PackDetailDialog(
            pack = pack,
            onDismiss = { viewModel.selectPack(null) },
            onAddToWhatsApp = { onAddToWhatsAppIntent(it) },
            onDeletePack = { packId -> viewModel.deletePack(packId) }
        )
    }

    // One-time Access Setup Dialog (Storage Permission / Folder Grant / Shizuku)
    uiState.accessPrompt?.let { prompt ->
        AccessSetupDialog(
            prompt = prompt,
            onDismiss = { viewModel.dismissAccessPrompt() },
            onRequestStoragePermission = {
                viewModel.dismissAccessPrompt()
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            },
            onLaunchFolderPicker = { suggestedUri ->
                viewModel.dismissAccessPrompt()
                folderPickerLauncher.launch(suggestedUri)
            },
            onRequestShizukuPermission = {
                viewModel.requestShizukuPermission()
            },
            onRefreshShizuku = {
                viewModel.refreshAccessPrompt()
                viewModel.scanTikTok()
            }
        )
    }
}

@Composable
private fun UpdateBanner(
    updateUi: UpdateUi,
    updateCheckMessage: String?,
    onStartUpdate: () -> Unit,
    onDismissMessage: () -> Unit
) {
    when (updateUi) {
        is UpdateUi.Available -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = WhatsAppGreen.copy(alpha = 0.15f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, WhatsAppGreen)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = WhatsAppGreen
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Update v${updateUi.info.versionName} Available",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (updateUi.info.changelog.isNotEmpty()) {
                            Text(
                                text = updateUi.info.changelog.joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onStartUpdate,
                        colors = ButtonDefaults.buttonColors(containerColor = WhatsAppGreen),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Install", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        is UpdateUi.Downloading -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Downloading Update v${updateUi.info.versionName}… ${if (updateUi.percent >= 0) "${updateUi.percent}%" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    if (updateUi.percent in 0..100) {
                        LinearProgressIndicator(
                            progress = { updateUi.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = WhatsAppGreen
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = WhatsAppGreen)
                    }
                }
            }
        }
        is UpdateUi.Ready -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = WhatsAppGreen.copy(alpha = 0.2f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, WhatsAppGreen)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "v${updateUi.info.versionName} downloaded & verified",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = onStartUpdate,
                        colors = ButtonDefaults.buttonColors(containerColor = WhatsAppGreen),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Tap to Install", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        is UpdateUi.Error -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Update error: ${updateUi.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onStartUpdate) {
                        Text("Retry", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        UpdateUi.None -> {
            if (updateCheckMessage != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = null,
                            tint = WhatsAppGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = updateCheckMessage,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onDismissMessage, modifier = Modifier.size(24.dp)) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessSetupDialog(
    prompt: AccessPrompt,
    onDismiss: () -> Unit,
    onRequestStoragePermission: () -> Unit,
    onLaunchFolderPicker: (Uri) -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onRefreshShizuku: () -> Unit
) {
    val context = LocalContext.current
    when (prompt) {
        AccessPrompt.StoragePermission -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Allow Storage Access", fontWeight = FontWeight.Bold) },
                text = {
                    Text("StickerBridge needs storage permission once to read TikTok's cached stickers on your device.")
                },
                confirmButton = {
                    Button(
                        onClick = onRequestStoragePermission,
                        colors = ButtonDefaults.buttonColors(containerColor = TikTokCyan)
                    ) {
                        Text("Grant Permission", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            )
        }
        is AccessPrompt.FolderGrant -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("One-Time TikTok Folder Grant", fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "Android protects app folders until you approve them once.\n\n" +
                            "On the next screen, tap 'Use this folder' at the bottom so StickerBridge can read TikTok's sticker cache automatically from now on."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { onLaunchFolderPicker(prompt.suggested) },
                        colors = ButtonDefaults.buttonColors(containerColor = TikTokCyan)
                    ) {
                        Text("Open TikTok Folder", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            )
        }
        is AccessPrompt.Shizuku -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Unlock TikTok Sticker Cache (Android 13+)", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "On Android 13+, Google blocks regular apps and the system file picker from opening another app's Android/data folder.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        when (prompt.state) {
                            ShizukuBridge.State.NOT_INSTALLED -> {
                                Text(
                                    "To read TikTok's internal sticker cache without root, install the free Shizuku helper once and enable Wireless Debugging:",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            ShizukuBridge.State.NOT_RUNNING -> {
                                Text(
                                    "Shizuku is installed on your phone, but isn't running yet. Open Shizuku, tap 'Start via Wireless debugging', then return here.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            ShizukuBridge.State.NEEDS_PERMISSION -> {
                                Text(
                                    "Shizuku is running! Tap 'Allow in Shizuku' below to let StickerBridge read TikTok's sticker cache in 1 tap.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            ShizukuBridge.State.READY -> {
                                Text(
                                    "Shizuku is ready! Tap 'Scan Now' to fetch your TikTok stickers.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        if (prompt.folderGrantAlsoPossible) {
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = { onLaunchFolderPicker(prompt.suggested) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Try System Folder Picker Instead")
                            }
                        }
                    }
                },
                confirmButton = {
                    when (prompt.state) {
                        ShizukuBridge.State.NOT_INSTALLED -> {
                            Button(
                                onClick = {
                                    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ShizukuBridge.PACKAGE}"))
                                    val web = Intent(Intent.ACTION_VIEW, Uri.parse(ShizukuBridge.RELEASES_URL))
                                    runCatching { context.startActivity(market) }.onFailure { context.startActivity(web) }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = TikTokCyan)
                            ) {
                                Text("Install Shizuku (Free)", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                        ShizukuBridge.State.NOT_RUNNING -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onRefreshShizuku) {
                                    Text("I Started It")
                                }
                                Button(
                                    onClick = {
                                        val launch = context.packageManager.getLaunchIntentForPackage(ShizukuBridge.PACKAGE)
                                        if (launch != null) context.startActivity(launch)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = TikTokCyan)
                                ) {
                                    Text("Open Shizuku", color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        ShizukuBridge.State.NEEDS_PERMISSION -> {
                            Button(
                                onClick = onRequestShizukuPermission,
                                colors = ButtonDefaults.buttonColors(containerColor = TikTokCyan)
                            ) {
                                Text("Allow in Shizuku", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                        ShizukuBridge.State.READY -> {
                            Button(
                                onClick = onRefreshShizuku,
                                colors = ButtonDefaults.buttonColors(containerColor = TikTokCyan)
                            ) {
                                Text("Scan Now", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            )
        }
    }
}

@Composable
fun StickerPackCard(
    pack: StickerPack,
    onCardClick: () -> Unit,
    onAddToWhatsApp: () -> Unit
) {
    val context = LocalContext.current
    val packDir = File(context.filesDir, "sticker_packs/${pack.id}")
    val trayFile = File(packDir, pack.trayImageFile)
    val realStickers = remember(pack.stickers) {
        pack.stickers.filter { !it.isPadding }.ifEmpty { pack.stickers }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCardClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Main Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tray Icon
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = trayFile,
                        contentDescription = pack.name,
                        modifier = Modifier.size(44.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                // Title & Details
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pack.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${realStickers.size} unique • 512×512",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = " • ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (pack.animatedStickerPack) "Animated" else "Static",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (pack.animatedStickerPack) TikTokPink else TikTokCyan
                        )
                    }
                    Text(
                        text = "ID: ${pack.id}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // WhatsApp Transfer Button
                Button(
                    onClick = onAddToWhatsApp,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (pack.isAddedToWhatsApp) WhatsAppGreen.copy(alpha = 0.15f) else WhatsAppGreen
                    ),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    if (pack.isAddedToWhatsApp) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Added",
                            tint = WhatsAppGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Added",
                            color = WhatsAppGreen,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Add",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Sticker Thumbnails Preview Carousel
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(realStickers.take(5), key = { it.id.ifBlank { it.imageFileName } }) { sticker ->
                    val stickerFile = File(packDir, sticker.imageFileName)
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0D0D14))
                            .border(0.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = stickerFile,
                            contentDescription = "Sticker Preview",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                if (realStickers.size > 5) {
                    item {
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "+${realStickers.size - 5}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
