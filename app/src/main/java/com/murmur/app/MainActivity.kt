package com.murmur.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    // Bumped on resume so permission and settings state is re-read after visiting Settings.
    private val resumeTick = mutableIntStateOf(0)
    private val themeMode = mutableStateOf(ThemeMode.System)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeMode.value = Prefs.theme(this)
        setContent {
            MurmurTheme(themeMode.value) {
                MurmurApp(
                    resumeTick = resumeTick.intValue,
                    theme = themeMode.value,
                    onTheme = {
                        themeMode.value = it
                        Prefs.setTheme(this, it)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

private fun bubbleEnabled(ctx: Context): Boolean {
    val enabled = Settings.Secure.getString(
        ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val full = "${ctx.packageName}/${BubbleService::class.java.name}"
    val short = "${ctx.packageName}/.BubbleService"
    return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
}

@Composable
private fun MurmurApp(resumeTick: Int, theme: ThemeMode, onTheme: (ThemeMode) -> Unit) {
    val ctx = LocalContext.current
    val state by Murmur.state.collectAsState()
    var appearanceOpen by remember { mutableStateOf(false) }

    val micGranted = remember(resumeTick) {
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val modelInstalled = remember(resumeTick) { Engine.isModelInstalled(ctx) }
    val bubbleOn = remember(resumeTick) { bubbleEnabled(ctx) }
    val batteryFree = remember(resumeTick) {
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }
    var micNow by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> micNow = result[Manifest.permission.RECORD_AUDIO] == true }
    val hasMic = micGranted || micNow

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 24.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") { Header(onAppearance = { appearanceOpen = true }) }

            item(key = "status") {
                StatusCard(
                    state = state,
                    canStart = hasMic && modelInstalled,
                    onStart = { DictationService.start(ctx) },
                    onStop = { DictationService.stop(ctx) },
                )
            }

            item(key = "setup-label") { SectionLabel("Setup") }
            item(key = "setup") {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        SetupRow(
                            "Microphone",
                            "Needed to hear you",
                            done = hasMic,
                            action = "Allow",
                        ) {
                            permissions.launch(
                                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                            )
                        }
                        SetupRow(
                            "Voice model",
                            if (modelInstalled) "Nemotron Speech Streaming · English · on-device"
                            else "Not found in ${Engine.modelDir(ctx).path}",
                            done = modelInstalled,
                            action = null,
                        ) {}
                        SetupRow(
                            "Bubble in other apps",
                            "Accessibility lets Murmur type into any text field",
                            done = bubbleOn,
                            action = "Turn on",
                        ) {
                            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                        SetupRow(
                            "Stay ready",
                            "Keeps OnePlus from closing Murmur in the background",
                            done = batteryFree,
                            action = "Allow",
                        ) {
                            ctx.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${ctx.packageName}"),
                                )
                            )
                        }
                    }
                }
            }

            item(key = "try-label") { SectionLabel("Try it") }
            item(key = "try") { TryCard(state) }

            if (SpeedTest.sample(ctx).exists()) {
                item(key = "speed") { SpeedCard(enabled = state.phase == Phase.Ready) }
            }
        }
    }

    if (appearanceOpen) {
        AppearanceDialog(theme, onPick = onTheme, onDismiss = { appearanceOpen = false })
    }
}

@Composable
private fun Header(onAppearance: () -> Unit) {
    Row(Modifier.padding(start = 4.dp, bottom = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Murmur", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Private voice typing, right on your phone",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.offset(x = 8.dp, y = (-4).dp)) {
            var menuOpen by remember { mutableStateOf(false) }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.Menu, contentDescription = "Menu")
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                DropdownMenuItem(
                    text = { Text("Appearance") },
                    leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    onClick = { menuOpen = false; onAppearance() },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp),
    )
}

@Composable
private fun StatusCard(state: DictationState, canStart: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val on = state.phase != Phase.Off
    val container by animateColorAsState(
        if (on && state.phase != Phase.Loading) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "status",
    )
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = container,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(
                        if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_mic),
                        contentDescription = null,
                        tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when (state.phase) {
                            Phase.Off -> "Murmur is off"
                            Phase.Loading -> "Loading voice model…"
                            Phase.Ready -> "Ready"
                            Phase.Listening -> "Listening…"
                            Phase.Finishing -> "Finishing…"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when (state.phase) {
                            Phase.Off -> "Start it to use the bubble in any app"
                            Phase.Loading -> "This takes a few seconds, once"
                            else -> "Tap the bubble above your keyboard in any app"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.phase == Phase.Loading) {
                Spacer(Modifier.height(18.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
            }
            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                state.loadMs?.takeIf { on }?.let {
                    Text(
                        "Model loaded in ${seconds(it)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (on) {
                    TextButton(onClick = onStop) { Text("Turn off") }
                } else {
                    Button(
                        onClick = onStart,
                        enabled = canStart,
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
                    ) { Text("Start") }
                }
            }
        }
    }
}

@Composable
private fun SetupRow(title: String, body: String, done: Boolean, action: String?, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(
                if (done) MaterialTheme.colorScheme.primary else Color.Transparent
            ).border(
                2.dp,
                if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = "Done",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!done && action != null) {
            Spacer(Modifier.size(8.dp))
            FilledTonalButton(onClick = onAction, shape = RoundedCornerShape(14.dp)) { Text(action) }
        }
    }
}

@Composable
private fun TryCard(state: DictationState) {
    var text by remember { mutableStateOf("") }
    val listening = state.phase == Phase.Listening || state.phase == Phase.Finishing
    val ready = state.phase == Phase.Ready

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp).animateContentSize()) {
            val style = MaterialTheme.typography.bodyLarge
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                "Tap here and use the bubble, or the mic below",
                                style = style,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                        inner()
                    }
                },
            )
            AnimatedVisibility(listening) {
                Column(Modifier.padding(top = 12.dp)) {
                    Waveform(
                        state.levels,
                        Modifier.fillMaxWidth().height(28.dp),
                        color = MaterialTheme.colorScheme.primary,
                        bars = 48,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.partial.ifBlank { "Listening…" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stats(state) ?: if (ready || listening) "" else "Start Murmur to dictate",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                val bg by animateColorAsState(
                    when {
                        listening -> MaterialTheme.colorScheme.error
                        ready -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    label = "mic",
                )
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(bg).clickable(enabled = ready || listening) {
                        val service = DictationService.instance ?: return@clickable
                        if (listening) service.finish()
                        else service.listen { result ->
                            text = listOf(text.trimEnd(), result).filter { it.isNotEmpty() }.joinToString(" ")
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        state.phase == Phase.Finishing -> CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onError,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                        listening -> Box(
                            Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.onError)
                        )
                        else -> Icon(
                            painterResource(R.drawable.ic_mic),
                            contentDescription = "Dictate here",
                            tint = if (ready) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun seconds(ms: Long) = String.format(Locale.US, "%.1f s", ms / 1000f)

private fun stats(state: DictationState): String? {
    val audio = state.lastAudioMs ?: return null
    val latency = state.lastLatencyMs ?: return null
    return "Last: ${seconds(audio)} of speech · text ${latency} ms after stop"
}

@Composable
private fun SpeedCard(enabled: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SpeedResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Speed test", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Transcribes a sample recording as fast as your phone can",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(8.dp))
                FilledTonalButton(
                    onClick = {
                        running = true
                        error = null
                        scope.launch {
                            try {
                                result = withContext(Dispatchers.Default) { SpeedTest.run(ctx) }
                            } catch (e: Throwable) {
                                error = e.message
                            }
                            running = false
                        }
                    },
                    enabled = enabled && !running,
                    shape = RoundedCornerShape(14.dp),
                ) { Text(if (running) "Running…" else "Run") }
            }
            result?.let { r ->
                Spacer(Modifier.height(14.dp))
                Text(
                    String.format(Locale.US, "%.0f× faster than real time", r.speedup),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "${seconds(r.audioMs)} of audio in ${seconds(r.decodeMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("“${r.text}”", style = MaterialTheme.typography.bodySmall)
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AppearanceDialog(current: ThemeMode, onPick: (ThemeMode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Appearance") },
        text = {
            Column {
                ThemeMode.entries.forEach { mode ->
                    val selected = mode == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                            )
                            .clickable { onPick(mode) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ThemeSwatch(mode)
                        Spacer(Modifier.size(14.dp))
                        Text(mode.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (selected) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/** Small circle previewing a theme; System is drawn half light, half dark. */
@Composable
private fun ThemeSwatch(mode: ThemeMode) {
    val light = Color(0xFFF4F4F6)
    val dark = Color(0xFF26272B)
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .background(
                when (mode) {
                    ThemeMode.Light -> SolidColor(light)
                    ThemeMode.Dark -> SolidColor(dark)
                    ThemeMode.Amoled -> SolidColor(Color.Black)
                    ThemeMode.System -> Brush.horizontalGradient(0f to light, 0.5f to light, 0.5f to dark, 1f to dark)
                }
            )
    )
}
