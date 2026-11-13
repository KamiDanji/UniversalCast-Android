package com.universalcast.sender.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.universalcast.sender.MediaSniffer
import com.universalcast.sender.app.Links
import com.universalcast.sender.app.SenderApp
import com.universalcast.sender.app.web.BrowserScreen

private enum class Screen { HOME, PAIR, REMOTE, BROWSER }

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShare(intent)
        // Android 13+: the "connected to TV" notification needs this; without it the service still runs, just unseen.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            SenderTheme {
                UpdatePrompt((application as SenderApp).updates)
                var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                val openBrowser by vm.openBrowserRequest.collectAsState()
                LaunchedEffect(openBrowser) { if (openBrowser) { screen = Screen.BROWSER; vm.openBrowserRequest.value = false } }
                // The browser handles Back itself (page history first).
                BackHandler(enabled = screen != Screen.HOME && screen != Screen.BROWSER) { screen = Screen.HOME }
                when (screen) {
                    Screen.HOME -> HomeScreen(vm, onPair = { screen = Screen.PAIR }, onOpenRemote = { screen = Screen.REMOTE }, onBrowse = { screen = Screen.BROWSER })
                    Screen.PAIR -> PairScreen(vm) { screen = Screen.HOME }
                    Screen.REMOTE -> RemoteScreen(vm, onClose = { screen = Screen.HOME }, onPairAgain = { screen = Screen.PAIR }, onBrowse = { screen = Screen.BROWSER })
                    // The browser shows the remote as an overlay itself, so the page stays loaded underneath.
                    Screen.BROWSER -> BrowserScreen(vm, onClose = { screen = Screen.HOME }, onPairAgain = { screen = Screen.PAIR })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** "Share" → UniversalCast: a stream link goes to the remote; a web page opens in the cast browser. */
    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND && i.type == "text/plain") {
            val url = Links.extractHttpUrl(i.getStringExtra(Intent.EXTRA_TEXT)) ?: return
            if (MediaSniffer.classify(url).castable) {
                vm.setPendingUrl(url) // a direct stream/file link: cast it as is
            } else {
                vm.browserUrl.value = url // a web page: open it and find the video it plays
                vm.openBrowserRequest.value = true
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as SenderApp).updates.checkIfStale() // silent unless a newer release exists
        vm.rt.discovery.start()
    }

    override fun onStop() {
        vm.rt.discovery.stop()
        super.onStop()
    }
}
