package com.murmur.app

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
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
 *
 * Kept deliberately light: most events are dropped without touching the node tree, the
 * window list is read at most once per burst of window changes, and password fields are
 * never offered dictation or typed into.
 */
class BubbleService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val owner = OverlayOwner()
    private lateinit var wm: WindowManager

    private var root: TouchFrame? = null
    private var params: WindowManager.LayoutParams? = null

    /** Where the keyboard's keys started last time; used to show the bubble before it opens. */
    private var lastImeTop: Int? = null
    private val imeTopByApp = HashMap<String?, Int>()

    private val visible = mutableStateOf(false)

    /** Grow the pill to the right: the bubble sits on the left half of the screen. */
    private val growRight = mutableStateOf(false)
    private var expanded = false

    /** Collapsed bubble window's distance from the right screen edge, in px. */
    private var baseX = 0

    /** The text field dictation will go into, captured when listening starts. */
    private var target: AccessibilityNodeInfo? = null
    private var lastFocused: AccessibilityNodeInfo? = null

    /** Bumped after a successful insert so the bubble can flash a check mark. */
    private val insertedTick = mutableIntStateOf(0)

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    override fun onServiceConnected() {
        wm = getSystemService(WindowManager::class.java)
        baseX = Prefs.bubbleX(this) ?: dp(12)
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
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED -> onFieldEvent(event)
            // Window changes come in bursts (keyboard animating in, pop-ups): handle once per burst.
            else -> {
                main.removeCallbacks(refreshNow)
                main.post(refreshNow)
            }
        }
    }

    /**
     * A tap or focus on a text field. Anything else is ignored here without fetching its
     * node: clicks happen constantly in every app, and the keyboard opening or closing
     * arrives separately as a window change.
     */
    private fun onFieldEvent(event: AccessibilityEvent) {
        // Clicks: only text fields matter; checking the class name costs nothing.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.className?.contains("EditText") != true
        ) return
        val field = event.source?.takeIf { it.isEditable } ?: return
        if (event.isPassword || field.isPassword) {
            // Never offer dictation into a password field.
            lastFocused = null
            main.removeCallbacks(confirmKeyboard)
            if (!Murmur.state.value.phase.isActive) hide()
            return
        }
        lastFocused = field
        // Don't wait for the keyboard window: show at its last known position so
        // the bubble arrives together with the keyboard.
        val pkg = event.packageName?.toString()
        if (!visible.value && !ownKeyboardShown()) (imeTopByApp[pkg] ?: lastImeTop)?.let { show(it) }
        main.removeCallbacks(confirmKeyboard)
        main.postDelayed(confirmKeyboard, 800)
    }

    private val refreshNow = Runnable { refresh() }

    /** Hides a bubble shown early on a field tap if no keyboard actually appeared. */
    private val confirmKeyboard = Runnable { refresh() }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Keyboard positions from the old orientation would put the bubble in the wrong place.
        lastImeTop = null
        imeTopByApp.clear()
        val lp = params ?: return
        placeX(lp)
        lp.y = lp.y.coerceIn(0, maxOf(0, screenSize().y - lp.height))
        updateLayout(lp)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        root?.let { runCatching { wm.removeViewImmediate(it) } }
        root = null
        params = null
        target = null
        lastFocused = null
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
        if (ime == null || (!busy && ownKeyboardShown())) {
            if (!busy) hide()
            return
        }
        // Remember the field now, while the app (not the bubble) is the active window. Only
        // needed when the keyboard first appears; later field changes arrive as focus events.
        // Also drops a field left behind in another app, so dictation can't land there.
        if (!busy && (!visible.value || lastFocused == null)) lastFocused = focusedEditable()
        val top = keyboardTop(ime)
        lastImeTop = top
        // Keyboards differ per app (toolbars, suggestion strips); remember each one.
        lastFocused?.packageName?.toString()?.let { imeTopByApp[it] = top }
        if (!busy && lastFocused?.isPassword == true) hide() else show(top)
    }

    /** Murmur's own voice keyboard is up; it has its own controls, so no bubble on top. */
    private fun ownKeyboardShown(): Boolean {
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return ime?.startsWith("$packageName/") == true
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
            val minWidth = screenSize().x * 0.9f
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
        updateLayout(lp)
    }
    private var pendingY: Int? = null

    private fun show(imeTop: Int) {
        val lp = params ?: createView(imeTop)
        if (!visible.value) {
            main.removeCallbacks(reanchor)
            lp.y = anchorY(imeTop)
            placeX(lp)
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            updateLayout(lp)
            visible.value = true
            return
        }
        val y = anchorY(imeTop)
        main.removeCallbacks(reanchor)
        if (y < lp.y - dp(6)) {
            // The keyboard grew (emoji panel, toolbar): move now, before it covers the bubble.
            lp.y = y
            updateLayout(lp)
        } else if (kotlin.math.abs(y - lp.y) > dp(6)) {
            // Moving down waits until the keyboard has settled; tiny changes are ignored.
            pendingY = y
            main.postDelayed(reanchor, 250)
        }
    }

    /**
     * The overlay is created once and then only shown or hidden: building a Compose view
     * from scratch each time the keyboard opened is what made the bubble appear late.
     */
    private fun createView(imeTop: Int): WindowManager.LayoutParams {
        expanded = Murmur.state.value.phase.isActive
        growRight.value = baseX + windowWidth(false) / 2 > screenSize().x / 2
        val lp = WindowManager.LayoutParams(
            windowWidth(expanded),
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
            y = anchorY(imeTop)
        }
        placeX(lp)
        val compose = ComposeView(this).apply {
            setContent {
                Bubble(
                    visible = visible.value,
                    growRight = growRight.value,
                    insertedTick = insertedTick.intValue,
                    onTap = ::onTap,
                    onCancel = { DictationService.instance?.cancel() },
                    onDragStart = ::dragStart,
                    onDrag = ::drag,
                    onDragEnd = ::savePosition,
                )
            }
        }
        val frame = TouchFrame(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            addView(compose)
        }
        wm.addView(frame, lp)
        root = frame
        params = lp
        return lp
    }

    private fun updateLayout(lp: WindowManager.LayoutParams) {
        root?.let { runCatching { wm.updateViewLayout(it, lp) } }
    }

    private fun screenSize(): android.graphics.Point {
        val bounds = if (Build.VERSION.SDK_INT >= 30) {
            runCatching { wm.currentWindowMetrics.bounds }.getOrNull()
        } else {
            null
        }
        if (bounds != null) return android.graphics.Point(bounds.width(), bounds.height())
        val m = resources.displayMetrics
        return android.graphics.Point(m.widthPixels, m.heightPixels)
    }

    /** Fixed window sizes: the pill animates inside the window, so it never relayouts per frame. */
    private fun windowWidth(expanded: Boolean) =
        dp((if (expanded) PILL_WIDTH_DP else BUBBLE_HEIGHT_DP) + 2 * BUBBLE_MARGIN_DP)

    /**
     * Horizontal position. The bubble stays where the user put it; the wider pill grows away
     * from the nearer screen edge and is kept fully on screen.
     */
    private fun placeX(lp: WindowManager.LayoutParams) {
        val screenW = screenSize().x
        val small = windowWidth(false)
        baseX = baseX.coerceIn(0, maxOf(0, screenW - small))
        lp.x = if (!expanded) {
            baseX
        } else {
            // Gravity END: x is the distance from the right edge. Growing right keeps the
            // window's left edge where the bubble's was.
            val x = if (growRight.value) baseX - (lp.width - small) else baseX
            x.coerceIn(0, maxOf(0, screenW - lp.width))
        }
    }

    private fun setExpanded(expanded: Boolean) {
        val lp = params ?: return
        if (this.expanded == expanded && lp.width == windowWidth(expanded)) return
        this.expanded = expanded
        if (expanded) growRight.value = baseX + windowWidth(false) / 2 > screenSize().x / 2
        lp.width = windowWidth(expanded)
        placeX(lp)
        updateLayout(lp)
    }

    private fun aboveKeys(imeTop: Int) = imeTop - dp(BUBBLE_HEIGHT_DP + 2 * BUBBLE_MARGIN_DP + 6)

    /**
     * Where the bubble goes: the spot the user dragged it to (a fixed place on screen, so it
     * never jumps with keyboard height), else just above the keys. Either way it is kept
     * clear of the keyboard.
     */
    private fun anchorY(imeTop: Int): Int {
        val aboveKeys = aboveKeys(imeTop)
        // Older versions saved the position relative to the keyboard; convert it once.
        Prefs.bubbleLift(this)?.let { lift -> Prefs.setBubbleY(this, imeTop - lift) }
        val saved = Prefs.bubbleY(this) ?: return aboveKeys.coerceAtLeast(0)
        return minOf(saved, aboveKeys).coerceAtLeast(0)
    }

    private fun hide() {
        main.removeCallbacks(reanchor)
        if (!visible.value) return
        visible.value = false
        // Stay attached (invisible) for an instant next show, but let touches through.
        val lp = params ?: return
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        updateLayout(lp)
    }

    // Dragging follows the finger's screen position. Compose's deltas are relative to the
    // window, which is itself moving, so using them made the bubble lag and stutter.
    private var dragFromX = 0f
    private var dragFromY = 0f
    private var dragBaseX = 0
    private var dragBaseY = 0

    private fun dragStart() {
        val frame = root ?: return
        val lp = params ?: return
        main.removeCallbacks(reanchor)
        dragFromX = frame.rawX
        dragFromY = frame.rawY
        dragBaseX = baseX
        dragBaseY = lp.y
    }

    private fun drag() {
        val frame = root ?: return
        val lp = params ?: return
        baseX = dragBaseX - (frame.rawX - dragFromX).roundToInt() // gravity END: x grows to the left
        val maxY = lastImeTop?.let { aboveKeys(it) } ?: (screenSize().y - lp.height)
        lp.y = (dragBaseY + (frame.rawY - dragFromY).roundToInt()).coerceIn(0, maxOf(0, maxY))
        placeX(lp)
        updateLayout(lp)
    }

    /** Saved once per drag rather than on every move event. */
    private fun savePosition() {
        val lp = params ?: return
        Prefs.setBubbleX(this, baseX)
        Prefs.setBubbleY(this, lp.y)
    }

    private fun onTap() {
        val service = DictationService.instance
        when (Murmur.state.value.phase) {
            Phase.Off -> openApp("Open Murmur and tap Start to use the bubble")
            Phase.Loading, Phase.Finishing -> Unit
            Phase.Ready -> {
                if (service == null) return openApp("Open Murmur and tap Start to use the bubble")
                val field = focusedEditable() ?: lastFocused
                if (field?.isPassword == true) {
                    Toast.makeText(this, "Murmur doesn't type passwords", Toast.LENGTH_SHORT).show()
                    hide()
                    return
                }
                target = field
                if (!service.listen(field?.packageName?.toString()) { text -> insert(text) }) {
                    // The voice keyboard has the microphone right now.
                    target = null
                    Toast.makeText(this, "The microphone is in use", Toast.LENGTH_SHORT).show()
                }
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
        // One call that searches every window; the per-window walk below is the fallback.
        runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?.takeIf { it.isEditable }?.let { return it }
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
        return lastFocused?.takeIf { it.refresh() && it.isEditable && it.isFocused }
    }

    /** Inserts [text] at the cursor of the field that was focused when dictation started. */
    private fun insert(text: String) {
        val node = target?.takeIf { it.refresh() && it.isEditable } ?: focusedEditable()
        target = null
        if (node == null) {
            copyToClipboard(text)
            Toast.makeText(this, "No text field found — copied to clipboard", Toast.LENGTH_SHORT).show()
            return
        }
        if (node.isPassword) {
            // Never type into (or leave dictation near) a password field.
            Toast.makeText(this, "Murmur doesn't type passwords", Toast.LENGTH_SHORT).show()
            return
        }
        val raw = node.text?.toString().orEmpty()
        // Some fields report their hint as text without flagging it.
        val current = if (node.isShowingHintText || (raw.isNotEmpty() && raw == node.hintText?.toString())) "" else raw
        val selStart = node.textSelectionStart
        val selEnd = node.textSelectionEnd
        // A cursor beyond the text we can see means the field doesn't expose all of its
        // text; SET_TEXT would then wipe what's hidden, so only a paste is safe.
        val textKnown = selStart <= current.length && selEnd <= current.length
        val start: Int
        val end: Int
        if (selStart < 0 || selEnd < 0 || !textKnown) {
            start = current.length
            end = current.length
        } else {
            start = minOf(selStart, selEnd)
            end = maxOf(selStart, selEnd)
        }
        val before = current.substring(0, start)
        val after = current.substring(end)
        val lead = if (before.isNotEmpty() && !before.last().isWhitespace() && text.isNotEmpty() && text.first() !in NO_SPACE_BEFORE) " " else ""
        val trail = if (after.isNotEmpty() && after.first().isLetterOrDigit()) " " else ""
        val insertion = lead + text + trail

        val ok = if (textKnown && setText(node, before + insertion + after, before.length + insertion.length)) {
            true
        } else {
            pasteInto(node, if (textKnown) insertion else text)
        }
        if (ok) insertedTick.intValue++
        else {
            copyToClipboard(text)
            Toast.makeText(this, "Couldn't type here — copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setText(node: AccessibilityNodeInfo, value: String, cursor: Int): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            },
        )
        return true
    }

    /**
     * Fallback for fields that refuse ACTION_SET_TEXT: paste, then put the clipboard back.
     * Android may not let a background service read the clipboard; then there is nothing to
     * restore and the dictation is cleared from it instead of being left behind.
     */
    private fun pasteInto(node: AccessibilityNodeInfo, text: String): Boolean {
        val cm = getSystemService(ClipboardManager::class.java)
        val previous = runCatching { cm.primaryClip }.getOrNull()
        val clip = ClipData.newPlainText("Murmur", text)
        if (Build.VERSION.SDK_INT >= 33) {
            // Keeps the text out of the "copied" preview and keyboard clipboard suggestions.
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm.setPrimaryClip(clip)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        val restore = Runnable {
            runCatching { if (previous != null) cm.setPrimaryClip(previous) else cm.clearPrimaryClip() }
        }
        // On failure restore at once, so a following "copied to clipboard" isn't undone.
        if (ok) main.postDelayed(restore, 600) else restore.run()
        return ok
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Murmur", text))
    }

    private companion object {
        /** Dictation starting with these attaches to the previous word. */
        const val NO_SPACE_BEFORE = ".,!?;:)'’"
    }
}

/** Root of the overlay; remembers the latest touch's screen position for dragging. */
private class TouchFrame(ctx: Context) : FrameLayout(ctx) {
    var rawX = 0f
        private set
    var rawY = 0f
        private set

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        rawX = ev.rawX
        rawY = ev.rawY
        return super.dispatchTouchEvent(ev)
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
