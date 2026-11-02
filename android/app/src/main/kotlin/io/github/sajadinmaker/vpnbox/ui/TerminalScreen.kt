package io.github.sajadinmaker.vpnbox.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sajadinmaker.vpnbox.R
import io.github.sajadinmaker.vpnbox.TerminalLine
import io.github.sajadinmaker.vpnbox.TerminalUi
import io.github.sajadinmaker.vpnbox.WatchPhase
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(ui: TerminalUi, onBack: () -> Unit, onKill: () -> Unit) {
    val scroll = rememberScrollState()
    val view = LocalView.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val copiedMsg = stringResource(R.string.terminal_copied)
    LaunchedEffect(ui.lines.size) {
        runCatching { scroll.animateScrollTo(scroll.maxValue) }
    }
    LaunchedEffect(copied) {
        if (copied) {
            snackbarHostState.showSnackbar(copiedMsg, duration = SnackbarDuration.Short)
            copied = false
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.terminal_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        enabled = ui.lines.isNotEmpty(),
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            clipboard.setText(
                                AnnotatedString(ui.lines.joinToString("\n") { it.text }),
                            )
                            copied = true
                        },
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.terminal_copy_log),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        phaseTag(ui.phase),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(Modifier.fillMaxSize().padding(14.dp)) {
                    Column(
                        modifier = Modifier.verticalScroll(scroll),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (l in ui.lines) {
                            Text(
                                l.text,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = tone(l.tone),
                            )
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    phaseKicker(ui.phase),
                    style = MaterialTheme.typography.labelLarge,
                    letterSpacing = 1.5.sp,
                    color = kickerColor(ui.phase),
                )
                Text(
                    phaseHeadline(ui.phase),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (ui.phase == WatchPhase.FAILED) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (ui.phase == WatchPhase.CONNECT) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    strokeCap = StrokeCap.Round,
                )
            } else {
                LinearProgressIndicator(
                    progress = { fraction(ui.phase) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = if (ui.phase == WatchPhase.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    strokeCap = StrokeCap.Round,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.terminal_back)) }
                OutlinedButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onKill()
                    },
                    enabled = ui.phase == WatchPhase.WATCH,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.terminal_kill)) }
            }
        }
    }
}

@Composable
private fun phaseTag(p: WatchPhase): String = when (p) {
    WatchPhase.CONNECT -> stringResource(R.string.tag_connecting)
    WatchPhase.WATCH -> stringResource(R.string.tag_protected)
    WatchPhase.KILL -> stringResource(R.string.tag_reconnecting)
    WatchPhase.RECOVERED -> stringResource(R.string.tag_recovered)
    WatchPhase.FAILED -> stringResource(R.string.tag_failed)
}

@Composable
private fun phaseKicker(p: WatchPhase): String = phaseTag(p)

@Composable
private fun phaseHeadline(p: WatchPhase): String = when (p) {
    WatchPhase.CONNECT -> stringResource(R.string.headline_connecting)
    WatchPhase.WATCH -> stringResource(R.string.headline_protected)
    WatchPhase.KILL -> stringResource(R.string.headline_reconnecting)
    WatchPhase.RECOVERED -> stringResource(R.string.headline_recovered)
    WatchPhase.FAILED -> stringResource(R.string.headline_failed)
}

@Composable
private fun kickerColor(p: WatchPhase): Color = when (p) {
    WatchPhase.FAILED -> MaterialTheme.colorScheme.error
    WatchPhase.KILL -> MaterialTheme.colorScheme.tertiary
    WatchPhase.RECOVERED -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
}

private fun fraction(p: WatchPhase): Float = when (p) {
    WatchPhase.WATCH -> 0.66f
    WatchPhase.KILL -> 0.8f
    WatchPhase.RECOVERED, WatchPhase.FAILED -> 1f
    else -> 0.33f
}

@Composable
private fun tone(t: TerminalLine.Tone): Color = when (t) {
    TerminalLine.Tone.OK -> MaterialTheme.colorScheme.secondary
    TerminalLine.Tone.WARN -> MaterialTheme.colorScheme.tertiary
    TerminalLine.Tone.ERR -> MaterialTheme.colorScheme.error
    TerminalLine.Tone.ACCENT -> MaterialTheme.colorScheme.onSurface
    TerminalLine.Tone.PLAIN -> MaterialTheme.colorScheme.onSurfaceVariant
}
