package com.universalcast.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Clean ambient / idle screen for the TV receiver: shows a large clock, date, receiver name,
 * connection status badge and connected phones. Any remote key returns to the Home screen.
 */
@Composable
fun AmbientScreen(vm: AppViewModel, onDismiss: () -> Unit) {
    val rt = vm.runtime
    val name by rt.receiverName.collectAsState()
    val running by rt.running.collectAsState()
    val connected by rt.connected.collectAsState()
    val focusRequester = remember { FocusRequester() }

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val timeStr = remember(nowMs / 1_000L) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nowMs))
    }
    val dateStr = remember(nowMs / 60_000L) {
        SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(nowMs))
    }
    // Subtle burn-in shift every minute
    val minuteTick = ((nowMs / 60_000L) % 4).toInt()
    val offsetX = when (minuteTick) { 0 -> (-8).dp; 1 -> 8.dp; 2 -> (-4).dp; else -> 4.dp }
    val offsetY = when (minuteTick) { 0 -> (-6).dp; 1 -> 6.dp; 2 -> 6.dp; else -> (-6).dp }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .appBackground()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent {
                onDismiss()
                true
            }
            .padding(56.dp),
    ) {
        // Top-left receiver identity badge
        Row(
            modifier = Modifier.align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Chip(
                text = if (running) "Ready to cast · TLS :${rt.core.boundPort}" else "Starting…",
                color = if (running) Palette.Ok else Palette.Warn,
                live = running,
            )
            if (connected.isNotEmpty()) {
                Chip(
                    text = "${connected.size} phone${if (connected.size == 1) "" else "s"} connected",
                    color = Palette.Accent,
                    live = true,
                )
            }
        }

        // Center clock & date
        Column(
            modifier = Modifier.align(Alignment.Center).offset(x = offsetX, y = offsetY),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = timeStr,
                color = Palette.Text,
                fontSize = 112.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = dateStr,
                color = Palette.TextDim,
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        // Bottom bar: TV name + connected device names
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0x66121A2B), RoundedCornerShape(20.dp))
                .border(1.dp, Palette.Outline, RoundedCornerShape(20.dp))
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(name, color = Palette.Text, style = MaterialTheme.typography.titleLarge)
                Text(
                    if (connected.isEmpty()) "Open UniversalCast on your phone to cast videos, websites or local files"
                    else "Connected: " + connected.joinToString { "${it.name} (${it.role.name.lowercase()})" },
                    color = Palette.TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text("Press any key for menu", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
