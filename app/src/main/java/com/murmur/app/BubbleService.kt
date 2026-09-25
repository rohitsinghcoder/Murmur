package com.murmur.app

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.os.Build
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
import androidx.compose.runtime.mutableStateOf
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

    /** Where the keyboard's keys started last time; used to show the bubble before it opens. */
    private var lastImeTop: Int? = null
    private val imeTopByApp = HashMap<String?, Int>()

    private val visible = mutableStateOf(false)

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
                    delay(COLLAPSE_MS + 80)
                    if (!Murmur.state.value.phase.isActive) setExpanded(false)
                }
                refresh()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED || type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val field = event.source?.takeIf { it.isEditable }
            if (field != null) {
                lastFocused = field
                // Don't wait for the keyboard window: show at its last known position so
                // the bubble arrives together with the keyboard.
                val pkg = event.packageName?.toString()
                (imeTopByApp[pkg] ?: lastImeTop)?.let { if (!visible.value) show(it) }
                main.removeCallbacks(confirmKeyboard)
                main.postDelayed(confirmKeyboard, 800)
                return
            }
        }
        refresh()
    }

    /** Hides a bubble shown early on a field tap if no keyboard actually appeared. */
    private val confirmKeyboard = Runnable { refresh() }

    override fun onInterrupt() {}

    override fun onDestroy() {
        main.removeCallbacks(confirmKeyboard)
        hide()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        params = null
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
            val top = keyboardTop(ime)
            lastImeTop = top
            // Keyboards differ per app (toolbars, suggestion strips); remember each one.
            lastFocused?.packageName?.toString()?.let { imeTopByApp[it] = top }
            show(top)
        } else if (!busy) {
            hide()
        }
    }

    /**
     * Top of the visible keys. The keyboard's window often covers far more of the screen
     * (Gboard reserves room for pop-ups), so use its touchable region where available.
     */
    private fun keyboardTop(ime: AccessibilityWindowInfo): Int {
        if (Build.VERSION.SDK_INT >= 33) {
            val region = Region()
            ime.getRegionInScreen(region)
            // Only full-width parts count: key-press pop-ups are narrow and come and go
            // while typing, and following them made the bubble shake.
            val minWidth = resources.displayMetrics.widthPixels * 0.9f
            var top = Int.MAX_VALUE
            val rect = Rect()
            val rects = RegionIterator(region)
            while (rects.next(rect)) {
                if (rect.width() >= minWidth) top = minOf(top, rect.top)
            }
            if (top != Int.MAX_VALUE) return top
        }
        val r = Rect()
        ime.getBoundsInScreen(r)
        return r.top
    }

    private val reanchor = Runnable {
        val lp = params ?: return@Runnable
        val y = pendingY ?: return@Runnable
        lp.y = y
        bubble?.let { wm.updateViewLayout(it, lp) }
    }
    private var pendingY: Int? = null

    private fun show(imeTop: Int) {
        val lp = params ?: createView(imeTop)
        if (!visible.value) {
            main.removeCallbacks(reanchor)
            lp.y = anchorY(imeTop)
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            bubble?.let { wm.updateViewLayout(it, lp) }
            visible.value = true
            return
        }
        // Move only once the keyboard has settled, and ignore tiny changes.
        val target = anchorY(imeTop)
        main.removeCallbacks(reanchor)
        if (kotlin.math.abs(target - lp.y) > dp(6)) {
            pendingY = target
            main.postDelayed(reanchor, 250)
        }
    }

    /**
     * The overlay is created once and then only shown or hidden: building a Compose view
     * from scratch each time the keyboard opened is what made the bubble appear late.
     */
    private fun createView(imeTop: Int): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            windowWidth(Murmur.state.value.phase.isActive),
            dp(BUBBLE_HEIGHT_DP + 2 * BUBBLE_MARGIN_DP),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Never take focus, so the keyboard and text field stay active underneath.
            // LAYOUT_IN_SCREEN: measure y from the top of the screen, like the keyboard bounds.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = Prefs.bubbleX(this@BubbleService) ?: dp(12)
            y = anchorY(imeTop)
        }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                Bubble(
                    visible = visible.value,
                    insertedTick = insertedTick.intValue,
                    onTap = ::onTap,
                    onCancel = { DictationService.instance?.cancel() },
                    onDrag = { dx, dy -> drag(dx, dy) },
                    onDragEnd = ::savePosition,
                )
            }
        }
        wm.addView(view, lp)
        bubble = view
        params = lp
        return lp
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

    /**
     * Where the bubble goes: the spot the user dragged it to (a fixed place on screen, so it
     * never jumps with keyboard height), else just above the keys. Either way it is kept
     * clear of the keyboard.
     */
    private fun anchorY(imeTop: Int): Int {
        val aboveKeys = imeTop - dp(BUBBLE_HEIGHT_DP + 2 * BUBBLE_MARGIN_DP + 6)
        // Older versions saved the position relative to the keyboard; convert it once.
        Prefs.bubbleLift(this)?.let { lift -> Prefs.setBubbleY(this, imeTop - lift) }
        val saved = Prefs.bubbleY(this) ?: return aboveKeys
        return minOf(saved, aboveKeys)
    }

    private fun hide() {
        main.removeCallbacks(reanchor)
        if (!visible.value) return
        visible.value = false
        // Stay attached (invisible) for an instant next show, but let touches through.
        val lp = params ?: return
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        bubble?.let { wm.updateViewLayout(it, lp) }
    }

    private fun drag(dx: Float, dy: Float) {
        val lp = params ?: return
        lp.x -= dx.roundToInt() // gravity END: x grows to the left
        lp.y += dy.roundToInt()
        bubble?.let { wm.updateViewLayout(it, lp) }
    }

    /** Saved once per drag rather than on every move event. */
    private fun savePosition() {
        val lp = params ?: return
        Prefs.setBubbleX(this, lp.x)
        Prefs.setBubbleY(this, lp.y)
    }


    private fun onTap() {
        val service = DictationService.instance
        when (Murmur.state.value.phase) {
            Phase.Off -> openApp("Open Murmur and tap Start to use the bubble")
            Phase.Loading, Phase.Finishing -> Unit
            Phase.Ready -> {
                if (service == null) return openApp("Open Murmur and tap Start to use the bubble")
                target = focusedEditable() ?: lastFocused
                service.listen(target?.packageName?.toString()) { text -> insert(text) }
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
