package com.murmur.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Bumped on resume so permission and settings state is re-read after visiting Settings.
    private val resumeTick = mutableIntStateOf(0)
    private val themeMode = mutableStateOf(ThemeMode.System)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before setContent, so the first frame is already drawn behind the system bars.
        enableEdgeToEdge()
        themeMode.value = Prefs.theme(this)
        // Up to thousands of lines of JSON: read them off the main thread.
        lifecycleScope.launch(Dispatchers.IO) { History.load(applicationContext) }
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
        // Opening Murmur brings back a bubble the user closed.
        Prefs.setBubbleClosed(this, false)
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

/** Opens the first of [intents] this phone has; some makers leave out or lock settings pages. */
private fun openFirst(ctx: Context, intents: List<Intent>) {
    for (intent in intents) {
        try {
            ctx.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
}

/** The main screen without the live waveform and transcript, which change every few ms. */
private fun DictationState.withoutLive() = copy(levels = emptyList(), partial = "")

/** Speed test progress, kept above the list so it survives the card scrolling out of view. */
private class SpeedTestState {
    var running by mutableStateOf(false)
    var result by mutableStateOf<SpeedResult?>(null)
    var error by mutableStateOf<String?>(null)

    fun run() {
        error = null
        running = true
        // The service runs it on the model's thread, so a dictation can't start meanwhile.
        val started = DictationService.instance?.speedTest { r ->
            running = false
            r.onSuccess { result = it }.onFailure { error = it.message ?: it.toString() }
        } == true
        if (!started) running = false
    }
}

@Composable
private fun MurmurApp(resumeTick: Int, theme: ThemeMode, onTheme: (ThemeMode) -> Unit) {
    val ctx = LocalContext.current
    // Only the try-it box shows the live waveform and text; the rest of the screen needn't
    // recompose for every chunk of audio.
    val state by remember { Murmur.state.map { it.withoutLive() }.distinctUntilChanged() }
        .collectAsState(Murmur.state.value.withoutLive())
    val history by History.items.collectAsState()
    val historyLoaded by History.loaded.collectAsState()
    var appearanceOpen by remember { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val speed = remember { SpeedTestState() }
    val timeFormat = remember(resumeTick) { android.text.format.DateFormat.getTimeFormat(ctx) }

    fun copy(d: Dictation) {
        copyText(ctx, d.text)
        // Android 13+ confirms copies itself; a snackbar on top would say it twice.
        if (Build.VERSION.SDK_INT < 33) scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar("Copied to clipboard", duration = SnackbarDuration.Short)
        }
    }

    fun delete(d: Dictation) {
        History.delete(ctx, d.id)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar("Dictation deleted", "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) History.restore(ctx, d)
        }
    }

    val micGranted = remember(resumeTick) {
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val modelInstalled = remember(resumeTick) { Engine.isModelInstalled(ctx) }
    val npuInstalled = remember(resumeTick) { Engine.isNpuInstalled(ctx) }
    val cpuInstalled = remember(resumeTick) { Engine.isCpuInstalled(ctx) }
    val hasSample = remember(resumeTick) { SpeedTest.sample(ctx).exists() }
    val bubbleOn = remember(resumeTick) { bubbleEnabled(ctx) }
    val batteryFree = remember(resumeTick) {
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }
    var micNow by remember { mutableStateOf(false) }
    // Once the user has said no for good, Android stops showing the dialog: send them to
    // Settings. That shows after the fact: a refusal after which Android no longer offers an
    // explanation, when it did before (or a second refusal in a row).
    var micBlocked by rememberSaveable { mutableStateOf(false) }
    var micRefusals by rememberSaveable { mutableIntStateOf(0) }
    var rationaleBefore by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        micNow = result[Manifest.permission.RECORD_AUDIO] == true
        if (!micNow) {
            micRefusals++
            val rationale = (ctx as Activity).shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            if (!rationale && (rationaleBefore || micRefusals >= 2)) micBlocked = true
        }
    }
    val hasMic = micGranted || micNow
    // Android 13+ hides the notification (and its Turn off button) without this permission.
    // Murmur works without it, so start either way.
    var notificationsAsked by rememberSaveable { mutableStateOf(false) }
    val notifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { DictationService.start(ctx) }

    fun start() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsAsked &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationsAsked = true
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            DictationService.start(ctx)
        }
    }

    Surface(
        Modifier.fillMaxSize().then(if (historyOpen) Modifier.clearAndSetSemantics {} else Modifier),
        color = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 24.dp,
                // imePadding already lifts the list above the keyboard, which covers the nav bar.
                bottom = WindowInsets.navigationBars.exclude(WindowInsets.ime).asPaddingValues()
                    .calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                Header(onHistory = { historyOpen = true }, onAppearance = { appearanceOpen = true })
            }

            item(key = "status") {
                StatusCard(
                    state = state,
                    cantStart = when {
                        !hasMic -> "Allow the microphone first"
                        !modelInstalled -> "Voice model not installed"
                        else -> null
                    },
                    onStart = { start() },
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
                            if (micBlocked) {
                                openFirst(
                                    ctx,
                                    listOf(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", ctx.packageName, null),
                                        )
                                    ),
                                )
                            } else {
                                rationaleBefore = (ctx as Activity)
                                    .shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                                permissions.launch(
                                    if (Build.VERSION.SDK_INT >= 33) {
                                        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        arrayOf(Manifest.permission.RECORD_AUDIO)
                                    }
                                )
                            }
                        }
                        SetupRow(
                            "Voice model",
                            if (modelInstalled) "Nemotron · runs entirely on your phone"
                            else "Not installed — see README (scripts/install-model.sh)",
                            done = modelInstalled,
                            action = null,
                        ) {}
                        SetupRow(
                            "Bubble in other apps",
                            "Accessibility lets Murmur type into any text field",
                            done = bubbleOn,
                            action = "Turn on",
                        ) {
                            // Android 13+ can open Murmur's own page instead of the full list.
                            val details = if (Build.VERSION.SDK_INT >= 33) {
                                Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS).putExtra(
                                    Intent.EXTRA_COMPONENT_NAME,
                                    ComponentName(ctx, BubbleService::class.java).flattenToString(),
                                )
                            } else null
                            openFirst(ctx, listOfNotNull(details, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                        }
                        SetupRow(
                            "Stay ready",
                            "Keeps your phone from closing Murmur in the background",
                            done = batteryFree,
                            action = "Allow",
                        ) {
                            openFirst(
                                ctx,
                                listOf(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${ctx.packageName}"),
                                    ),
                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                                ),
                            )
                        }
                    }
                }
            }

            if (npuInstalled) {
                item(key = "voice-label") { SectionLabel("Voice") }
                item(key = "voice") {
                    VoiceCard(state, cpuInstalled = cpuInstalled, tick = resumeTick)
                }
            }

            item(key = "try-label") { SectionLabel("Try it") }
            item(key = "try") { TryCard(state) }

            if (history.isNotEmpty()) {
                item(key = "recent-label") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { SectionLabel("Recent") }
                        TextButton(onClick = { historyOpen = true }, modifier = Modifier.padding(top = 12.dp)) {
                            Text("See all")
                        }
                    }
                }
                items(history.take(3), key = { "recent-${it.id}" }) { d ->
                    DictationRow(d, Modifier.animateItem(), timeFormat, onCopy = { copy(d) }, onDelete = null)
                }
            }

            if (hasSample) {
                item(key = "speed") { SpeedCard(speed, enabled = state.phase == Phase.Ready) }
            }
        }
    }

    AnimatedVisibility(
        historyOpen,
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
    ) {
        BackHandler { historyOpen = false }
        HistoryScreen(
            items = history,
            loaded = historyLoaded,
            timeFormat = timeFormat,
            onBack = { historyOpen = false },
            onCopy = { copy(it) },
            onDelete = { delete(it) },
            onClearAll = { History.clear(ctx) },
        )
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(snackbar, Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)))
    }

    if (appearanceOpen) {
        AppearanceDialog(theme, onPick = onTheme, onDismiss = { appearanceOpen = false })
    }
}

@Composable
private fun Header(onHistory: () -> Unit, onAppearance: () -> Unit) {
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
                    text = { Text("History") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.List, contentDescription = null) },
                    onClick = { menuOpen = false; onHistory() },
                )
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

/** [cantStart] says why Start is disabled, or is null when it can be tapped. */
@Composable
private fun StatusCard(state: DictationState, cantStart: String?, onStart: () -> Unit, onStop: () -> Unit) {
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
                            Phase.Busy -> "Running speed test…"
                            Phase.Listening -> "Listening…"
                            Phase.Finishing -> "Finishing…"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
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
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val note = if (on) {
                    state.loadMs?.let { "Model loaded in ${seconds(it)}" + (state.backend?.let { b -> " · ${b.label}" } ?: "") }
                } else cantStart
                if (note != null) {
                    Text(
                        note,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(8.dp))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (on) {
                    TextButton(onClick = onStop) { Text("Turn off") }
                } else {
                    Button(
                        onClick = onStart,
                        enabled = cantStart == null,
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
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { stateDescription = if (done) "Done" else "Not done" }
            .padding(horizontal = 18.dp, vertical = 12.dp),
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
                    contentDescription = null,
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
    var text by rememberSaveable { mutableStateOf("") }
    // Read only where drawn or shown, so a new audio chunk redraws the waveform and the
    // transcript line, not the whole card.
    val live = Murmur.state.collectAsState()
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            AnimatedVisibility(listening) {
                Column(Modifier.padding(top = 12.dp)) {
                    Waveform(
                        { live.value.levels },
                        Modifier.fillMaxWidth().height(28.dp),
                        color = MaterialTheme.colorScheme.primary,
                        bars = 48,
                    )
                    Spacer(Modifier.height(8.dp))
                    LiveText(live)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stats(state) ?: if (ready || listening || state.phase == Phase.Busy) "" else "Start Murmur to dictate",
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
                    Modifier.size(56.dp).clip(CircleShape).background(bg).clickable(
                        enabled = ready || listening,
                        role = Role.Button,
                    ) {
                        val service = DictationService.instance ?: return@clickable
                        if (listening) service.finish()
                        else service.listen { result ->
                            text = listOf(text.trimEnd(), result).filter { it.isNotEmpty() }.joinToString(" ")
                        }
                    }.semantics { contentDescription = if (listening) "Stop dictation" else "Dictate here" },
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
                            contentDescription = null,
                            tint = if (ready) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The live transcript; its own scope, so each new word recomposes just this line. */
@Composable
private fun LiveText(live: State<DictationState>) {
    Text(
        live.value.partial.ifBlank { "Listening…" },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun seconds(ms: Long) = String.format(Locale.US, "%.1f s", ms / 1000f)

private fun stats(state: DictationState): String? {
    val audio = state.lastAudioMs ?: return null
    val latency = state.lastLatencyMs ?: return null
    return "Last: ${seconds(audio)} of speech · text ${latency} ms after stop"
}

/** Where the model runs (NPU or CPU) and, for the multilingual NPU model, the language. */
@Composable
private fun VoiceCard(state: DictationState, cpuInstalled: Boolean, tick: Int) {
    val ctx = LocalContext.current
    var useNpu by remember { mutableStateOf(Prefs.useNpu(ctx)) }
    var language by remember { mutableStateOf(Prefs.language(ctx)) }
    var npuFailed by remember(tick, state.backend) { mutableStateOf(Prefs.npuFailed(ctx)) }
    // Switching reloads the model, which can't happen mid-dictation or mid-load.
    val canSwitch = state.phase == Phase.Off || state.phase == Phase.Ready
    // Reads the model folders, so not on every recomposition.
    val preferred = remember(tick, useNpu, npuFailed) { Engine.preferredBackend(ctx) }
    val onNpu = (state.backend ?: preferred) == Backend.Npu

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp).animateContentSize()) {
            if (cpuInstalled) {
                Text("Runs on", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Backend.entries.forEachIndexed { i, b ->
                        SegmentedButton(
                            selected = (b == Backend.Npu) == useNpu,
                            onClick = {
                                val npu = b == Backend.Npu
                                if (npu == useNpu && !(npu && npuFailed)) return@SegmentedButton
                                useNpu = npu
                                // Choosing again clears the failure (see Prefs.setUseNpu).
                                npuFailed = false
                                Prefs.setUseNpu(ctx, npu)
                                DictationService.instance?.reloadModel()
                            },
                            enabled = canSwitch,
                            shape = SegmentedButtonDefaults.itemShape(i, Backend.entries.size),
                        ) { Text(b.label) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (useNpu) "Snapdragon's AI chip. Leaves the processor almost idle, so it's " +
                        "easier on the battery, and understands many languages."
                    else "The main processor, with the English-only model. Works the phone much harder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (useNpu && npuFailed) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The NPU couldn't start, so Murmur used the CPU. Tap NPU to try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            AnimatedVisibility(onNpu || (useNpu && !npuFailed)) {
                Column {
                    if (cpuInstalled) Spacer(Modifier.height(20.dp))
                    Text("Language", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        Language.entries.forEachIndexed { i, l ->
                            SegmentedButton(
                                selected = l == language,
                                onClick = {
                                    language = l
                                    Prefs.setLanguage(ctx, l)
                                },
                                shape = SegmentedButtonDefaults.itemShape(i, Language.entries.size),
                            ) { Text(l.label, maxLines = 1) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when (language) {
                            Language.English -> "Best accuracy for English."
                            Language.Hindi -> "For speaking Hindi."
                            Language.Auto -> "Recognises the language as you speak. Handy when you switch."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedCard(speed: SpeedTestState, enabled: Boolean) {
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
                    onClick = { speed.run() },
                    enabled = enabled && !speed.running,
                    shape = RoundedCornerShape(14.dp),
                ) { Text(if (speed.running) "Running…" else "Run") }
            }
            speed.result?.let { r ->
                Spacer(Modifier.height(14.dp))
                Text(
                    String.format(Locale.US, "%.0f× faster than real time", r.speedup),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "${seconds(r.audioMs)} of audio in ${seconds(r.decodeMs)}" +
                        (r.backend?.let { " on the ${it.label}" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${seconds(r.cpuMs)} of processor time",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("“${r.text}”", style = MaterialTheme.typography.bodySmall)
            }
            speed.error?.let {
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
            Column(Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    val selected = mode == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                            )
                            .selectable(selected, role = Role.RadioButton) { onPick(mode) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ThemeSwatch(mode)
                        Spacer(Modifier.size(14.dp))
                        Text(mode.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (selected) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
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
