package com.universalcast.sender.app.ui

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
import androidx.compose.material3.TextButton
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
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "You have ${updates.currentVersion}. The new version downloads from GitHub and Android asks you to confirm the install.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (s.release.notes.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        MarkdownReleaseNotes(s.release.notes)
                    }
                }
            },
            confirmButton = { TextButton(onClick = updates::install) { Text("Update now") } },
            dismissButton = { TextButton(onClick = updates::dismiss) { Text("Later") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading ${s.release.version}…") },
            text = { Column { LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth()); Text("${s.percent}%", color = MaterialTheme.colorScheme.onSurfaceVariant) } },
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
fun MarkdownReleaseNotes(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in blocks) {
            when (b) {
                is MarkdownBlock.Heading -> {
                    val style = when (b.level) {
                        1 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        2 -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        else -> MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                    }
                    val color = if (b.level <= 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    Spacer(Modifier.height(2.dp))
                    Text(text = renderSpans(b.spans), style = style, color = color)
                }
                is MarkdownBlock.ListItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = (b.indentLevel * 12).dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(b.bullet, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(
                            text = renderSpans(b.spans),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                is MarkdownBlock.CodeBlock -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                    ) {
                        Text(
                            text = b.code,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                MarkdownBlock.Divider -> {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = renderSpans(b.spans),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun renderSpans(spans: List<MarkdownSpan>): AnnotatedString {
    val strong = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    return buildAnnotatedString {
    for (s in spans) {
        when (s) {
            is MarkdownSpan.Plain -> append(s.text)
            is MarkdownSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = strong)) { append(s.text) }
            is MarkdownSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.text) }
            is MarkdownSpan.Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    color = accent,
                    background = codeBg,
                )
            ) { append(" ${s.text} ") }
            is MarkdownSpan.Link -> withStyle(
                SpanStyle(
                    color = accent,
                    textDecoration = TextDecoration.Underline,
                    fontWeight = FontWeight.Medium,
                )
            ) { append(s.label) }
        }
    }
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
