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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.tiktoktowhatsappstickers.BuildConfig
import com.example.tiktoktowhatsappstickers.data.model.StickerPack
import com.example.tiktoktowhatsappstickers.theme.*
import com.example.tiktoktowhatsappstickers.ui.grabber.TikTokWebGrabberDialog
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

    // Auto-fill copied TikTok video link if one is on clipboard
    LaunchedEffect(Unit) {
        val clip = clipboardManager.getText()?.text
        if (!clip.isNullOrBlank() && (clip.contains("tiktok.com") || clip.contains("ibyteimg.com"))) {
            inputUrl = clip.trim()
        }
    }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 30)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) viewModel.importFromMediaUris(uris)
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        viewModel.onStoragePermissionResult(granted)
    }

    LaunchedEffect(uiState.pendingMediaPermission) {
        val perm = uiState.pendingMediaPermission ?: return@LaunchedEffect
        viewModel.consumeMediaPermissionPrompt()
        storagePermissionLauncher.launch(perm)
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
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(Brush.linearGradient(listOf(TikTokPink, TikTokCyan))),
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
                            Text(
                                text = "StickerBridge",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "v${BuildConfig.VERSION_NAME} • TikTok → WhatsApp (512×512)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = { viewModel.checkForUpdate(manual = true) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdateAlt,
                                contentDescription = "Check for Updates",
                                tint = TikTokCyan,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (uiState.isWhatsAppInstalled) WhatsAppGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(
                                            if (uiState.isWhatsAppInstalled) WhatsAppGreen else MaterialTheme.colorScheme.error,
                                            CircleShape
                                        )
                                )
                                Spacer(modifier = Modifier.width(5.dp))
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
            UpdateBanner(
                updateUi = uiState.update,
                updateCheckMessage = uiState.updateCheckMessage,
                onStartUpdate = { viewModel.startUpdate() },
                onDismissMessage = { viewModel.dismissUpdateMessage() }
            )

            // Clean, intuitive source chooser card (Zero 3rd-party apps)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(listOf(TikTokPink.copy(alpha = 0.45f), TikTokCyan.copy(alpha = 0.45f)))
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Section 1: Grab Stickers from a Video Comment Section
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Forum,
                            contentDescription = null,
                            tint = TikTokPink,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Pull Stickers from Video Comments",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Paste or share a TikTok video link to open its comments and grab every sticker automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = inputUrl,
                            onValueChange = { inputUrl = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Paste TikTok video link…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                                            if (text.contains("http")) inputUrl = text.trim()
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

                        Button(
                            onClick = {
                                viewModel.openVideoCommentsGrabber(inputUrl)
                                inputUrl = ""
                            },
                            enabled = inputUrl.isNotBlank() && !uiState.isImporting,
                            modifier = Modifier.height(54.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TikTokPink)
                        ) {
                            Icon(imageVector = Icons.Default.Search, contentDescription = "Grab Comments", tint = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Section 2: Saved / Favorited Stickers & Local Imports
                    Text(
                        text = "Other Ways to Import",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QuickSourceTile(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.ChatBubbleOutline,
                            label = "DMs &\nSaved",
                            accent = TikTokCyan,
                            enabled = !uiState.isImporting,
                            onClick = { viewModel.openTikTokLiveBrowser() }
                        )
                        QuickSourceTile(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.AutoFixHigh,
                            label = "Scan Phone\nGIFs",
                            accent = TikTokPink,
                            enabled = !uiState.isImporting,
                            onClick = { viewModel.scanTikTokCache() }
                        )
                        QuickSourceTile(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.PhotoLibrary,
                            label = "Gallery\n/ GIFs",
                            accent = WhatsAppGreen,
                            enabled = !uiState.isImporting,
                            onClick = {
                                mediaPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        )
                    }

                    // Progress indicator
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
                                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = WhatsAppGreen)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = info,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { viewModel.clearInfo() }, modifier = Modifier.size(28.dp)) {
                                    Icon(imageVector = Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

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
                                Icon(imageVector = Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { viewModel.clearError() }, modifier = Modifier.size(28.dp)) {
                                    Icon(imageVector = Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }

            // Library Header
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
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "No Sticker Packs Yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Share or paste a TikTok video link to extract comment stickers, open DMs & Saved to grab your own stickers live, or pick from Gallery.",
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

    // Live TikTok Comment / DM Sticker Grabber (WebView - zero 3rd party apps)
    uiState.grabberSession?.let { session ->
        TikTokWebGrabberDialog(
            initialUrl = session.url,
            title = session.title,
            startInDesktopMode = session.startInDesktopMode,
            onDismiss = { viewModel.closeGrabber() },
            onImportStickers = { packName, raws ->
                viewModel.importCapturedStickers(packName, raws)
            }
        )
    }

    // Pack Detail Dialog
    uiState.selectedPack?.let { pack ->
        PackDetailDialog(
            pack = pack,
            onDismiss = { viewModel.selectPack(null) },
            onAddToWhatsApp = { onAddToWhatsAppIntent(it) },
            onDeletePack = { packId -> viewModel.deletePack(packId) }
        )
    }
}

@Composable
private fun QuickSourceTile(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.12f),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                lineHeight = MaterialTheme.typography.labelSmall.lineHeight
            )
        }
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
                    Icon(imageVector = Icons.Default.SystemUpdate, contentDescription = null, tint = WhatsAppGreen)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Update v${updateUi.info.versionName} Available",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
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
                        text = "v${updateUi.info.versionName} verified & ready",
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
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

                Button(
                    onClick = onAddToWhatsApp,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (pack.isAddedToWhatsApp) WhatsAppGreen.copy(alpha = 0.15f) else WhatsAppGreen
                    ),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    if (pack.isAddedToWhatsApp) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = "Added", tint = WhatsAppGreen, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Added", color = WhatsAppGreen, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "Add", tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

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
