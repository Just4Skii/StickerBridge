package com.example.tiktoktowhatsappstickers.ui.grabber

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.tiktoktowhatsappstickers.core.engine.WebpProcessingEngine
import com.example.tiktoktowhatsappstickers.core.scanner.StickerClassifier
import com.example.tiktoktowhatsappstickers.data.storage.StickerLibrary
import com.example.tiktoktowhatsappstickers.theme.TikTokCyan
import com.example.tiktoktowhatsappstickers.theme.TikTokPink
import com.example.tiktoktowhatsappstickers.theme.WhatsAppGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit

data class GrabbedSticker(
    val id: String,
    val key: String,
    val bytes: ByteArray,
    val previewFile: File,
    val isAnimated: Boolean,
    val selected: Boolean = true
)

private const val DESKTOP_CHROME_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

private const val JS_COMMENT_STICKER_HOOK = """
(function() {
  if (window.__sbHooked) return;
  window.__sbHooked = true;

  function report(url, text) {
    try {
      if (!text || text.length < 20) return;
      if (url.indexOf('comment') !== -1 || url.indexOf('sticker') !== -1 || text.indexOf('sticker') !== -1 || text.indexOf('video2sticker') !== -1 || text.indexOf('tiktok-dm-sticker') !== -1) {
        if (window.StickerBridgeJs && window.StickerBridgeJs.onApiPayload) {
          window.StickerBridgeJs.onApiPayload(String(url), text);
        }
      }
    } catch (e) {}
  }

  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function() {
      var reqUrl = (arguments[0] && arguments[0].url) ? arguments[0].url : String(arguments[0] || '');
      return origFetch.apply(this, arguments).then(function(resp) {
        try {
          resp.clone().text().then(function(t) { report(reqUrl, t); }).catch(function(){});
        } catch (e) {}
        return resp;
      });
    };
  }

  var origOpen = XMLHttpRequest.prototype.open;
  var origSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(method, url) {
    this.__sbUrl = url;
    return origOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function() {
    this.addEventListener('load', function() {
      try {
        if (this.responseType === '' || this.responseType === 'text') {
          report(this.__sbUrl || '', this.responseText);
        }
      } catch (e) {}
    });
    return origSend.apply(this, arguments);
  };

  function scanDomImgs() {
    try {
      var imgs = document.querySelectorAll('img');
      for (var i = 0; i < imgs.length; i++) {
        var s = imgs[i].currentSrc || imgs[i].src || '';
        if (s && (s.indexOf('sticker') !== -1 || s.indexOf('video2sticker') !== -1 || s.indexOf('.awebp') !== -1)) {
          if (window.StickerBridgeJs && window.StickerBridgeJs.onStickerUrl) {
            window.StickerBridgeJs.onStickerUrl(s);
          }
        }
      }
    } catch (e) {}
  }
  setInterval(scanDomImgs, 2000);
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TikTokWebGrabberDialog(
    initialUrl: String,
    title: String,
    onDismiss: () -> Unit,
    onImportStickers: (packName: String, raws: List<StickerLibrary.Raw>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val previewDir = remember {
        File(context.cacheDir, "grabber_previews").apply {
            if (exists()) listFiles()?.forEach { it.delete() } else mkdirs()
        }
    }

    val httpClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    val seenCanonicalUrls = remember { Collections.synchronizedSet(HashSet<String>()) }
    val seenContentIds = remember { Collections.synchronizedSet(HashSet<String>()) }
    var captured by remember { mutableStateOf<List<GrabbedSticker>>(emptyList()) }
    var pageLoading by remember { mutableStateOf(true) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var isSaving by remember { mutableStateOf(false) }

    fun isLikelyStickerUrl(url: String): Boolean {
        val low = url.lowercase()
        if (!low.startsWith("https://")) return false
        val isTikTokCdn = "ibyteimg.com" in low || "tiktokcdn" in low || "byteoversea" in low
        if (!isTikTokCdn) return false
        if ("avt-" in low || "sc=avatar" in low || "avatar" in low || "favicon" in low || "logo" in low ||
            "qrcode" in low || "photomode" in low || "tplv-tiktokx-origin" in low
        ) {
            return false
        }
        return "tiktok-dm-sticker" in low || "video2sticker" in low || "sticker" in low || ".awebp" in low
    }

    fun canonicalKey(url: String): String = url.substringBefore('?').substringBefore('~')

    fun enqueueCandidateUrl(url: String, requireStickerUrlHint: Boolean = true) {
        if (requireStickerUrlHint && !isLikelyStickerUrl(url)) return
        val canon = canonicalKey(url)
        if (!seenCanonicalUrls.add(canon)) return

        scope.launch(Dispatchers.IO) {
            try {
                val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", DESKTOP_CHROME_UA)
                    .header("Referer", "https://www.tiktok.com/")
                if (!cookies.isNullOrBlank()) reqBuilder.header("Cookie", cookies)

                httpClient.newCall(reqBuilder.build()).execute().use { res ->
                    if (!res.isSuccessful) return@launch
                    val bytes = res.body?.bytes() ?: return@launch
                    val verdict = StickerClassifier.classify(bytes, canon, url)
                    if (!verdict.accept) return@launch

                    val id = StickerLibrary.idOf(bytes)
                    if (!seenContentIds.add(id)) return@launch

                    val anim = WebpProcessingEngine.isAnimatedBytes(bytes)
                    val ext = if (WebpProcessingEngine.sniff(bytes) == WebpProcessingEngine.Kind.GIF) "gif" else "webp"
                    val previewFile = File(previewDir, "${id.take(16)}.$ext").apply { writeBytes(bytes) }
                    val cacheKey = canon.substringAfterLast('/').ifBlank { id.take(12) }

                    val item = GrabbedSticker(
                        id = id,
                        key = cacheKey,
                        bytes = bytes,
                        previewFile = previewFile,
                        isAnimated = anim,
                        selected = true
                    )
                    scope.launch(Dispatchers.Main) {
                        captured = captured + item
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun parseJsonNodeForStickers(node: Any?) {
        when (node) {
            is JSONObject -> {
                // Direct sticker object from TikTok /api/comment/list/ -> prefer animate_url over static_url
                if (node.has("sticker")) {
                    val sObj = node.optJSONObject("sticker")
                    if (sObj != null) {
                        val animList = sObj.optJSONObject("animate_url")?.optJSONArray("url_list")
                        val staticList = sObj.optJSONObject("static_url")?.optJSONArray("url_list")
                        val chosen = animList?.optString(0)?.takeIf { it.startsWith("http") }
                            ?: staticList?.optString(0)?.takeIf { it.startsWith("http") }
                        if (chosen != null) enqueueCandidateUrl(chosen, requireStickerUrlHint = false)
                    }
                }
                val animDirect = node.optJSONObject("animate_url")?.optJSONArray("url_list")?.optString(0)
                if (!animDirect.isNullOrBlank() && animDirect.startsWith("http")) {
                    enqueueCandidateUrl(animDirect, requireStickerUrlHint = false)
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k == "sticker" || k == "animate_url" || k == "static_url") continue
                    parseJsonNodeForStickers(node.opt(k))
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    parseJsonNodeForStickers(node.opt(i))
                }
            }
            is String -> {
                if (node.startsWith("https://") && isLikelyStickerUrl(node)) {
                    enqueueCandidateUrl(node, requireStickerUrlHint = true)
                }
            }
        }
    }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
            }
            webViewRef = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (pageLoading) "Loading comments & watching for stickers…" else "Scroll comments or open DMs to grab stickers live",
                            style = MaterialTheme.typography.labelSmall,
                            color = TikTokCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(
                        onClick = { webViewRef?.loadUrl("https://www.tiktok.com/messages") },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("DMs", color = TikTokPink, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { webViewRef?.reload() }, modifier = Modifier.size(34.dp)) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Reload", modifier = Modifier.size(18.dp))
                    }
                }

                if (pageLoading || loadProgress in 1..99) {
                    LinearProgressIndicator(
                        progress = { (loadProgress.coerceIn(5, 100)) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = TikTokPink
                    )
                }

                // Embedded TikTok Browser
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            CookieManager.getInstance().setAcceptCookie(true)
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.databaseEnabled = true
                                settings.userAgentString = DESKTOP_CHROME_UA
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.setSupportZoom(true)
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                settings.mediaPlaybackRequiresUserGesture = true
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                                addJavascriptInterface(
                                    object {
                                        @JavascriptInterface
                                        fun onApiPayload(url: String, payload: String) {
                                            scope.launch(Dispatchers.Default) {
                                                runCatching {
                                                    val trimmed = payload.trim()
                                                    if (trimmed.startsWith("{")) parseJsonNodeForStickers(JSONObject(trimmed))
                                                    else if (trimmed.startsWith("[")) parseJsonNodeForStickers(JSONArray(trimmed))
                                                }
                                            }
                                        }

                                        @JavascriptInterface
                                        fun onStickerUrl(url: String) {
                                            enqueueCandidateUrl(url, requireStickerUrlHint = true)
                                        }
                                    },
                                    "StickerBridgeJs"
                                )

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        loadProgress = newProgress
                                        if (newProgress >= 100) pageLoading = false
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        pageLoading = true
                                        url?.let { currentUrl = it }
                                        view?.evaluateJavascript(JS_COMMENT_STICKER_HOOK, null)
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        pageLoading = false
                                        view?.evaluateJavascript(JS_COMMENT_STICKER_HOOK, null)
                                    }

                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        val scheme = request?.url?.scheme?.lowercase() ?: return false
                                        // Keep TikTok inside this browser; block deep-links trying to jump out to Play Store or native app nag
                                        return scheme != "http" && scheme != "https"
                                    }

                                    override fun shouldInterceptRequest(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): android.webkit.WebResourceResponse? {
                                        val reqUrl = request?.url?.toString()
                                        if (reqUrl != null && isLikelyStickerUrl(reqUrl)) {
                                            enqueueCandidateUrl(reqUrl, requireStickerUrlHint = true)
                                        }
                                        return null
                                    }
                                }

                                webViewRef = this
                                loadUrl(initialUrl)
                            }
                        }
                    )
                }

                // Bottom Live Capture Tray
                val selectedCount = captured.count { it.selected }
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (captured.isEmpty()) "Listening for stickers on page…" else "${captured.size} Unique Stickers Grabbed",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (captured.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else WhatsAppGreen
                            )
                            if (captured.isNotEmpty()) {
                                val allSelected = selectedCount == captured.size
                                TextButton(
                                    onClick = {
                                        val target = !allSelected
                                        captured = captured.map { it.copy(selected = target) }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(if (allSelected) "Unselect All" else "Select All")
                                }
                            }
                        }

                        if (captured.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(captured, key = { it.id }) { item ->
                                    Box(
                                        modifier = Modifier
                                            .size(62.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFF0E0E14))
                                            .border(
                                                2.dp,
                                                if (item.selected) WhatsAppGreen else MaterialTheme.colorScheme.outline,
                                                RoundedCornerShape(12.dp)
                                            )
                                            .clickable {
                                                captured = captured.map {
                                                    if (it.id == item.id) it.copy(selected = !it.selected) else it
                                                }
                                            }
                                    ) {
                                        AsyncImage(
                                            model = item.previewFile,
                                            contentDescription = "Captured Sticker",
                                            modifier = Modifier.fillMaxSize().padding(3.dp)
                                        )
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(3.dp)
                                                .size(16.dp)
                                                .background(
                                                    if (item.selected) WhatsAppGreen else Color.Black.copy(alpha = 0.6f),
                                                    CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (item.selected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(11.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Button(
                                onClick = {
                                    if (!isSaving && selectedCount > 0) {
                                        isSaving = true
                                        val chosen = captured.filter { it.selected }.map {
                                            StickerLibrary.Raw(it.key, it.bytes)
                                        }
                                        val packTitle = when {
                                            "/messages" in currentUrl -> "TikTok DM Stickers"
                                            "/video/" in currentUrl -> "TikTok Video Comments"
                                            else -> "TikTok Live Capture"
                                        }
                                        onImportStickers(packTitle, chosen)
                                    }
                                },
                                enabled = selectedCount > 0 && !isSaving,
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = WhatsAppGreen)
                            ) {
                                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isSaving) "Converting to 512×512…" else "Save $selectedCount Sticker${if (selectedCount == 1) "" else "s"} to Pack",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
