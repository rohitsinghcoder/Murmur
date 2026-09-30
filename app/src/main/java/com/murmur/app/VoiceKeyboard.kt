package com.murmur.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class KeyboardPhase { Setup, Loading, Idle, Listening, Finishing }

data class KeyboardState(
    val phase: KeyboardPhase = KeyboardPhase.Idle,
    val partial: String = "",
    val levels: List<Float> = emptyList(),
    /** Why dictation can't start (Setup), or what went wrong last time (Idle). */
    val message: String? = null,
)

/**
 * Murmur as a voice-only keyboard: dictation that needs no accessibility service (some banking
 * apps refuse to run while one is on). Other keyboards switch to it from their mic key, via its
 * "voice" subtype. It starts listening as soon as it opens, types the transcript through the
 * input connection when the user taps stop, then hands back to the previous keyboard.
 *
 * An input method whose window is showing may record, so it needs neither the microphone
 * foreground service nor the Start button.
 */
class VoiceKeyboard : InputMethodService() {

    private val main = Handler(Looper.getMainLooper())
    private val owner = KeyboardOwner()
    private val ui = MutableStateFlow(KeyboardState())
    private lateinit var recorder: VoiceRecorder

    /** The panel is on screen. */
    private var shown = false
    private var loading = false

    /** App the current dictation goes into, for history. */
    private var targetPackage: String? = null

    /** Passwords and incognito fields are typed but never saved to history. */
    private var privateField = false

    private val callbacks = object : VoiceRecorder.Callbacks {
        override fun onPartial(text: String) = ui.update {
            if (it.phase == KeyboardPhase.Listening) it.copy(partial = text) else it
        }

        override fun onLevels(levels: List<Float>) = ui.update {
            if (it.phase == KeyboardPhase.Listening) it.copy(levels = levels) else it
        }

        override fun onFinishing() = ui.update { it.copy(phase = KeyboardPhase.Finishing) }

        override fun onDone(text: String, audioMs: Long, latencyMs: Long) {
            main.post { deliver(text, audioMs) }
        }

        override fun onCancelled() {
            main.post { ui.value = KeyboardState(KeyboardPhase.Idle) }
        }

        override fun onError(message: String) {
            main.post { ui.value = KeyboardState(KeyboardPhase.Idle, message = message) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        owner.start()
        recorder = VoiceRecorder(this, callbacks)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        recorder.shutdown()
        owner.stop()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        // Compose finds its lifecycle through the view tree; an input method has none of its own.
        window.window?.decorView?.let {
            it.setViewTreeLifecycleOwner(owner)
            it.setViewTreeSavedStateRegistryOwner(owner)
        }
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val state by ui.collectAsState()
                VoicePanel(
                    state = state,
                    onMic = ::onMic,
                    onCancel = { recorder.cancel() },
                    onSwitch = ::switchBack,
                    onSpace = { currentInputConnection?.commitText(" ", 1) },
                    onBackspace = { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) },
                    // '\n' runs the field's action (send, search, next) or types a new line.
                    onEnter = { sendKeyChar('\n') },
                    onOpenApp = ::openApp,
                )
            }
        }
    }

    /** The panel is small; never take over the whole screen in landscape. */
    override fun onEvaluateFullscreenMode() = false

    override fun onWindowShown() {
        super.onWindowShown()
        shown = true
        begin()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        shown = false
        // Stop recording as soon as the panel goes away; what was said is still typed.
        if (recorder.isBusy) recorder.finish()
    }

    private fun onMic() {
        when (ui.value.phase) {
            KeyboardPhase.Listening -> recorder.finish()
            KeyboardPhase.Idle -> begin()
            KeyboardPhase.Setup -> openApp()
            KeyboardPhase.Loading, KeyboardPhase.Finishing -> Unit
        }
    }

    /** Starts listening, loading the model first if needed. */
    private fun begin() {
        if (recorder.isBusy || loading) return
        val problem = when {
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                "Murmur needs permission to use the microphone."
            !Engine.isModelInstalled(this) -> "The voice model isn't installed yet."
            else -> null
        }
        if (problem != null) {
            ui.value = KeyboardState(KeyboardPhase.Setup, message = problem)
            return
        }
        if (!Engine.isLoaded) {
            loading = true
            ui.value = KeyboardState(KeyboardPhase.Loading)
            recorder.preload { error ->
                main.post {
                    loading = false
                    when {
                        error != null -> ui.value = KeyboardState(
                            KeyboardPhase.Idle, message = "Couldn't load the voice model: ${error.message}"
                        )
                        shown -> begin()
                        else -> ui.value = KeyboardState(KeyboardPhase.Idle)
                    }
                }
            }
            return
        }
        val info = currentInputEditorInfo
        targetPackage = info?.packageName
        privateField = info != null && isPrivate(info)
        ui.value = KeyboardState(KeyboardPhase.Listening)
        if (!recorder.start()) {
            ui.value = KeyboardState(KeyboardPhase.Idle, message = "Murmur is already listening in the bubble.")
        }
    }

    /** Types the final transcript, saves it, and goes back to the previous keyboard. */
    private fun deliver(text: String, audioMs: Long) {
        if (text.isBlank()) {
            ui.value = KeyboardState(KeyboardPhase.Idle, message = "Didn't catch that. Tap the mic to try again.")
            return
        }
        ui.value = KeyboardState(KeyboardPhase.Idle)
        val typed = type(text.trim())
        if (!privateField) History.add(this, text, audioMs, targetPackage)
        if (!typed) {
            if (!privateField) {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Murmur", text))
            }
            ui.value = KeyboardState(
                KeyboardPhase.Idle,
                message = if (privateField) "Couldn't type here." else "Couldn't type here — copied to clipboard.",
            )
            return
        }
        if (shown) switchBack()
    }

    /** Commits [text] at the cursor, with a space before it when it continues earlier text. */
    private fun type(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val space = before.isNotEmpty() &&
            !before.last().isWhitespace() &&
            before.last() !in NO_SPACE_AFTER &&
            text.first() !in NO_SPACE_BEFORE
        ic.beginBatchEdit()
        val ok = ic.commitText(if (space) " $text" else text, 1)
        ic.endBatchEdit()
        return ok
    }

    private fun isPrivate(info: EditorInfo): Boolean {
        val type = info.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val password = when (cls) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        val incognito = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        return password || incognito
    }

    /** Back to the keyboard the user came from; the system picker if there isn't one. */
    private fun switchBack() {
        if (recorder.isBusy) recorder.cancel()
        if (switchToPreviousInputMethod()) return
        if (shouldOfferSwitchingToNextInputMethod() && switchToNextInputMethod(false)) return
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private companion object {
        /** No space is added after these (an opening bracket or quote)... */
        const val NO_SPACE_AFTER = "([{\"“‘/@#"

        /** ...or before a transcript starting with these. */
        const val NO_SPACE_BEFORE = ",.?!:;)]}"
    }
}

private val Panel = Color(0xFF1B1B1E)
private val KeyFill = Color(0x1FFFFFFF)
private val Soft = Color(0xB3FFFFFF)

@Composable
private fun VoicePanel(
    state: KeyboardState,
    onMic: () -> Unit,
    onCancel: () -> Unit,
    onSwitch: () -> Unit,
    onSpace: () -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val active = state.phase == KeyboardPhase.Listening || state.phase == KeyboardPhase.Finishing
    MaterialTheme(colorScheme = darkColorScheme()) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Panel)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().height(84.dp).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (state.phase) {
                    KeyboardPhase.Listening, KeyboardPhase.Finishing -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Waveform(state.levels, Modifier.width(180.dp).height(16.dp))
                        Spacer(Modifier.height(8.dp))
                        // Reverse scrolling keeps the newest words in view as the text grows.
                        Box(Modifier.fillMaxWidth().height(60.dp).verticalScroll(rememberScrollState(), reverseScrolling = true)) {
                            Text(
                                state.partial.ifBlank { if (state.phase == KeyboardPhase.Finishing) "…" else "Listening…" },
                                color = if (state.partial.isBlank()) Soft else Color.White,
                                fontSize = 16.sp,
                                lineHeight = 20.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    KeyboardPhase.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Loading voice model…", color = Soft, fontSize = 14.sp)
                    }
                    KeyboardPhase.Setup -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.message.orEmpty(), color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White)
                                .clickable(onClick = onOpenApp).padding(horizontal = 18.dp, vertical = 8.dp),
                        ) {
                            Text("Open Murmur", color = Panel, fontSize = 14.sp)
                        }
                    }
                    KeyboardPhase.Idle -> Text(
                        state.message ?: "Tap the mic to dictate",
                        color = if (state.message != null) Color.White else Soft,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().height(92.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Key(onClick = onSwitch) { KeyIcon(painterResource(R.drawable.ic_keyboard), "Switch keyboard") }
                if (active) {
                    Key(onClick = onCancel) { KeyIcon(Icons.Rounded.Close, "Cancel") }
                } else {
                    Key(onClick = onSpace) { KeyIcon(painterResource(R.drawable.ic_space), "Space") }
                }
                MicButton(state.phase, onMic)
                RepeatKey(onPress = onBackspace) { KeyIcon(painterResource(R.drawable.ic_backspace), "Delete") }
                Key(onClick = onEnter) { KeyIcon(painterResource(R.drawable.ic_enter), "Enter") }
            }
        }
    }
}

@Composable
private fun MicButton(phase: KeyboardPhase, onClick: () -> Unit) {
    val enabled = phase != KeyboardPhase.Loading && phase != KeyboardPhase.Finishing
    Box(
        Modifier.size(72.dp).clip(CircleShape)
            .background(if (phase == KeyboardPhase.Setup) KeyFill else Color.White)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (phase) {
            KeyboardPhase.Loading, KeyboardPhase.Finishing ->
                CircularProgressIndicator(color = Panel, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            KeyboardPhase.Listening ->
                Box(Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(Panel))
            KeyboardPhase.Idle, KeyboardPhase.Setup -> Icon(
                painterResource(R.drawable.ic_mic),
                contentDescription = "Dictate",
                tint = if (phase == KeyboardPhase.Setup) Soft else Panel,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

@Composable
private fun Key(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(KeyFill).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A key that repeats while held, like backspace on a normal keyboard. */
@Composable
private fun RepeatKey(onPress: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val action by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier.size(52.dp).clip(CircleShape)
            .background(if (pressed) Soft.copy(alpha = 0.3f) else KeyFill)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    action()
                    val repeat = scope.launch {
                        delay(400)
                        while (true) {
                            action()
                            delay(60)
                        }
                    }
                    tryAwaitRelease()
                    repeat.cancel()
                    pressed = false
                })
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun KeyIcon(painter: androidx.compose.ui.graphics.painter.Painter, label: String) =
    Icon(painter, contentDescription = label, tint = Color.White, modifier = Modifier.size(22.dp))

@Composable
private fun KeyIcon(vector: ImageVector, label: String) =
    Icon(vector, contentDescription = label, tint = Color.White, modifier = Modifier.size(22.dp))

/** Minimal lifecycle so Compose can run inside the input method's window. */
private class KeyboardOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    fun start() {
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
