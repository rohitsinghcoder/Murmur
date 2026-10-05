package com.murmur.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

const val BUBBLE_HEIGHT_DP = 44
const val PILL_WIDTH_DP = 264
const val BUBBLE_MARGIN_DP = 8
const val CONTENT_FADE_MS = 90

/** Total close time; the service waits this long before shrinking the overlay window. */
const val COLLAPSE_MS = 90L + 260L

private val Ink = Color(0xF51B1B1E)
private val Edge = Color(0x29FFFFFF)
private val Soft = Color(0xB3FFFFFF)
private val Warn = Color(0xFFFFB4AB)

/**
 * The floating control. A small mark when idle; while listening it widens into a pill with a
 * live waveform and transcript, a cancel button and a stop button.
 *
 * The overlay window has a fixed size for each mode; only this composable animates, which
 * keeps the growth smooth (resizing a window every frame makes it jitter).
 *
 * Closing is staged: the pill's contents fade out, then it shrinks, then the mark fades in.
 */
@Composable
fun Bubble(
    visible: Boolean,
    /** The bubble sits on the left half of the screen, so the pill widens to the right. */
    growRight: Boolean,
    insertedTick: Int,
    errorTick: Int,
    onTap: () -> Unit,
    onCancel: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: () -> Unit,
    onDragEnd: () -> Unit,
    onClose: () -> Unit,
    onSwitchSide: () -> Unit,
) {
    // The state changes every 50 ms while listening (the waveform). Only the parts that read
    // a value recompose when it changes; the waveform itself is only redrawn.
    val state = Murmur.state.collectAsState()
    val phase by remember { derivedStateOf { state.value.phase } }
    val active = phase.isActive

    var showDone by remember { mutableStateOf(false) }
    LaunchedEffect(insertedTick) {
        if (insertedTick > 0) {
            showDone = true
            delay(1000)
            showDone = false
        }
    }
    var showError by remember { mutableStateOf(false) }
    LaunchedEffect(errorTick) {
        if (errorTick > 0) {
            showError = true
            delay(1600)
            showError = false
        }
    }

    val width by animateDpAsState(
        if (active) PILL_WIDTH_DP.dp else BUBBLE_HEIGHT_DP.dp,
        animationSpec = if (active) tween(300, easing = FastOutSlowInEasing)
        else tween(260, delayMillis = CONTENT_FADE_MS, easing = FastOutSlowInEasing),
        label = "width",
    )
    val pillAlpha by animateFloatAsState(
        if (active) 1f else 0f,
        animationSpec = if (active) tween(180, delayMillis = 140) else tween(CONTENT_FADE_MS),
        label = "pill",
    )
    val markAlpha by animateFloatAsState(
        if (active) 0f else 1f,
        animationSpec = if (active) tween(90) else tween(160, delayMillis = CONTENT_FADE_MS + 200),
        label = "mark",
    )
    val shape = RoundedCornerShape(BUBBLE_HEIGHT_DP.dp / 2)
    val side = if (growRight) Alignment.CenterStart else Alignment.CenterEnd
    val label = when {
        active -> "Stop and type what you said"
        phase == Phase.Off -> "Murmur is off. Open Murmur"
        phase == Phase.Loading || phase == Phase.Busy -> "Murmur is loading"
        else -> "Dictate"
    }
    // The whole window, margin included, takes the touches: the margin is see-through but
    // would swallow taps anyway, so it may as well make the bubble easier to hit.
    val touches = remember { MutableInteractionSource() }

    // While the pill shrinks back, the window is still wide: a tap beside the shrinking pill
    // is meant for the app underneath, not a new dictation.
    val collapsing = !active && width > BUBBLE_HEIGHT_DP.dp
    Box(
        Modifier
            .fillMaxSize()
            .then(
                // A hidden bubble stays attached (for a quick return) but must not be found
                // by TalkBack or take touches.
                if (!visible) Modifier.clearAndSetSemantics {} else Modifier
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragEnd,
                        ) { change, _ ->
                            change.consume()
                            onDrag()
                        }
                    }
                    .semantics {
                        contentDescription = label
                        role = Role.Button
                        if (!active) {
                            // Dragging isn't possible with TalkBack; these do what dragging does.
                            customActions = listOf(
                                CustomAccessibilityAction("Move to the other side") { onSwitchSide(); true },
                                CustomAccessibilityAction("Close bubble") { onClose(); true },
                            )
                        }
                    }
                    .clickable(
                        interactionSource = touches,
                        indication = null,
                        enabled = !collapsing,
                        onClick = onTap,
                    )
            )
            .padding(BUBBLE_MARGIN_DP.dp),
        contentAlignment = side,
    ) {
        AnimatedVisibility(
            visible,
            enter = fadeIn(tween(140)) + scaleIn(tween(180), initialScale = 0.8f),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.9f),
        ) {
            Box(
                Modifier
                    .width(width)
                    .height(BUBBLE_HEIGHT_DP.dp)
                    .shadow(6.dp, shape)
                    .clip(shape)
                    .background(Ink)
                    .border(1.dp, Edge, shape)
                    .indication(touches, ripple(color = Color.White)),
                contentAlignment = side,
            ) {
                if (pillAlpha > 0f) {
                    // Laid out at full width and revealed by the growing clip, so nothing squashes.
                    Box(
                        Modifier.fillMaxHeight()
                            .wrapContentWidth(if (growRight) Alignment.Start else Alignment.End, unbounded = true)
                            .requiredWidth(PILL_WIDTH_DP.dp).alpha(pillAlpha),
                    ) {
                        ListeningPill(state, active, stopFirst = growRight, onCancel = onCancel, onStop = onTap)
                    }
                }
                if (markAlpha > 0f) {
                    Box(
                        Modifier.size(BUBBLE_HEIGHT_DP.dp).alpha(markAlpha),
                        contentAlignment = Alignment.Center,
                    ) {
                        val mark = when {
                            showError -> Mark.Error
                            showDone -> Mark.Done
                            else -> Mark.Idle
                        }
                        Crossfade(mark, animationSpec = tween(180), label = "mark") { m ->
                            Box(Modifier.size(BUBBLE_HEIGHT_DP.dp), contentAlignment = Alignment.Center) {
                                when {
                                    m == Mark.Error -> Icon(
                                        Icons.Rounded.Warning, null,
                                        tint = Warn, modifier = Modifier.size(20.dp),
                                    )
                                    m == Mark.Done -> Icon(
                                        Icons.Rounded.Check, "Inserted",
                                        tint = Color.White, modifier = Modifier.size(22.dp),
                                    )
                                    phase == Phase.Loading || phase == Phase.Busy -> CircularProgressIndicator(
                                        color = Color.White,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    else -> MurmurMark(
                                        color = if (phase == Phase.Off) Soft.copy(alpha = 0.4f) else Color.White,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class Mark { Idle, Done, Error }

/**
 * The open pill. [stopFirst]: the pill grows rightwards from the bubble, so the stop button
 * goes first, under the finger that started dictation; a second tap on the same spot stops
 * instead of cancelling.
 */
@Composable
private fun ListeningPill(
    state: State<DictationState>,
    active: Boolean,
    stopFirst: Boolean,
    onCancel: () -> Unit,
    onStop: () -> Unit,
) {
    // While the pill closes, the state is already reset: keep showing the last words and
    // waveform instead of the "Listening…" placeholder.
    val last = remember { LastShown() }
    val finishing by remember { derivedStateOf { state.value.phase == Phase.Finishing } }
    val partial by remember { derivedStateOf { state.value.partial } }
    if (active) last.partial = partial
    val text = if (active) partial else last.partial

    // Buttons get the pill's full height as their touch area; the visible circles stay small.
    // They stop working as soon as the pill starts closing.
    val cancel = @Composable {
        Box(
            Modifier.size(BUBBLE_HEIGHT_DP.dp).clip(CircleShape)
                .semantics { role = Role.Button }
                .clickable(enabled = active, onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, "Cancel dictation", tint = Soft, modifier = Modifier.size(16.dp))
        }
    }
    val stop = @Composable {
        Box(
            Modifier.size(BUBBLE_HEIGHT_DP.dp).clip(CircleShape)
                .semantics {
                    contentDescription = "Stop and type"
                    role = Role.Button
                }
                .clickable(enabled = active && !finishing, onClick = onStop),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                if (finishing) {
                    CircularProgressIndicator(color = Ink, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                } else {
                    Box(Modifier.size(11.dp).clip(RoundedCornerShape(3.dp)).background(Ink))
                }
            }
        }
    }
    // The pill's height is fixed, so very large font sizes are capped to keep the line whole.
    val density = LocalDensity.current
    val fontSize = with(density) { (12f * minOf(fontScale, 1.3f) / fontScale).sp }
    Row(
        Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (stopFirst) stop() else cancel()
        Column(Modifier.weight(1f).padding(horizontal = 2.dp)) {
            Waveform(
                levels = {
                    val levels = state.value.levels
                    if (active && levels.isNotEmpty()) last.levels = levels
                    if (active) levels else last.levels
                },
                Modifier.fillMaxWidth().height(14.dp),
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text.ifBlank { "Listening…" }.takeLast(60),
                color = if (text.isBlank()) Soft else Color.White,
                fontSize = fontSize,
                lineHeight = fontSize * 1.15f,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        }
        if (stopFirst) cancel() else stop()
    }
}

/** What the closing pill keeps showing. Not state: it only changes while the pill is open. */
private class LastShown {
    var partial = ""
    var levels: List<Float> = emptyList()
}

/**
 * Scrolling level meter: one rounded bar per recent audio chunk, newest on the right.
 * [levels] is read while drawing, so a new level only redraws the meter.
 */
@Composable
fun Waveform(levels: () -> List<Float>, modifier: Modifier, color: Color = Color.White, bars: Int = 28) {
    Canvas(modifier) {
        val shown = levels()
        val gap = size.width / bars
        val stroke = gap * 0.55f
        // The newest [bars] levels, right-aligned; empty slots on the left are faint stubs.
        val offset = bars - minOf(bars, shown.size)
        val skip = shown.size - (bars - offset)
        for (i in 0 until bars) {
            val level = if (i >= offset) shown[skip + i - offset] else 0f
            val h = (size.height * (0.12f + 0.88f * level)).coerceAtMost(size.height)
            val x = gap * i + gap / 2
            val alpha = if (i >= offset) 0.95f else 0.25f
            drawLine(
                color.copy(alpha = alpha),
                Offset(x, (size.height - h) / 2),
                Offset(x, (size.height + h) / 2),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Murmur's mark: five symmetric rounded bars, matching the app icon. */
@Composable
fun MurmurMark(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val heights = floatArrayOf(0.3f, 0.65f, 1f, 0.65f, 0.3f)
        val gap = size.width / heights.size
        val stroke = gap * 0.55f
        // Round caps add half a stroke at each end; keep the tallest bar inside the box.
        val maxHalf = (size.height - stroke) / 2
        heights.forEachIndexed { i, h ->
            val x = gap * i + gap / 2
            val half = maxHalf * h
            drawLine(
                color,
                Offset(x, size.height / 2 - half),
                Offset(x, size.height / 2 + half),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Size of the close target's window; the circle inside grows when the bubble is over it. */
const val CLOSE_TARGET_DP = 112

/** Drop zone at the bottom of the screen, shown while the bubble is dragged. */
@Composable
fun CloseTarget(visible: Boolean, armed: Boolean) {
    val scale by animateFloatAsState(if (armed) 1.25f else 1f, tween(140), label = "armed")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible,
            enter = fadeIn(tween(140)) + scaleIn(tween(180), initialScale = 0.6f),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f),
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(if (armed) Color.White else Ink)
                    .border(1.dp, Edge, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Close, "Close bubble",
                    tint = if (armed) Ink else Color.White, modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
