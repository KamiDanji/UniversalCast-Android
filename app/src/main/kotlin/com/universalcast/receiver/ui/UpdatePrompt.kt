package com.universalcast.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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
        is UpdateState.Available -> TvDialog(
            title = "Update available: ${s.release.version}",
            onDismiss = updates::dismiss,
            buttons = {
                TvButton("Update now", updates::install)
                TvButton("Later", updates::dismiss, primary = false)
            },
        ) {
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "You have ${updates.currentVersion}. The new version downloads from GitHub and Android asks you to confirm the install.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (s.release.notes.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    MarkdownReleaseNotes(s.release.notes)
                }
            }
        }
        is UpdateState.Downloading -> Dialog("Downloading ${s.release.version}…", null, {}) {
            TvProgressBar(s.percent / 100f, Modifier.fillMaxWidth())
            Text("${s.percent}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    val color = if (b.level <= 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    Spacer(Modifier.height(2.dp))
                    Text(text = renderSpans(b.spans), style = style, color = color)
                }
                is MarkdownBlock.ListItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = (b.indentLevel * 16).dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                            .padding(12.dp),
                    ) {
                        Text(
                            text = b.code,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                MarkdownBlock.Divider -> {
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(MaterialTheme.colorScheme.border))
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
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
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
private fun Dialog(title: String, message: String?, buttons: @Composable () -> Unit, body: @Composable () -> Unit = {}) {
    TvDialog(title = title, onDismiss = {}, buttons = { buttons() }) {
        if (message != null) Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        body()
    }
}
