package com.universalcast.receiver.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalcast.updater.UpdateManager
import com.universalcast.updater.UpdateState

/**
 * Dialog driven by [UpdateManager]. Background checks only surface here when a newer release exists; failures and
 * "up to date" are only shown after the user pressed "Check for updates" (those states never arise from a silent check).
 */
@Composable
fun UpdatePrompt(updates: UpdateManager) {
    val state by updates.state.collectAsState()
    when (val s = state) {
        UpdateState.Idle, UpdateState.Checking -> Unit
        UpdateState.UpToDate -> Dialog("Up to date", "UniversalCast ${updates.currentVersion} is the latest version.", { TvButton("OK", updates::dismiss) })
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = updates::dismiss,
            containerColor = Palette.Surface,
            title = { Text("Update available: ${s.release.version}", color = Palette.Text) },
            text = {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    Text("You have ${updates.currentVersion}. The new version downloads from GitHub and Android asks you to confirm the install.", color = Palette.TextDim)
                    if (s.release.notes.isNotEmpty()) { Spacer(Modifier.height(12.dp)); Text(s.release.notes, color = Palette.Text, style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = { TvButton("Update now", updates::install) },
            dismissButton = { TvButton("Later", updates::dismiss, primary = false) },
        )
        is UpdateState.Downloading -> Dialog("Downloading ${s.release.version}…", null, {}) {
            LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
            Text("${s.percent}%", color = Palette.TextDim)
        }
        is UpdateState.NeedsInstallPermission -> Dialog(
            "Allow installing updates",
            "Android needs your permission for UniversalCast to install updates. Enable “Allow from this source” on the next screen, press Back, then choose Update again.",
            {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Open settings", updates::openInstallPermissionSettings)
                    TvButton("Update", updates::install, primary = false)
                    TvButton("Cancel", updates::dismiss, primary = false)
                }
            },
        )
        is UpdateState.Installing -> Dialog("Installing ${s.release.version}", "Confirm the installation on the Android screen. The app restarts when it is done.", { TvButton("Close", updates::dismiss, primary = false) })
        is UpdateState.Failed -> Dialog("Update problem", s.message, {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (s.release != null) TvButton("Retry", updates::install)
                TvButton("Close", updates::dismiss, primary = false)
            }
        })
    }
}

@Composable
private fun Dialog(title: String, message: String?, buttons: @Composable () -> Unit, body: @Composable () -> Unit = {}) {
    AlertDialog(
        onDismissRequest = {},
        containerColor = Palette.Surface,
        title = { Text(title, color = Palette.Text) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { if (message != null) Text(message, color = Palette.TextDim); body() } },
        confirmButton = buttons,
    )
}
