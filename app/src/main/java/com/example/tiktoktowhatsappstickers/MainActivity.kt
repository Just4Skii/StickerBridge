package com.example.tiktoktowhatsappstickers

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.tiktoktowhatsappstickers.core.whatsapp.WhatsAppIntentManager
import com.example.tiktoktowhatsappstickers.data.model.StickerPack
import com.example.tiktoktowhatsappstickers.theme.TikTokToWhatsAppStickersTheme
import com.example.tiktoktowhatsappstickers.ui.main.MainScreen
import com.example.tiktoktowhatsappstickers.ui.main.MainScreenViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainScreenViewModel by viewModels()
    private lateinit var whatsAppManager: WhatsAppIntentManager
    private lateinit var addPackLauncher: ActivityResultLauncher<Intent>
    private var pendingPackId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Enable animated WebP and GIF playback in UI
        val imageLoader = coil.ImageLoader.Builder(this)
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    add(coil.decode.ImageDecoderDecoder.Factory())
                } else {
                    add(coil.decode.GifDecoder.Factory())
                }
            }
            .build()
        coil.Coil.setImageLoader(imageLoader)

        whatsAppManager = WhatsAppIntentManager(this)

        addPackLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Toast.makeText(this, "Sticker pack added to WhatsApp!", Toast.LENGTH_LONG).show()
                pendingPackId?.let { viewModel.markAdded(it) }
            } else {
                Toast.makeText(this, "Cancelled or added to WhatsApp", Toast.LENGTH_SHORT).show()
            }
            pendingPackId = null
        }

        // Handle shared intent if opened from TikTok Share Sheet
        handleIncomingIntent(intent)

        setContent {
            TikTokToWhatsAppStickersTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onAddToWhatsAppIntent = { pack ->
                            launchAddPack(pack)
                        },
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type ?: return

        when (action) {
            Intent.ACTION_SEND -> {
                if (type == "text/plain") {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                    val urlPattern = Regex("https?://\\S+")
                    val match = urlPattern.find(sharedText)
                    val url = match?.value ?: sharedText
                    if (url.startsWith("http")) {
                        viewModel.importTikTokUrl(url)
                    }
                } else if (type.startsWith("image/") || type.startsWith("video/")) {
                    val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    if (uri != null) {
                        viewModel.importFromMediaUris(listOf(uri), "Shared Media Pack")
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (type.startsWith("image/")) {
                    val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    if (!uris.isNullOrEmpty()) {
                        viewModel.importFromMediaUris(uris, "Shared Media Pack")
                    }
                }
            }
        }
    }

    private fun launchAddPack(pack: StickerPack) {
        if (!whatsAppManager.isWhatsAppInstalled()) {
            Toast.makeText(this, "WhatsApp is not installed on this device", Toast.LENGTH_LONG).show()
            return
        }

        val addIntent = whatsAppManager.createAddPackIntent(pack)
        if (addIntent != null) {
            pendingPackId = pack.id
            try {
                addPackLauncher.launch(addIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open WhatsApp: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Could not create WhatsApp intent", Toast.LENGTH_SHORT).show()
        }
    }
}
