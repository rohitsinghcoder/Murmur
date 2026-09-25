package com.murmur.app

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Shows the dictation bubble above the keyboard in any app and types the transcript into
 * the focused text field. Android offers no other way for an app to find and fill text
 * fields in other apps; this is the same approach Wispr Flow uses.
 */
class BubbleService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val owner = OverlayOwner()
    private lateinit var wm: WindowManager

    private var bubble: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var keyboardTop: Int? = null
    private var movedByUser = false

    /** The text field dictation will go into, captured when listening starts. */
    private var target: AccessibilityNodeInfo? = null
    private var lastFocused: AccessibilityNodeInfo? = null

    /** Bumped after a successful insert so the bubble can flash a check mark. */
    private val insertedTick = mutableIntStateOf(0)

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    override fun onServiceConnected() {
        wm = getSystemService(WindowManager::class.java)
        owner.start()
        // Keep the bubble up while dictation finishes, even if the keyboard closes.
        scope.launch {
            Murmur.state.map { it.phase.isActive }.distinctUntilChanged().collect { active ->
                if (active) {
                    setExpanded(true)
                } else {
                    // Let the pill finish shrinking before the window gets small again.
                    delay(COLLAPSE_MS)
                    if (!Murmur.state.value.phase.isActive) setExpanded(false)
                }
                refresh()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            event.source?.takeIf { it.isEditable }?.let { lastFocused = it }
        }
        refresh()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        hide()
        owner.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        val ime = try {
            windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (_: Exception) {
            null
        }
        val busy = Murmur.state.value.phase.isActive
        if (ime != null) {
            // Remember the field now, while the app (not the bubble) is the active window.
            if (!busy) focusedEditable()?.let { lastFocused = it }
            val r = Rect()
            ime.getBoundsInScreen(r)
            show(r.top)
        } else if (!busy) {
            hide()
        }
    }

    private fun show(imeTop: Int) {
        val newTop = imeTop != keyboardTop
        keyboardTop = imeTop
        val existing = params
        if (bubble != null && existing != null) {
            // Re-anchor above the keyboard when it moves, unless the user dragged the bubble.
            if (newTop && !movedByUser) {
                existing.y = anchorY(imeTop)
                wm.updateViewLayout(bubble, existing)
            }
            return
        }
        movedByUser = false
        val lp = WindowManager.LayoutParams(
            windowWidth(Murmur.state.value.phase.isActive),
            dp(BUBBLE_HEIGHT_DP + 2 * BUBBLE_MARGIN_DP),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Never take focus, so the keyboard and text field stay active underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = anchorY(imeTop)
        }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                Bubble(
                    insertedTick = insertedTick.intValue,
                    onTap = ::onTap,
                    onCancel = { DictationService.instance?.cancel() },
                    onDrag = { dx, dy -> drag(dx, dy) },
                )
            }
        }
        wm.addView(view, lp)
        bubble = view
        params = lp
    }

    /** Fixed window sizes: the pill animates inside the window, so it never relayouts per frame. */
    private fun windowWidth(expanded: Boolean) =
        dp((if (expanded) PILL_WIDTH_DP else BUBBLE_HEIGHT_DP) + 2 * BUBBLE_MARGIN_DP)

    private fun setExpanded(expanded: Boolean) {
        val lp = params ?: return
        val width = windowWidth(expanded)
        if (lp.width == width) return
        lp.width = width
        bubble?.let { wm.updateViewLayout(it, lp) }
    }

    private fun anchorY(imeTop: Int) = imeTop - dp(BUBBLE_HEIGHT_DP + 2 * BUBBLE_MARGIN_DP + 4)

    private fun hide() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        params = null
        keyboardTop = null
    }

    private fun drag(dx: Float, dy: Float) {
        val lp = params ?: return
        movedByUser = true
        lp.x -= dx.roundToInt() // gravity END: x grows to the left
        lp.y += dy.roundToInt()
        bubble?.let { wm.updateViewLayout(it, lp) }
    }

    private fun onTap() {
        val service = DictationService.instance
        when (Murmur.state.value.phase) {
            Phase.Off -> openApp("Open Murmur and tap Start to use the bubble")
            Phase.Loading, Phase.Finishing -> Unit
            Phase.Ready -> {
                if (service == null) return openApp("Open Murmur and tap Start to use the bubble")
                target = focusedEditable() ?: lastFocused
                service.listen { text -> insert(text) }
            }
            Phase.Listening -> service?.finish()
        }
    }

    private fun openApp(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val appWindows = try {
            windows.filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                    it.type == AccessibilityWindowInfo.TYPE_SYSTEM
            }
        } catch (_: Exception) {
            emptyList()
        }
        for (window in appWindows.sortedByDescending { it.isFocused }) {
            val node = window.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (node != null && node.isEditable) return node
        }
        return lastFocused?.takeIf { it.refresh() && it.isEditable }
    }

    /** Inserts [text] at the cursor of the field that was focused when dictation started. */
    private fun insert(text: String) {
        val node = target?.takeIf { it.refresh() } ?: focusedEditable()
        if (node == null) {
            copyToClipboard(text)
            Toast.makeText(this, "No text field found — copied to clipboard", Toast.LENGTH_SHORT).show()
            return
        }
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0 || start > current.length || end > current.length) {
            start = current.length
            end = current.length
        }
        val before = current.substring(0, minOf(start, end))
        val after = current.substring(maxOf(start, end))
        val needsSpace = before.isNotEmpty() && !before.last().isWhitespace()
        val insertion = (if (needsSpace) " " else "") + text

        val setText = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                before + insertion + after,
            )
        }
        val ok = if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText)) {
            val cursor = before.length + insertion.length
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                },
            )
            true
        } else {
            pasteInto(node, insertion)
        }
        if (ok) insertedTick.intValue++
        else {
            copyToClipboard(text)
            Toast.makeText(this, "Couldn't type here — copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    /** Fallback for fields that refuse ACTION_SET_TEXT: paste, then restore the clipboard. */
    private fun pasteInto(node: AccessibilityNodeInfo, text: String): Boolean {
        val cm = getSystemService(ClipboardManager::class.java)
        val previous = cm.primaryClip
        cm.setPrimaryClip(ClipData.newPlainText("Murmur", text))
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        main.postDelayed({ previous?.let { cm.setPrimaryClip(it) } }, 600)
        return ok
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Murmur", text))
    }
}

/** Minimal lifecycle so Compose can run inside an overlay window owned by a service. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
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
