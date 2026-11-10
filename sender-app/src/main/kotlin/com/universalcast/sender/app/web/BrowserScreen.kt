package com.universalcast.sender.app.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.webkit.CookieManager
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.universalcast.playback.MediaHeaders
import com.universalcast.playback.PlaybackStatus
import com.universalcast.sender.ConnectionPhase
import com.universalcast.sender.MediaKind
import com.universalcast.sender.MediaSniffer
import com.universalcast.sender.app.ui.AppViewModel
import com.universalcast.sender.app.ui.Chip
import com.universalcast.sender.app.ui.Palette
import com.universalcast.sender.app.ui.formatTime

/**
 * A small browser for sites without a Cast button. The user plays the video as usual; every request the page makes
 * (including from iframes and service workers) is checked by [MediaSniffer], and the streams found are offered for
 * casting with the headers the page used. The TV then fetches the stream itself; the phone only stays a remote.
 *
 * Hardening: no JavaScript bridge into the app, no file/content access, popups and non-web schemes (intent://,
 * market://…) are blocked, and cleartext follows the app's policy (https only).
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(vm: AppViewModel, onClose: () -> Unit, onOpenRemote: () -> Unit) {
    val rt = vm.rt
    val st by rt.state.collectAsState()
    val scope = rememberCoroutineScope()
    val candidates = remember { MediaCandidates(scope) }
    val found by candidates.items.collectAsState()
    var address by remember { mutableStateOf(vm.browserUrl.value ?: "") }
    var progress by remember { mutableIntStateOf(100) }
    var pageTitle by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    // Page state the network threads read (Compose state is not meant for them).
    val current = remember {
        object {
            @Volatile var url: String? = null
            @Volatile var title: String? = null
            /** Last iframe document loaded: embedded players (Vidstream, …) live there, and their CDN expects it as Referer. */
            @Volatile var frameUrl: String? = null
            @Volatile var userAgent: String? = null
        }
    }

    fun inspect(request: WebResourceRequest) {
        val url = request.url?.toString() ?: return
        val headers = request.requestHeaders ?: emptyMap()
        if (!request.isForMainFrame && isDocumentRequest(headers) && (url.startsWith("https://") || url.startsWith("http://"))) {
            current.frameUrl = url
        }
        val kind = MediaSniffer.classifyUrl(url) ?: return
        candidates.offer(url, kind, current.title, current.frameUrl ?: current.url, headers, current.userAgent)
    }

    BackHandler {
        val w = webView
        if (showSheet) showSheet = false else if (w != null && w.canGoBack()) w.goBack() else onClose()
    }

    // Requests made by service workers never reach WebViewClient; watch those too while this screen is open.
    DisposableEffect(Unit) {
        val swc = runCatching { ServiceWorkerController.getInstance() }.getOrNull()
        swc?.setServiceWorkerClient(object : ServiceWorkerClient() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? { inspect(request); return null }
        })
        onDispose {
            swc?.setServiceWorkerClient(object : ServiceWorkerClient() {})
            webView?.let { it.stopLoading(); it.destroy() }
            webView = null
        }
    }

    fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("https://", true) || t.startsWith("http://", true) -> t
            ' ' !in t && '.' in t -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(t)
        }
        focus.clearFocus()
        webView?.loadUrl(url)
    }

    Scaffold(
        containerColor = Palette.Background,
        topBar = {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close browser", tint = Palette.Text) }
                    OutlinedTextField(
                        value = address, onValueChange = { address = it }, singleLine = true,
                        placeholder = { Text("Search or enter a website") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { go(address) }),
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    IconButton(onClick = { webView?.reload() }) { Icon(Icons.Filled.Refresh, "Reload", tint = Palette.Text) }
                }
                if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            CastBar(
                count = found.size, connected = st.phase == ConnectionPhase.CONNECTED && st.canControl,
                tvName = st.receiver?.name, nowPlaying = st.snapshot.title.takeIf { st.snapshot.status != PlaybackStatus.IDLE },
                tvError = st.snapshot.error?.message.takeIf { st.snapshot.status == PlaybackStatus.ERROR },
                playing = st.snapshot.status == PlaybackStatus.PLAYING,
                canGoBack = canGoBack, onBack = { webView?.goBack() },
                onCast = { showSheet = true },
                onPlayPause = { if (st.snapshot.status == PlaybackStatus.PLAYING) rt.controller.pause() else rt.controller.resume() },
                onRemote = onOpenRemote,
            )
        },
    ) { pad ->
        AndroidView(
            modifier = Modifier.padding(pad).fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = true
                        allowFileAccess = false
                        allowContentAccess = false
                        setSupportMultipleWindows(true) // so onCreateWindow sees popups and can refuse them
                        javaScriptCanOpenWindowsAutomatically = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    }
                    // Embedded players usually live in a third-party iframe that needs its own cookies.
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            inspect(request); return null // observe only; the page loads normally
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val scheme = request.url.scheme?.lowercase()
                            return scheme != "http" && scheme != "https" // block intent://, market://, app deep links
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            if (url != current.url) candidates.clear()
                            current.url = url; current.title = null; current.frameUrl = null
                            address = url; vm.browserUrl.value = url
                        }

                        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                            current.url = url; address = url; vm.browserUrl.value = url; canGoBack = view.canGoBack()
                        }

                        override fun onPageFinished(view: WebView, url: String) { canGoBack = view.canGoBack() }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                        override fun onReceivedTitle(view: WebView, title: String?) { current.title = title; pageTitle = title }
                        // Ad popups: never open new windows.
                        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) = false
                    }
                    current.userAgent = settings.userAgentString
                    webView = this
                    val start = vm.browserUrl.value
                    if (start != null) loadUrl(start)
                }
            },
        )
        if (webView != null && vm.browserUrl.value == null && address.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Open the site with the video, start it playing, then tap Cast.\nUniversalCast finds the stream the page plays (HLS .m3u8, DASH, MP4…) and sends it to the TV.",
                    color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }, containerColor = Palette.Surface) {
            CastSheet(
                found = found, connected = st.phase == ConnectionPhase.CONNECTED && st.canControl,
                tvName = st.receiver?.name ?: "TV",
                onCast = { c, variantUrl ->
                    val headers = LinkedHashMap(c.headers)
                    val target = variantUrl ?: c.url
                    // Only the cookies of the stream's own host, and only if it has any.
                    CookieManager.getInstance().getCookie(target)?.takeIf { it.isNotBlank() && it.length <= 8192 }?.let { headers[MediaHeaders.COOKIE] = it }
                    rt.controller.load(target, c.pageTitle ?: pageTitle, mimeType = c.kind.mimeType, headers = headers)
                    // Don't play twice: silence the page's own player.
                    webView?.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}})", null)
                    showSheet = false
                },
            )
        }
    }
}

@Composable
private fun CastBar(
    count: Int, connected: Boolean, tvName: String?, nowPlaying: String?, tvError: String?, playing: Boolean,
    canGoBack: Boolean, onBack: () -> Unit, onCast: () -> Unit, onPlayPause: () -> Unit, onRemote: () -> Unit,
) {
    Surface(color = Palette.Surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (tvError != null && connected) {
                Text("${tvName ?: "TV"} couldn't play it: $tvError", color = Palette.Danger, style = MaterialTheme.typography.bodySmall)
            }
            if (nowPlaying != null && connected) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("On ${tvName ?: "TV"}: $nowPlaying", color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onPlayPause) { Text(if (playing) "Pause" else "Play") }
                    TextButton(onClick = onRemote) { Text("Remote") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onBack, enabled = canGoBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = if (canGoBack) Palette.Text else Palette.TextDim) }
                Text(
                    when {
                        count == 0 -> "Play the video on the page to find it"
                        count == 1 -> "1 stream found"
                        else -> "$count streams found"
                    },
                    color = Palette.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                )
                Button(onClick = onCast, enabled = count > 0) { Text(if (connected) "Cast" else "Cast…") }
            }
        }
    }
}

@Composable
private fun CastSheet(
    found: List<MediaCandidate>, connected: Boolean, tvName: String,
    onCast: (MediaCandidate, String?) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Cast to $tvName", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
        if (!connected) {
            Text("Not connected to a TV you can control. Go back to the remote, connect to your TV, then return here.", color = Palette.Warn, style = MaterialTheme.typography.bodySmall)
        }
        Text("The most likely episode is on top. Short items are usually ads.", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(found, key = { it.url }) { c -> CandidateRow(c, connected, onCast) }
        }
    }
}

@Composable
private fun CandidateRow(c: MediaCandidate, enabled: Boolean, onCast: (MediaCandidate, String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val p = c.probe
    Surface(color = Palette.SurfaceHigh, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(c.kind.label, if (c.kind == MediaKind.HLS || c.kind == MediaKind.DASH) Palette.Accent else Palette.Ok)
                val facts = listOfNotNull(
                    p?.durationSec?.let { formatTime((it * 1000).toLong()) },
                    p?.variants?.takeIf { it.isNotEmpty() }?.let { v -> v.first().height?.let { "up to ${it}p" } ?: "${v.size} qualities" },
                    p?.sizeBytes?.let { "%.0f MB".format(it / 1_048_576.0) },
                    if (p == null) "checking…" else null,
                    if (p?.refused == true) "server refused (HTTP ${p.httpStatus})" else null,
                )
                Text(facts.joinToString(" · "), color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
            }
            Text(c.host, color = Palette.Text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(c.url, color = Palette.TextDim, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onCast(c, null) }, enabled = enabled) { Text(if (p?.variants.isNullOrEmpty()) "Cast" else "Cast · Auto quality") }
                if (!p?.variants.isNullOrEmpty()) OutlinedButton(onClick = { expanded = !expanded }, enabled = enabled) { Text("Quality") }
            }
            if (expanded) {
                p?.variants?.forEach { v ->
                    Text(
                        v.label, color = Palette.Text,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onCast(c, v.uri) }.padding(vertical = 10.dp, horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

/** An iframe (or page) navigation, as opposed to a script/XHR/media request. */
private fun isDocumentRequest(headers: Map<String, String>): Boolean {
    fun h(n: String) = headers.entries.firstOrNull { it.key.equals(n, ignoreCase = true) }?.value
    val dest = h("Sec-Fetch-Dest")
    if (dest != null) return dest == "iframe" || dest == "frame" || dest == "document"
    return h("Accept")?.startsWith("text/html") == true
}
