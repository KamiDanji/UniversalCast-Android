package com.universalcast.sender.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalcast.updater.UpdateManager
import com.universalcast.updater.UpdateState

/**
 * Dialog driven by [UpdateManager]. Background checks only surface here when a newer release exists; failures and
 * "up to date" only appear after the user chose "Check for updates".
 */
@Composable
fun UpdatePrompt(updates: UpdateManager) {
    val state by updates.state.collectAsState()
    when (val s = state) {
        UpdateState.Idle, UpdateState.Checking -> Unit
        UpdateState.UpToDate -> Info("Up to date", "UniversalCast ${updates.currentVersion} is the latest version.", updates)
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = updates::dismiss,
            title = { Text("Update available: ${s.release.version}") },
            text = {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    Text("You have ${updates.currentVersion}. The new version downloads from GitHub and Android asks you to confirm the install.", color = Palette.TextDim)
                    if (s.release.notes.isNotEmpty()) { Spacer(Modifier.height(12.dp)); Text(s.release.notes) }
                }
            },
            confirmButton = { TextButton(onClick = updates::install) { Text("Update now") } },
            dismissButton = { TextButton(onClick = updates::dismiss) { Text("Later") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading ${s.release.version}…") },
            text = { Column { LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth()); Text("${s.percent}%", color = Palette.TextDim) } },
            confirmButton = {},
        )
        is UpdateState.NeedsInstallPermission -> AlertDialog(
            onDismissRequest = updates::dismiss,
            title = { Text("Allow installing updates") },
            text = { Text("Android needs your permission for UniversalCast to install updates. Enable “Allow from this source” on the next screen, press Back, then choose Update again.") },
            confirmButton = { TextButton(onClick = updates::openInstallPermissionSettings) { Text("Open settings") } },
            dismissButton = {
                Column {
                    TextButton(onClick = updates::install) { Text("Update") }
                    TextButton(onClick = updates::dismiss) { Text("Cancel") }
                }
            },
        )
        is UpdateState.Installing -> Info("Installing ${s.release.version}", "Confirm the installation on the Android screen. The app restarts when it is done.", updates)
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = updates::dismiss,
            title = { Text("Update problem") },
            text = { Text(s.message) },
            confirmButton = { if (s.release != null) TextButton(onClick = updates::install) { Text("Retry") } },
            dismissButton = { TextButton(onClick = updates::dismiss) { Text("Close") } },
        )
    }
}

@Composable
private fun Info(title: String, message: String, updates: UpdateManager) {
    AlertDialog(
        onDismissRequest = updates::dismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = updates::dismiss) { Text("OK") } },
    )
}
