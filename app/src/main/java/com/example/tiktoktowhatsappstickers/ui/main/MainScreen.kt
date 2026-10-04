package com.example.tiktoktowhatsappstickers.ui.main

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
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

    // Photo/Video Picker Launcher
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 30)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.importFromMediaUris(uris)
        }
    }

    // SAF Document Tree Folder Picker
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Ignore if persistable permission not granted
            }
            viewModel.scanDocumentTree(it)
        }
    }

    // SAF Multiple Document Files Picker (.cnt / images)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.scanSelectedCacheFiles(uris)
        }
    }

    var showCacheScannerDialog by remember { mutableStateOf(false) }

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
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(TikTokPink, TikTokCyan)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "TT",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

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
                                text = "Sticker Studio & Exporter",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

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
            // Import Studio Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
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
                        .padding(18.dp)
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
                            text = "Import from TikTok",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Paste a TikTok video link or photo carousel to extract stickers. (Use 1-Tap Auto-Scan below for all saved stickers!)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Input Field
                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://www.tiktok.com/@.../video/... or photo link") },
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

                    Spacer(modifier = Modifier.height(12.dp))

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
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Icon(imageVector = Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Gallery Import", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 1-Click Automatic Device Scanner (Zero manual folder searching)
                    Button(
                        onClick = {
                            viewModel.scanDeviceMediaAutomatically()
                        },
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
                            text = "⚡ 1-Tap Auto-Scan TikTok Media",
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.Black
                        )
                    }

                    // Loading State
                    AnimatedVisibility(visible = uiState.isImporting) {
                        Column(modifier = Modifier.padding(top = 14.dp)) {
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

                    // Error Alert
                    uiState.errorMessage?.let { error ->
                        Spacer(modifier = Modifier.height(12.dp))
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
                                IconButton(onClick = { viewModel.clearError() }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Dismiss",
                                        tint = MaterialTheme.colorScheme.error
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
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ready for WhatsApp",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${uiState.packs.size} Packs",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Sticker Packs List
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

    // Detail Dialog Preview
    uiState.selectedPack?.let { pack ->
        PackDetailDialog(
            pack = pack,
            onDismiss = { viewModel.selectPack(null) },
            onAddToWhatsApp = { onAddToWhatsAppIntent(it) },
            onDeletePack = { packId -> viewModel.deletePack(packId) }
        )
    }

    // Cache Scanner Modal Dialog
    if (showCacheScannerDialog) {
        AlertDialog(
            onDismissRequest = { showCacheScannerDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.FolderSpecial,
                    contentDescription = null,
                    tint = TikTokCyan,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Import Device TikTok Cache",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "TikTok caches your saved and received stickers in its storage folder as .cnt files:\n\nAndroid/data/com.zhiliaoapp.musically/cache/picture/fresco_custom_cache/\n\nChoose an extraction method below:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCacheScannerDialog = false
                        folderPickerLauncher.launch(null)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TikTokCyan),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Select Cache Folder", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showCacheScannerDialog = false
                        filePickerLauncher.launch(arrayOf("*/*"))
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Pick .cnt Files Directly")
                }
            }
        )
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
                            text = "${pack.stickers.size} stickers",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = " • ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (pack.animatedStickerPack) "Animated WebP" else "Static WebP",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (pack.animatedStickerPack) TikTokPink else TikTokCyan
                        )
                    }
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
                items(pack.stickers.take(5)) { sticker ->
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
                if (pack.stickers.size > 5) {
                    item {
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "+${pack.stickers.size - 5}",
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
