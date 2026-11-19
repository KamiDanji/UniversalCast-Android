package com.universalcast.sender.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.universalcast.security.Fingerprint
import com.universalcast.sender.app.BuildConfig
import com.universalcast.sender.app.SenderApp

private const val TAPS_TO_UNLOCK_DEV = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenDummyRemote: () -> Unit,
) {
    val rt = vm.rt
    val state by rt.state.collectAsState()
    val receivers by rt.receivers.collectAsState()
    val nearby by rt.discovery.tvs.collectAsState()
    val history by rt.history.collectAsState()
    val bookmarks by rt.bookmarks.collectAsState()
    val filterEngine by rt.filters.engine.collectAsState()
    val devUnlocked by vm.devModeUnlocked.collectAsState()
    val ctx = LocalContext.current
    val updates = (ctx.applicationContext as SenderApp).updates
    val haptics = LocalHapticFeedback.current

    var versionTaps by remember { mutableIntStateOf(0) }
    var tapHint by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About UniversalCast") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // --- Version Card (Spam-tap to unlock Dev / Dummy TV Mode) -----------------------------
            Panel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("UniversalCast", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                        Text("Secure Android TV Casting & Remote", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Chip("UCP 1.0", MaterialTheme.colorScheme.primary)
                }

                Spacer(Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .border(1.dp, if (devUnlocked || state.isDummyMode) MaterialTheme.status.ok.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            versionTaps++
                            val remaining = TAPS_TO_UNLOCK_DEV - versionTaps
                            when {
                                devUnlocked || state.isDummyMode -> {
                                    tapHint = "Developer Dummy TV Mode is already unlocked!"
                                }
                                remaining in 1..3 -> {
                                    tapHint = "$remaining more tap${if (remaining == 1) "" else "s"} to unlock Developer Dummy TV Mode…"
                                }
                                remaining <= 0 -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    vm.devModeUnlocked.value = true
                                    rt.controller.startDummyMode()
                                    tapHint = "🎉 Developer Dummy TV Mode unlocked & connected!"
                                }
                            }
                        }
                        .padding(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Version", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                            if (devUnlocked || state.isDummyMode) {
                                Chip("Dev Mode Active", MaterialTheme.status.ok, live = true)
                            }
                        }
                        Text(
                            text = "v${updates.currentVersion} (Build ${BuildConfig.VERSION_CODE})",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                        tapHint?.let {
                            Text(it, color = if (devUnlocked) MaterialTheme.status.ok else MaterialTheme.status.warn, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // --- Developer Dummy TV Mode Panel (shown once unlocked) -------------------------------
            if (devUnlocked || state.isDummyMode) {
                Panel {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Developer Dummy TV Simulator", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.status.ok)
                        Chip(if (state.isDummyMode) "Connected" else "Standby", if (state.isDummyMode) MaterialTheme.status.ok else MaterialTheme.colorScheme.onSurfaceVariant, live = state.isDummyMode)
                    }
                    Text(
                        "Simulates a live connected Android TV receiver with sample episodes, multi-language audio tracks, subtitles, and queue so you can test the TV Remote, Touchpad, D-Pad, Keyboard, and Media controls without a TV.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (!state.isDummyMode) rt.controller.startDummyMode()
                                onOpenDummyRemote()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (state.isDummyMode) "Open Dummy TV Remote" else "Start & Open Dummy TV")
                        }
                        if (state.isDummyMode) {
                            OutlinedButton(onClick = { rt.controller.disconnect() }) {
                                Text("Disconnect", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            // --- Developer Card --------------------------------------------------------------------
            Panel {
                Text("Developer & Project", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                StatRow("Developer", "Danny (KamiDanji)")
                StatRow("Repository", "github.com/KamiDanji/UniversalCast-Android")
                StatRow("Protocol", "UniversalCast Protocol (UCP) 1.0")
                StatRow("Transport Security", "TLS 1.2 / 1.3 · SPKI SHA-256 Certificate Pinning")
                StatRow("Authentication", "ECDSA P-256 Mutual Challenge-Response + 6-digit SAS")
            }

            // --- Live Stats Card -------------------------------------------------------------------
            Panel {
                Text("Runtime & Security Stats", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                StatRow(
                    "Connection State",
                    when {
                        state.isDummyMode -> "CONNECTED → Dummy TV (Dev Simulator)"
                        state.receiver != null -> "${state.phase.name} → ${state.receiver?.name}"
                        else -> state.phase.name
                    }
                )
                StatRow("Paired TVs", "${receivers.size} saved (AES-256-GCM) · ${nearby.size} nearby via mDNS")
                StatRow(
                    "Phone Media Proxy",
                    if (rt.proxy.isRunning) "Running on port ${rt.proxy.port} (HTTP Range 206)" else "Standby (starts on demand)"
                )
                StatRow(
                    "Cast Browser Ad-Blocker",
                    if (filterEngine != null) "EasyList + uBlock + Built-in domain blocker loaded" else "Built-in domain blocker active"
                )
                StatRow("Watch History", "${history.size} tracked stream(s) (${history.count { it.canResume }} resumable)")
                StatRow("Browser Bookmarks", "${bookmarks.size} saved website(s)")
                StatRow("Phone Keystore Key", "EC P-256 (Android Hardware Keystore)")
                Text("Phone Identity Fingerprint", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Text(
                    Fingerprint.display(rt.identity.senderId),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Text(value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}
