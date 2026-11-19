package com.universalcast.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.universalcast.updater.MarkdownBlock
import com.universalcast.updater.MarkdownParser
import com.universalcast.updater.MarkdownSpan
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
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "You have ${updates.currentVersion}. The new version downloads from GitHub and Android asks you to confirm the install.",
                        color = Palette.TextDim,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (s.release.notes.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        MarkdownReleaseNotes(s.release.notes)
                    }
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
fun MarkdownReleaseNotes(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in blocks) {
            when (b) {
                is MarkdownBlock.Heading -> {
                    val style = when (b.level) {
                        1 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        else -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    }
                    val color = if (b.level <= 2) Palette.Accent else Palette.Text
                    Spacer(Modifier.height(2.dp))
                    Text(text = renderSpans(b.spans), style = style, color = color)
                }
                is MarkdownBlock.ListItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = (b.indentLevel * 16).dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(b.bullet, color = Palette.Accent, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(
                            text = renderSpans(b.spans),
                            color = Palette.Text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                is MarkdownBlock.CodeBlock -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Palette.SurfaceHigh, RoundedCornerShape(10.dp))
                            .padding(12.dp),
                    ) {
                        Text(
                            text = b.code,
                            color = Palette.Text,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                MarkdownBlock.Divider -> {
                    HorizontalDivider(color = Palette.Outline, modifier = Modifier.padding(vertical = 4.dp))
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = renderSpans(b.spans),
                        color = Palette.Text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun renderSpans(spans: List<MarkdownSpan>): AnnotatedString = buildAnnotatedString {
    for (s in spans) {
        when (s) {
            is MarkdownSpan.Plain -> append(s.text)
            is MarkdownSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Palette.Text)) { append(s.text) }
            is MarkdownSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.text) }
            is MarkdownSpan.Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    color = Palette.Accent,
                    background = Palette.SurfaceHigh,
                )
            ) { append(" ${s.text} ") }
            is MarkdownSpan.Link -> withStyle(
                SpanStyle(
                    color = Palette.Accent,
                    textDecoration = TextDecoration.Underline,
                    fontWeight = FontWeight.Medium,
                )
            ) { append(s.label) }
        }
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
