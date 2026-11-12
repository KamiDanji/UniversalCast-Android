package com.universalcast.sender.app.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Message
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
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
import com.universalcast.sender.AdBlocker
import com.universalcast.sender.FilterEngine
import com.universalcast.sender.ReqType
import com.universalcast.sender.BrowserCast
import com.universalcast.sender.CandidateList
import com.universalcast.sender.CastHeaders
import com.universalcast.sender.ConnectionPhase
import com.universalcast.sender.HlsRole
import com.universalcast.sender.MediaCast
import com.universalcast.sender.MediaKind
import com.universalcast.sender.MediaSniffer
import com.universalcast.sender.PageScan
import com.universalcast.sender.PairedReceiver
import com.universalcast.sender.ProbeOutcome
import com.universalcast.sender.StreamCandidate
import com.universalcast.sender.StreamProbe
import com.universalcast.sender.SubtitleCandidate
import com.universalcast.sender.app.ui.AppViewModel
import com.universalcast.sender.app.ui.Chip
import com.universalcast.sender.app.ui.Palette
import com.universalcast.sender.app.ui.RemoteScreen
import com.universalcast.sender.app.ui.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * What the WebView's background threads share with the UI. Everything here is thread-safe: [list] is synchronized,
 * the page fields are volatile and only written on the main thread, [version] is a StateFlow.
 */
private class PageState {
    val list = CandidateList()
    val version = MutableStateFlow(0L)
    @Volatile var currentPageUrl: String? = null
    @Volatile var title: String? = null
    /** Last iframe document loaded: embedded players live there, and their CDN expects it as Referer. */
    @Volatile var frameUrl: String? = null
    @Volatile var userAgent: String? = null

    val adBlocker = AdBlocker()
    /** uBlock-style filter lists (EasyList, uBlock filters…), once loaded; [adBlocker] covers the time before. */
    @Volatile var engine: FilterEngine? = null
    @Volatile var adBlockEnabled = true

    private fun pageHost(): String? = currentPageUrl?.let { runCatching { java.net.URI(it).host }.getOrNull() }

    /** True when [url] is an ad/tracker request. Never true for something that looks like a stream (casting must keep working). */
    fun isAd(url: String, type: ReqType): Boolean {
        if (!adBlockEnabled) return false
        if (MediaSniffer.classify(url) != MediaKind.IGNORE) return false
        return adBlocker.shouldBlock(url) || engine?.shouldBlock(url, pageHost(), type) == true
    }

    private fun typeOf(request: WebResourceRequest): ReqType {
        if (request.isForMainFrame) return ReqType.DOCUMENT
        val h = request.requestHeaders ?: emptyMap()
        val accept = (h["Accept"] ?: h["accept"] ?: "").lowercase()
        val path = request.url?.path?.lowercase() ?: ""
        return when {
            accept.contains("text/html") -> ReqType.SUBDOCUMENT
            accept.startsWith("image/") || path.endsWithAny(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".avif") -> ReqType.IMAGE
            accept.startsWith("text/css") || path.endsWith(".css") -> ReqType.STYLESHEET
            path.endsWith(".js") || path.endsWith(".mjs") -> ReqType.SCRIPT
            h["X-Requested-With"] == "XMLHttpRequest" -> ReqType.XHR
            path.endsWithAny(".mp4", ".webm", ".m3u8", ".mpd", ".ts", ".m4s", ".mp3") -> ReqType.MEDIA
            else -> ReqType.OTHER
        }
    }

    private fun String.endsWithAny(vararg s: String) = s.any { endsWith(it) }

    /** Script-started navigation of the page to another site without a tap: ad redirects and pop-unders. */
    fun isSneakyRedirect(request: WebResourceRequest): Boolean {
        if (!adBlockEnabled || !request.isForMainFrame || request.hasGesture() || request.isRedirect) return false
        val from = pageHost() ?: return false
        val to = request.url?.host ?: return false
        return FilterEngine.registrable(from) != FilterEngine.registrable(to)
    }
    /** Ad requests, pop-ups and ad redirects blocked on this screen. */
    val blocked = MutableStateFlow(0)

    fun countBlocked() { blocked.value = blocked.value + 1 }

    fun bump() { version.value = list.version }

    /**
     * Called on WebView network threads for every request of every frame: fast, never blocks, never throws.
     * Returns an empty response for an ad request (sub-resources and frames; the main page is handled in
     * shouldOverrideUrlLoading), else null so the page loads normally.
     */
    fun inspect(request: WebResourceRequest): WebResourceResponse? {
        try {
            val url = request.url?.toString() ?: return null
            if (!request.isForMainFrame && isAd(url, typeOf(request))) {
                countBlocked()
                return blockedResponse()
            }
            val headers = request.requestHeaders ?: emptyMap()
            if (!request.isForMainFrame && isDocumentRequest(headers) && (url.startsWith("https://") || url.startsWith("http://"))) {
                frameUrl = url
            }
            val kind = MediaSniffer.classify(url)
            if (kind == MediaKind.IGNORE) return null
            val page = frameUrl ?: currentPageUrl
            list.offer(url, CastHeaders.forwardable(url, headers, page, userAgent), page, title)
            bump()
        } catch (_: Exception) {
        }
        return null
    }

    private fun blockedResponse() =
        WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))

    fun sameSite(url: String): Boolean {
        val from = pageHost() ?: return true
        val to = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
        return FilterEngine.registrable(from) == FilterEngine.registrable(to)
    }

    /** Hides the page's ad elements (uBlock "cosmetic" rules) with an injected style sheet. */
    fun cosmeticScript(url: String): String? {
        val css = engine?.takeIf { adBlockEnabled }?.cosmeticCss(runCatching { java.net.URI(url).host }.getOrNull()) ?: return null
        if (css.isEmpty()) return null
        return "(function(){var s=document.getElementById('uc-adblock');if(!s){s=document.createElement('style');s.id='uc-adblock';" +
            "(document.head||document.documentElement).appendChild(s);}s.textContent=" + org.json.JSONObject.quote(css) + ";})();"
    }

    fun clear() { list.clear(); frameUrl = null; bump() }
}

/**
 * A small browser for sites without a Cast button. The user plays the video as usual; every request the page and its
 * iframes make is checked by [MediaSniffer] (the page loads normally, nothing is changed), the streams found are
 * offered for casting with the headers the page used, and the TV then fetches the stream itself.
 *
 * Hardening: no JavaScript bridge into the app (no addJavascriptInterface), no file/content access, one window only,
 * non-web schemes (intent:, market:, file:…) are never followed, and a crashed renderer is replaced instead of taking
 * the app down.
 *
 * Ad blocking ([AdBlocker], switchable in the menu): ad requests in every frame get an empty response, the page is
 * never redirected to an ad site, and "new tab" requests are caught: pop-ups the user didn't tap are dropped, tapped
 * links open in this view (so Back returns to the video page) unless they lead to an ad site.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(vm: AppViewModel, onClose: () -> Unit, onPairAgain: () -> Unit) {
    val rt = vm.rt
    val st by rt.state.collectAsState()
    val receivers by rt.receivers.collectAsState()
    val scope = rememberCoroutineScope()
    val page = remember { PageState() }
    val version by page.version.collectAsState()
    val streams = remember(version) { page.list.streams() }
    val subtitles = remember(version) { page.list.subtitles() }

    var address by remember { mutableStateOf(vm.browserUrl.value ?: "") }
    var addressError by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(100) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var webViewGeneration by remember { mutableIntStateOf(0) }
    var showSheet by remember { mutableStateOf(false) }
    var showRemote by remember { mutableStateOf(false) }
    var chooseTvFor by remember { mutableStateOf<MediaCast?>(null) }
    var pendingCast by remember { mutableStateOf<MediaCast?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var lastBackAtMs by remember { mutableLongStateOf(0L) }
    val adBlock by vm.browserAdBlock.collectAsState()
    val blockedCount by page.blocked.collectAsState()
    LaunchedEffect(adBlock) { page.adBlockEnabled = adBlock }
    val engine by rt.filters.engine.collectAsState()
    LaunchedEffect(engine) { page.engine = engine }
    LaunchedEffect(Unit) { rt.filters.start() }
    fun hideAds(view: WebView, url: String) { page.cosmeticScript(url)?.let { view.evaluateJavascript(it, null) } }
    val focus = LocalFocusManager.current

    fun go(input: String) {
        val url = UrlInput.normalize(input)
        if (url == null) { addressError = true; return }
        addressError = false
        focus.clearFocus()
        webView?.loadUrl(url)
    }

    /** Reads the page's DOM for <video>/<audio>/<source>/<track> (main document only). */
    fun scanPage() {
        val w = webView ?: return
        scanning = true
        w.evaluateJavascript(PageScan.SCRIPT) { result ->
            scanning = false
            val scan = PageScan.parse(result)
            scan.title?.let { page.title = it }
            val ref = page.currentPageUrl
            for (m in scan.media) page.list.offer(m, CastHeaders.forwardable(m, emptyMap(), ref, page.userAgent), ref, scan.title ?: page.title)
            // <track> files are WebVTT by the HTML spec unless the extension says otherwise.
            for (t in scan.tracks) page.list.addSubtitle(t.src, MediaSniffer.subtitleMimeType(t.src) ?: "text/vtt", t.language, t.label)
            page.bump()
            if (scan.media.isEmpty() && scan.tracks.isEmpty() && page.list.streams().isEmpty()) {
                notice = "Nothing found in this page. Start the video first; players inside other frames are found once they play."
            }
        }
    }

    fun afterCast() {
        // The phone must not keep playing once the TV does (main document; cross-origin player frames can't be reached).
        webView?.evaluateJavascript(PageScan.PAUSE_SCRIPT, null)
        showSheet = false
        showRemote = true // drawn ABOVE the still-composed browser: coming back doesn't reload the page
    }

    /** Uses the connected TV; else the only paired TV; else asks which one. */
    fun cast(cast: MediaCast) {
        val s = rt.state.value
        when {
            s.phase == ConnectionPhase.CONNECTED && s.canControl -> { rt.controller.load(cast); afterCast() }
            s.phase == ConnectionPhase.CONNECTED -> notice = "This phone is only an observer on ${s.receiver?.name ?: "this TV"}; it can't start videos."
            s.phase == ConnectionPhase.CONNECTING || s.phase == ConnectionPhase.RECONNECTING -> { pendingCast = cast; notice = "Connecting to ${s.receiver?.name ?: "the TV"}…" }
            receivers.size == 1 -> { pendingCast = cast; rt.connect(receivers.single()); notice = "Connecting to ${receivers.single().name}…" }
            receivers.isEmpty() -> notice = "Pair a TV first: go back and tap “Pair a TV”."
            else -> chooseTvFor = cast
        }
    }

    // A cast waiting for its connection.
    LaunchedEffect(st.phase, pendingCast) {
        val p = pendingCast ?: return@LaunchedEffect
        when (st.phase) {
            ConnectionPhase.CONNECTED -> {
                pendingCast = null; notice = null
                if (st.canControl) { rt.controller.load(p); afterCast() } else notice = "This phone is only an observer on ${st.receiver?.name}; it can't start videos."
            }
            ConnectionPhase.FAILED -> { pendingCast = null; notice = st.message ?: "Couldn't connect to the TV." }
            else -> {}
        }
    }

    BackHandler {
        val w = webView
        val now = System.currentTimeMillis()
        when {
            showRemote -> showRemote = false
            showSheet -> showSheet = false
            menuOpen -> menuOpen = false
            w != null && w.canGoBack() -> w.goBack()
            // At the first page: only a second Back within 2 s leaves the browser (a stray Back must not lose the page).
            now - lastBackAtMs < 2_000 -> onClose()
            else -> { lastBackAtMs = now; notice = "Press Back again to close the browser." }
        }
    }

    // Requests made by service workers never reach WebViewClient; watch those too while this screen is open.
    DisposableEffect(Unit) {
        val swc = runCatching { ServiceWorkerController.getInstance() }.getOrNull()
        runCatching {
            swc?.setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = page.inspect(request)
            })
        }
        onDispose {
            runCatching { swc?.setServiceWorkerClient(object : ServiceWorkerClient() {}) }
            webView?.let { it.stopLoading(); it.destroy() }
            webView = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Palette.Background,
            topBar = {
                Column(Modifier.background(Palette.Surface).statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Back only navigates back in the page history; closing is in the menu.
                        IconButton(onClick = { webView?.takeIf { it.canGoBack() }?.goBack() }, enabled = canGoBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = if (canGoBack) Palette.Text else Palette.TextDim)
                        }
                        OutlinedTextField(
                            value = address, onValueChange = { address = it; addressError = false }, singleLine = true,
                            placeholder = { Text("Enter website", maxLines = 1) },
                            isError = addressError,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { go(address) }),
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            shape = RoundedCornerShape(24.dp),
                        )
                        IconButton(onClick = { webView?.reload() }) { Icon(Icons.Filled.Refresh, "Reload", tint = Palette.Text) }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "Menu", tint = Palette.Text) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (adBlock) "Ad blocker: on ($blockedCount blocked)" else "Ad blocker: off") },
                                    trailingIcon = { Switch(checked = adBlock, onCheckedChange = null) },
                                    onClick = { vm.browserAdBlock.value = !adBlock; menuOpen = false; webView?.reload() },
                                )
                                DropdownMenuItem(text = { Text("Scan page") }, onClick = { menuOpen = false; scanPage() })
                                DropdownMenuItem(text = { Text("Close browser") }, onClick = { menuOpen = false; onClose() })
                            }
                        }
                    }
                    if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
            },
            bottomBar = {
                Surface(color = Palette.Surface) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (streams.isNotEmpty()) {
                            Button(onClick = { showSheet = true }, modifier = Modifier.weight(1f)) {
                                Text("▶ ${streams.size} video${if (streams.size == 1) "" else "s"} found – Cast")
                            }
                        } else {
                            Text("Play the video on the page to find it", color = Palette.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        }
                        TextButton(onClick = { scanPage() }, enabled = !scanning && webView != null) { Text(if (scanning) "Scanning…" else "Scan page") }
                        if (st.phase == ConnectionPhase.CONNECTED) TextButton(onClick = { showRemote = true }) { Text("Remote") }
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                key(webViewGeneration) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    mediaPlaybackRequiresUserGesture = true
                                    allowFileAccess = false
                                    allowContentAccess = false
                                    // New windows are routed through onCreateWindow below (pop-up filter); script-only
                                    // window.open without a tap is refused by WebView itself.
                                    setSupportMultipleWindows(true)
                                    javaScriptCanOpenWindowsAutomatically = false
                                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                    setGeolocationEnabled(false)
                                }
                                // Embedded players usually live in a third-party iframe that needs its own cookies.
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                        page.inspect(request) // observes, and blocks ad requests; the page loads normally otherwise

                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        val scheme = request.url.scheme?.lowercase()
                                        if (scheme != "http" && scheme != "https") return true // block intent://, market://, file:, app deep links
                                        // Clicks hijacked into a redirect to an ad site: stay on the page.
                                        if (request.isForMainFrame && (page.isAd(request.url.toString(), ReqType.DOCUMENT) || page.isSneakyRedirect(request))) {
                                            page.countBlocked()
                                            return true
                                        }
                                        return false
                                    }

                                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                        if (url != page.currentPageUrl) page.clear()
                                        page.currentPageUrl = url; page.title = null
                                        address = url; vm.browserUrl.value = url
                                    }

                                    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                        page.currentPageUrl = url; address = url; vm.browserUrl.value = url
                                        canGoBack = view.canGoBack()
                                    }

                                    override fun onPageCommitVisible(view: WebView, url: String) { hideAds(view, url) }

                                    override fun onPageFinished(view: WebView, url: String) { canGoBack = view.canGoBack(); hideAds(view, url) }

                                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                        // The page's renderer crashed or was killed: drop this WebView and build a new one.
                                        if (webView === view) webView = null
                                        runCatching { view.destroy() }
                                        notice = "The page crashed and was reloaded."
                                        webViewGeneration++
                                        return true
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                                    override fun onReceivedTitle(view: WebView, title: String?) { page.title = title }

                                    /**
                                     * "Open in new tab" / window.open. There are no tabs here: a pop-up the user didn't tap is
                                     * dropped; a tapped one is loaded in this view (Back returns to the page), unless it leads
                                     * to an ad site. The target URL is only known once the new window starts loading, so a
                                     * throw-away WebView catches it and is destroyed right away.
                                     */
                                    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                                        val transport = resultMsg?.obj as? WebView.WebViewTransport
                                        if (!isUserGesture || transport == null) { page.countBlocked(); return false }
                                        val catcher = WebView(view.context)
                                        var handled = false
                                        fun take(url: String?) {
                                            if (handled || url == null || url == "about:blank") return
                                            handled = true
                                            catcher.stopLoading()
                                            view.post { runCatching { catcher.destroy() } }
                                            when {
                                                !url.startsWith("http://", true) && !url.startsWith("https://", true) -> page.countBlocked()
                                                page.isAd(url, ReqType.DOCUMENT) -> page.countBlocked()
                                                // A new tab on another site than the page is almost always an ad; same-site links are fine.
                                                page.adBlockEnabled && !page.sameSite(url) -> page.countBlocked()
                                                else -> view.loadUrl(url)
                                            }
                                        }
                                        catcher.webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean { take(r.url.toString()); return true }
                                            override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) { take(url) }
                                        }
                                        transport.webView = catcher
                                        resultMsg.sendToTarget()
                                        // A window that never navigates anywhere is cleaned up as well.
                                        view.postDelayed({ if (!handled) { handled = true; runCatching { catcher.destroy() } } }, 10_000)
                                        return true
                                    }
                                }
                                page.userAgent = settings.userAgentString
                                webView = this
                                vm.browserUrl.value?.let { loadUrl(it) }
                            }
                        },
                    )
                }
                if (address.isEmpty()) {
                    Column(Modifier.fillMaxSize().background(Palette.Background).padding(24.dp), verticalArrangement = Arrangement.Center) {
                        Text("Cast from a website", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Open the page with the video and start it playing. UniversalCast finds the stream the page plays " +
                                "(HLS .m3u8, DASH, MP4…) and the TV plays it directly.",
                            color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                notice?.let { n ->
                    Surface(color = Palette.SurfaceHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
                        Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(n, color = Palette.Text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false))
                            TextButton(onClick = { notice = null }) { Text("OK") }
                        }
                    }
                }
            }
        }

        if (showSheet) {
            ModalBottomSheet(onDismissRequest = { showSheet = false }, containerColor = Palette.Surface) {
                CastSheet(
                    streams = streams, subtitles = subtitles,
                    tvName = st.receiver?.name?.takeIf { st.phase != ConnectionPhase.DISCONNECTED }
                        ?: receivers.singleOrNull()?.name ?: "TV",
                    onScan = { scanPage() },
                    onProbe = { candidate ->
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                probeLimit.withPermit {
                                    val cookie = runCatching { CookieManager.getInstance().getCookie(candidate.url) }.getOrNull()
                                    val headers = BrowserCast.build(candidate, emptyList(), null, cookie).headers
                                    StreamProbe().probe(candidate.url, headers)
                                }
                            }
                            page.list.applyProbe(candidate.url, result)
                            page.bump()
                        }
                    },
                    onCast = { candidate, uriOverride, subs ->
                        val target = if (uriOverride != null) candidate.copy(url = uriOverride) else candidate
                        // Cookies are read now, for the stream's own URL only (the TV sends them only to that host).
                        val cookie = runCatching { CookieManager.getInstance().getCookie(target.url) }.getOrNull()
                        cast(BrowserCast.build(target, subs, page.title, cookie))
                    },
                )
            }
        }

        chooseTvFor?.let { pending ->
            AlertDialog(
                onDismissRequest = { chooseTvFor = null },
                title = { Text("Cast to which TV?") },
                text = {
                    Column {
                        receivers.forEach { r: PairedReceiver ->
                            Text(r.name, color = Palette.Text, modifier = Modifier.fillMaxWidth().clickable {
                                chooseTvFor = null; pendingCast = pending; rt.connect(r); notice = "Connecting to ${r.name}…"
                            }.padding(vertical = 14.dp))
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { chooseTvFor = null }) { Text("Cancel") } },
            )
        }

        if (showRemote) {
            RemoteScreen(vm, onClose = { showRemote = false }, onPairAgain = onPairAgain, onBrowse = { showRemote = false })
        }
    }
}

private val probeLimit = Semaphore(3)

@Composable
private fun CastSheet(
    streams: List<StreamCandidate>,
    subtitles: List<SubtitleCandidate>,
    tvName: String,
    onScan: () -> Unit,
    onProbe: (StreamCandidate) -> Unit,
    onCast: (StreamCandidate, String?, List<SubtitleCandidate>) -> Unit,
) {
    // Probe lazily, once per candidate, when the sheet is open.
    val probed = remember { HashSet<String>() }
    LaunchedEffect(streams) { streams.filter { it.probe == null && probed.add(it.url) }.forEach(onProbe) }

    var selectedUrl by remember { mutableStateOf<String?>(null) }
    val selected = streams.firstOrNull { it.url == selectedUrl } ?: streams.firstOrNull { !it.isProtected } ?: streams.firstOrNull()
    var variantUri by remember(selected?.url) { mutableStateOf<String?>(null) }
    var chosenSubs by remember { mutableStateOf<Set<String>?>(null) }
    // Default: the first English-looking subtitle (if any).
    val subsSelection = chosenSubs ?: setOfNotNull(subtitles.firstOrNull { MediaSniffer.looksEnglish(it.language, it.label) }?.url)

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Videos on this page", style = MaterialTheme.typography.titleMedium, color = Palette.Text)
        if (streams.isEmpty()) {
            Text(
                "Nothing castable was found yet. Common reasons:\n" +
                    "• The video hasn't started: play it on the page first (and skip ads).\n" +
                    "• The player uses DRM (Widevine/FairPlay, e.g. Netflix, Disney+): it can't be cast this way.\n" +
                    "• The player only uses blob: URLs and loads the stream in a way that isn't visible; try Scan page.\n" +
                    "• Only ads have played so far.",
                color = Palette.TextDim, style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onScan) { Text("Scan page") }
            return@Column
        }
        LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(streams, key = { it.url }) { c -> CandidateRow(c, c.url == selected?.url) { selectedUrl = c.url } }
        }
        val variants = selected?.probe?.variants.orEmpty()
        if (variants.isNotEmpty()) {
            Text("Quality", color = Palette.TextDim, style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = variantUri == null, onClick = { variantUri = null }, label = { Text("Auto") })
                variants.take(4).forEach { v -> FilterChip(selected = variantUri == v.uri, onClick = { variantUri = v.uri }, label = { Text(v.height?.let { "${it}p" } ?: "?") }) }
            }
        }
        if (subtitles.isNotEmpty()) {
            Text("Subtitles", color = Palette.TextDim, style = MaterialTheme.typography.labelMedium)
            subtitles.take(8).forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = s.url in subsSelection, onCheckedChange = { on -> chosenSubs = if (on) subsSelection + s.url else subsSelection - s.url })
                    Text(listOfNotNull(s.label, s.language?.uppercase()).joinToString(" · ").ifEmpty { s.url.substringAfterLast('/').substringBefore('?').take(40) },
                        color = Palette.Text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (selected?.isProtected == true) {
            Text("This stream is DRM-protected (Widevine/FairPlay). It can't be cast by any method in this app.", color = Palette.Danger, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = { selected?.let { onCast(it, variantUri, subtitles.filter { s -> s.url in subsSelection }) } },
            enabled = selected != null && !selected.isProtected,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Cast to $tvName") }
        Text(
            "The TV fetches the stream itself. Links that are one-time, or bound to this phone's IP, may not play on the TV.",
            color = Palette.TextDim, style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onScan) { Text("Scan page again") }
    }
}

@Composable
private fun CandidateRow(c: StreamCandidate, selected: Boolean, onSelect: () -> Unit) {
    val p = c.probe
    Surface(
        color = if (selected) Palette.Accent.copy(alpha = 0.18f) else Palette.SurfaceHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioButton(selected = selected, onClick = onSelect)
                val label = if (c.kind == MediaKind.HLS && c.hlsRole == HlsRole.MASTER) "HLS (all qualities)" else c.kind.label
                Chip(label, if (c.kind == MediaKind.HLS || c.kind == MediaKind.DASH) Palette.Accent else Palette.Ok)
                if (c.isProtected) Chip("DRM", Palette.Danger)
            }
            val facts = listOfNotNull(
                p?.variants?.mapNotNull { it.height }?.maxOrNull()?.let { "up to ${it}p" },
                p?.durationSec?.takeIf { p.live != true }?.let { formatTime((it * 1000).toLong()) },
                if (p?.live == true) "live" else null,
                p?.sizeBytes?.let { "%.0f MB".format(it / 1_048_576.0) },
                p?.subtitleTracks?.takeIf { it.isNotEmpty() }?.let { "built-in subtitles" },
                when {
                    p == null -> "checking…"
                    p.outcome == ProbeOutcome.FAILED -> "unverified"
                    else -> null
                },
            )
            if (facts.isNotEmpty()) Text(facts.joinToString(" · "), color = Palette.TextDim, style = MaterialTheme.typography.bodySmall)
            p?.problem?.takeIf { p.outcome != ProbeOutcome.FAILED && !c.isProtected }?.let { Text(it, color = Palette.Warn, style = MaterialTheme.typography.bodySmall) }
            Text(c.host, color = Palette.Text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(c.url, color = Palette.TextDim, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
