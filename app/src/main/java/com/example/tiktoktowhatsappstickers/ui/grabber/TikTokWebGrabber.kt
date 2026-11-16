package com.example.tiktoktowhatsappstickers.ui.grabber

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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

private const val TAG = "TikTokWebGrabber"

private const val DESKTOP_CHROME_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private const val JS_COMMENT_STICKER_HOOK = """
(function() {
  if (window.__sbHooked) return;
  window.__sbHooked = true;

  function safeReportPayload(url, text) {
    try {
      if (!text || text.length < 20) return;
      var u = String(url || '');
      if (u.indexOf('comment') !== -1 || u.indexOf('sticker') !== -1 || u.indexOf('/im/') !== -1 ||
          u.indexOf('emoticon') !== -1 || u.indexOf('emoji') !== -1 ||
          text.indexOf('sticker') !== -1 || text.indexOf('animate_url') !== -1 ||
          text.indexOf('video2sticker') !== -1 || text.indexOf('tiktok-dm-sticker') !== -1) {
        if (window.StickerBridgeJs && window.StickerBridgeJs.onApiPayload) {
          window.StickerBridgeJs.onApiPayload(u, text);
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
          var ct = (resp.headers && resp.headers.get('content-type')) || '';
          if (!ct || ct.indexOf('json') !== -1 || ct.indexOf('text') !== -1 || ct.indexOf('javascript') !== -1) {
            resp.clone().text().then(function(t) { safeReportPayload(reqUrl, t); }).catch(function(){});
          }
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
          safeReportPayload(this.__sbUrl || '', this.responseText);
        } else if (this.responseType === 'json' && this.response) {
          safeReportPayload(this.__sbUrl || '', JSON.stringify(this.response));
        }
      } catch (e) {}
    });
    return origSend.apply(this, arguments);
  };

  function scanEmbeddedJson() {
    try {
      var ids = ['__UNIVERSAL_DATA_FOR_REHYDRATION__', 'SIGI_STATE', '__NEXT_DATA__'];
      for (var i = 0; i < ids.length; i++) {
        var el = document.getElementById(ids[i]);
        if (el && el.textContent && el.textContent.length > 50) {
          safeReportPayload('ssr://' + ids[i], el.textContent);
        }
      }
    } catch (e) {}
  }

  function reportUrl(u) {
    if (!u || typeof u !== 'string') return;
    if (u.indexOf('https://') !== 0) return;
    var low = u.toLowerCase();
    if (low.indexOf('avt-') !== -1 || low.indexOf('avatar') !== -1) return;
    if (low.indexOf('sticker') !== -1 || low.indexOf('video2sticker') !== -1 ||
        low.indexOf('.awebp') !== -1 || low.indexOf('giphy.com/media') !== -1 ||
        low.indexOf('emoticon') !== -1 || low.indexOf('comment-sign') !== -1) {
      if (window.StickerBridgeJs && window.StickerBridgeJs.onStickerUrl) {
        window.StickerBridgeJs.onStickerUrl(u);
      }
    }
  }

  function scanDomMedia() {
    try {
      var imgs = document.querySelectorAll('img');
      for (var i = 0; i < imgs.length; i++) {
        reportUrl(imgs[i].currentSrc || imgs[i].src || '');
      }
      var styled = document.querySelectorAll('[style*="background-image"]');
      for (var j = 0; j < styled.length; j++) {
        var bg = styled[j].style.backgroundImage || '';
        var m = bg.match(/url\(["']?(https:\/\/[^"')]+)["']?\)/i);
        if (m && m[1]) reportUrl(m[1]);
      }
    } catch (e) {}
  }

  function ensureDesktopViewportAndHeight() {
    try {
      var isDesktop = navigator.userAgent.indexOf('Windows NT') !== -1;
      if (!isDesktop) {
        var oldFix = document.getElementById('__sbDesktopFix');
        if (oldFix) oldFix.remove();
        return;
      }
      var head = document.head || document.documentElement;
      if (head) {
        var vp = document.querySelector('meta[name="viewport"]');
        if (!vp) {
          vp = document.createElement('meta');
          vp.name = 'viewport';
          head.appendChild(vp);
        }
        if (vp.content.indexOf('1024') === -1) {
          vp.content = 'width=1024';
        }
        // Android WebView with useWideViewPort collapses CSS 100vh to 0px on TikTok desktop layout;
        // enforce pixel min-height based on actual viewport/screen height so video & comments render.
        var app = document.querySelector('#app');
        var appH = app ? app.getBoundingClientRect().height : 0;
        var fix = document.getElementById('__sbDesktopFix');
        if (!fix || appH < 250) {
          var h = Math.max(window.innerHeight || 0, Math.round((window.screen && screen.height ? screen.height : 800) * 1.25), 1300);
          if (!fix) {
            fix = document.createElement('style');
            fix.id = '__sbDesktopFix';
            head.appendChild(fix);
          }
          fix.textContent =
            'html, body, #app, [class*="BaseBodyContainer"], [class*="DivMainContainer"], ' +
            '[class*="DivVideoDetailContainer"], [class*="DivContentContainer"], [class*="DivVideoList"], ' +
            '[class*="DivLeftContainer"], [class*="RightPanelContainer"] { min-height: ' + h + 'px !important; }\n' +
            '[class*="DivCommentMain"] { min-height: ' + Math.max(h - 220, 650) + 'px !important; max-height: ' + Math.max(h - 140, 820) + 'px !important; overflow-y: auto !important; }';
        }
      }
    } catch (e) {}
  }

  function dismissPopups() {
    try {
      var sels = [
        '[data-e2e="alt-middle-cta-cancel-btn"]',
        '[data-e2e="modal-close-inner-button"]'
      ];
      for (var i = 0; i < sels.length; i++) {
        var el = document.querySelector(sels[i]);
        if (el && el.offsetParent !== null) el.click();
      }
    } catch (e) {}
  }

  window.__sbUserTouched = false;
  window.__sbScrollSteps = 0;
  window.addEventListener('touchstart', function() { window.__sbUserTouched = true; }, { passive: true });

  var commentClicked = false;
  function maybeOpenAndScrollComments(force) {
    try {
      ensureDesktopViewportAndHeight();
      dismissPopups();
      if (location.pathname.indexOf('/video/') === -1) return;
      if (!commentClicked) {
        var btn = document.querySelector('[data-e2e="comment-icon"], [data-e2e="browse-comment-icon"], button[aria-label*="comment" i], [aria-label*="Comment" i]');
        if (btn) {
          btn.click();
          commentClicked = true;
        }
      }
      if (!force && (window.__sbUserTouched || window.__sbScrollSteps >= 8)) return;
      window.__sbScrollSteps++;
      var list = document.querySelector('[class*="DivCommentMain"], [class*="DivCommentListContainer"], [data-e2e="comment-list"], [class*="CommentList"]');
      if (list && list.scrollHeight > list.clientHeight) {
        list.scrollBy(0, 520);
      } else {
        window.scrollBy(0, 520);
      }
      var replyBtns = document.querySelectorAll('[class*="DivViewRepliesContainer"]');
      if (replyBtns.length > 0 && window.__sbScrollSteps <= 4) {
        replyBtns[0].click();
      }
    } catch (e) {}
  }

  window.__sbForceScroll = function() {
    window.__sbUserTouched = false;
    window.__sbScrollSteps = 0;
    maybeOpenAndScrollComments(true);
  };

  document.addEventListener('DOMContentLoaded', function() {
    ensureDesktopViewportAndHeight();
    scanEmbeddedJson();
    scanDomMedia();
    setTimeout(function() { maybeOpenAndScrollComments(false); }, 1100);
  });
  setInterval(function() {
    ensureDesktopViewportAndHeight();
    dismissPopups();
    scanDomMedia();
    maybeOpenAndScrollComments(false);
  }, 2200);
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TikTokWebGrabberDialog(
    initialUrl: String,
    title: String,
    startInDesktopMode: Boolean = false,
    onDismiss: () -> Unit,
    onImportStickers: (packName: String, raws: List<StickerLibrary.Raw>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val mobileUa = remember {
        WebSettings.getDefaultUserAgent(context)
            .replace("; wv)", ")")
            .replace("; wv;", ";")
    }
    var desktopMode by remember { mutableStateOf(startInDesktopMode) }

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
    var statusText by remember { mutableStateOf("Opening TikTok…") }
    var isSaving by remember { mutableStateOf(false) }

    fun activeUa() = if (desktopMode) DESKTOP_CHROME_UA else mobileUa

    fun isLikelyStickerUrl(url: String): Boolean {
        val low = url.lowercase()
        if (!low.startsWith("https://")) return false
        val isSupportedCdn =
            "ibyteimg.com" in low || "tiktokcdn" in low || "byteoversea" in low ||
                "byteimg.com" in low || "ibytedtos.com" in low || "ttwstatic.com" in low ||
                "tiktokv.com" in low || "muscdn.com" in low || "giphy.com" in low
        if (!isSupportedCdn) return false
        if ("avt-" in low || "sc=avatar" in low || "avatar" in low || "favicon" in low || "logo" in low ||
            "qrcode" in low || "photomode" in low
        ) {
            return false
        }
        return "tiktok-dm-sticker" in low || "video2sticker" in low || "sticker" in low ||
            "emoticon" in low || ".awebp" in low || "giphy.com/media" in low ||
            "comment-sign" in low || "comment_photo" in low
    }

    fun canonicalKey(url: String): String = url.substringBefore('?').substringBefore('~')

    fun enqueueCandidateUrl(url: String, trustedSticker: Boolean = false) {
        val lowCheck = url.lowercase()
        if ("avt-" in lowCheck || "avatar" in lowCheck) return
        if (!trustedSticker && !isLikelyStickerUrl(url)) return
        val canon = canonicalKey(url)
        if (!seenCanonicalUrls.add(canon)) return

        scope.launch(Dispatchers.IO) {
            try {
                val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", activeUa())
                    .header("Referer", "https://www.tiktok.com/")
                    .header("Accept", "image/webp,image/apng,image/gif,image/png,image/*,*/*;q=0.8")
                if (!cookies.isNullOrBlank()) reqBuilder.header("Cookie", cookies)

                httpClient.newCall(reqBuilder.build()).execute().use { res ->
                    if (!res.isSuccessful) {
                        Log.d(TAG, "Download HTTP ${res.code} for $canon")
                        return@launch
                    }
                    val bytes = res.body?.bytes() ?: return@launch
                    val verdict = StickerClassifier.classify(bytes, canon, url, trustAsSticker = trustedSticker)
                    if (!verdict.accept) {
                        Log.d(TAG, "Rejected (${verdict.reason}) for $canon")
                        return@launch
                    }

                    val id = StickerLibrary.idOf(bytes)
                    if (!seenContentIds.add(id)) return@launch

                    val anim = WebpProcessingEngine.isAnimatedBytes(bytes)
                    val ext = when (WebpProcessingEngine.sniff(bytes)) {
                        WebpProcessingEngine.Kind.GIF -> "gif"
                        WebpProcessingEngine.Kind.PNG -> "png"
                        WebpProcessingEngine.Kind.JPEG -> "jpg"
                        else -> "webp"
                    }
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
                    Log.d(TAG, "Captured sticker ${item.key} (${bytes.size} bytes, anim=$anim)")
                    scope.launch(Dispatchers.Main) {
                        captured = captured + item
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed fetching candidate $canon: ${e.message}")
            }
        }
    }

    fun pickUrlFromMediaObj(obj: JSONObject?): String? {
        if (obj == null) return null
        val arr = obj.optJSONArray("url_list")
        if (arr != null && arr.length() > 0) {
            val u = arr.optString(0)
            if (u.startsWith("http")) return u
        }
        val direct = obj.optString("url")
        return direct.takeIf { it.startsWith("http") }
    }

    fun parseJsonNodeForStickers(node: Any?) {
        when (node) {
            is JSONObject -> {
                for (keyName in listOf("sticker", "comment_sticker", "sticker_info")) {
                    node.optJSONObject(keyName)?.let { sObj ->
                        val chosen = pickUrlFromMediaObj(sObj.optJSONObject("animate_url"))
                            ?: pickUrlFromMediaObj(sObj.optJSONObject("static_url"))
                            ?: pickUrlFromMediaObj(sObj.optJSONObject("origin"))
                            ?: pickUrlFromMediaObj(sObj.optJSONObject("thumbnail"))
                            ?: pickUrlFromMediaObj(sObj)
                        if (chosen != null) enqueueCandidateUrl(chosen, trustedSticker = true)
                    }
                }
                val directAnim = pickUrlFromMediaObj(node.optJSONObject("animate_url"))
                    ?: pickUrlFromMediaObj(node.optJSONObject("sticker_url"))
                if (directAnim != null) enqueueCandidateUrl(directAnim, trustedSticker = true)

                node.optJSONArray("image_list")?.let { imgArr ->
                    for (i in 0 until imgArr.length()) {
                        val imgObj = imgArr.optJSONObject(i) ?: continue
                        val chosen = pickUrlFromMediaObj(imgObj.optJSONObject("origin_url"))
                            ?: pickUrlFromMediaObj(imgObj.optJSONObject("medium_url"))
                            ?: pickUrlFromMediaObj(imgObj.optJSONObject("crop_url"))
                            ?: pickUrlFromMediaObj(imgObj.optJSONObject("thumbnail_url"))
                        if (chosen != null) enqueueCandidateUrl(chosen, trustedSticker = true)
                    }
                }

                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (v is String && v.startsWith("https://")) {
                        val trusted = "sticker" in k.lowercase() && "avatar" !in k.lowercase()
                        if (trusted || isLikelyStickerUrl(v)) {
                            enqueueCandidateUrl(v, trustedSticker = trusted)
                        }
                    } else if (v is JSONObject || v is JSONArray) {
                        parseJsonNodeForStickers(v)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    parseJsonNodeForStickers(node.opt(i))
                }
            }
            is String -> {
                if (node.startsWith("https://") && isLikelyStickerUrl(node)) {
                    enqueueCandidateUrl(node, trustedSticker = false)
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
                // Top Header + Action Strip
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
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
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = TikTokCyan,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { webViewRef?.reload() }, modifier = Modifier.size(34.dp)) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = "Reload", modifier = Modifier.size(19.dp))
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 6.dp, end = 6.dp, top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                desktopMode = !desktopMode
                                webViewRef?.let { wv ->
                                    wv.settings.userAgentString = activeUa()
                                    wv.settings.useWideViewPort = desktopMode
                                    wv.settings.loadWithOverviewMode = desktopMode
                                    wv.reload()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text(
                                text = if (desktopMode) "Mobile View" else "Desktop View",
                                style = MaterialTheme.typography.labelSmall,
                                color = TikTokCyan,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                desktopMode = false
                                webViewRef?.let { wv ->
                                    wv.settings.userAgentString = mobileUa
                                    wv.settings.useWideViewPort = false
                                    wv.settings.loadWithOverviewMode = false
                                    wv.loadUrl("https://www.tiktok.com/login")
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text(
                                text = "Log In",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                desktopMode = true
                                webViewRef?.let { wv ->
                                    wv.settings.userAgentString = DESKTOP_CHROME_UA
                                    wv.settings.useWideViewPort = true
                                    wv.settings.loadWithOverviewMode = true
                                    wv.loadUrl("https://www.tiktok.com/messages")
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text(
                                text = "DMs",
                                style = MaterialTheme.typography.labelSmall,
                                color = TikTokPink,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                webViewRef?.evaluateJavascript(
                                    "if (window.__sbForceScroll) window.__sbForceScroll();",
                                    null
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text(
                                text = "Scan More",
                                style = MaterialTheme.typography.labelSmall,
                                color = WhatsAppGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
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
                            WebView.setWebContentsDebuggingEnabled(true)
                            CookieManager.getInstance().setAcceptCookie(true)
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                setBackgroundColor(android.graphics.Color.BLACK)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.databaseEnabled = true
                                settings.userAgentString = activeUa()
                                settings.loadWithOverviewMode = desktopMode
                                settings.useWideViewPort = desktopMode
                                settings.setSupportZoom(true)
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                settings.mediaPlaybackRequiresUserGesture = true
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                                    runCatching {
                                        WebViewCompat.addDocumentStartJavaScript(
                                            this,
                                            JS_COMMENT_STICKER_HOOK,
                                            setOf("*")
                                        )
                                    }
                                }

                                addJavascriptInterface(
                                    object {
                                        @JavascriptInterface
                                        fun onApiPayload(url: String, payload: String) {
                                            Log.d(TAG, "API payload ${payload.length} chars from $url")
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
                                            enqueueCandidateUrl(url, trustedSticker = false)
                                        }
                                    },
                                    "StickerBridgeJs"
                                )

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        loadProgress = newProgress
                                        if (newProgress >= 100) pageLoading = false
                                    }

                                    override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                                        if (msg != null && msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                            Log.w(TAG, "JS Error: ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                                        }
                                        return super.onConsoleMessage(msg)
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        pageLoading = true
                                        url?.let { currentUrl = it }
                                        statusText = "Loading & scanning page…"
                                        view?.evaluateJavascript(JS_COMMENT_STICKER_HOOK, null)
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        pageLoading = false
                                        url?.let { currentUrl = it }
                                        statusText = if (captured.isEmpty()) {
                                            "Scroll comments or open a DM chat to grab stickers"
                                        } else {
                                            "Captured ${captured.size} sticker(s)"
                                        }
                                        view?.evaluateJavascript(JS_COMMENT_STICKER_HOOK, null)
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?
                                    ) {
                                        if (request?.isForMainFrame == true) {
                                            statusText = "Page error: ${error?.description ?: "Failed to load"}"
                                            Log.e(TAG, "MainFrame error ${error?.errorCode}: ${error?.description}")
                                        }
                                    }

                                    override fun onReceivedHttpError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        errorResponse: WebResourceResponse?
                                    ) {
                                        if (request?.isForMainFrame == true) {
                                            Log.w(TAG, "HTTP ${errorResponse?.statusCode} for ${request.url}")
                                        }
                                    }

                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        val reqUri = request?.url ?: return false
                                        val scheme = reqUri.scheme?.lowercase() ?: return false
                                        if (scheme == "http" || scheme == "https") return false
                                        // Prevent TikTok web from bouncing to Play Store or native app; follow browser fallback URL if present.
                                        if (scheme == "intent") {
                                            runCatching {
                                                val parsed = Intent.parseUri(reqUri.toString(), Intent.URI_INTENT_SCHEME)
                                                val fallback = parsed.getStringExtra("browser_fallback_url")
                                                if (!fallback.isNullOrBlank() && fallback.startsWith("http")) {
                                                    view?.loadUrl(fallback)
                                                }
                                            }
                                        }
                                        return true
                                    }

                                    override fun shouldInterceptRequest(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): WebResourceResponse? {
                                        val reqUrl = request?.url?.toString()
                                        if (reqUrl != null && isLikelyStickerUrl(reqUrl)) {
                                            enqueueCandidateUrl(reqUrl, trustedSticker = false)
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
                                text = if (captured.isEmpty()) "Listening for comment & chat stickers…" else "${captured.size} Unique Stickers Grabbed",
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
