package com.murmur.app

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

const val BUBBLE_HEIGHT_DP = 44
const val PILL_WIDTH_DP = 264
const val BUBBLE_MARGIN_DP = 8
const val COLLAPSE_MS = 320L

private val Ink = Color(0xF51B1B1E)
private val Edge = Color(0x29FFFFFF)
private val Soft = Color(0xB3FFFFFF)

/**
 * The floating control. A small mic circle when idle; while listening it widens into a pill
 * with a live waveform and transcript, a cancel button and a stop button.
 *
 * The overlay window has a fixed size for each mode; only this composable animates, which
 * keeps the growth smooth (resizing a window every frame makes it jitter).
 */
@Composable
fun Bubble(
    insertedTick: Int,
    onTap: () -> Unit,
    onCancel: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    val state by Murmur.state.collectAsState()
    var showDone by remember { mutableStateOf(false) }
    LaunchedEffect(insertedTick) {
        if (insertedTick > 0) {
            showDone = true
            delay(900)
            showDone = false
        }
    }
    val active = state.phase.isActive
    val width by animateDpAsState(
        if (active) PILL_WIDTH_DP.dp else BUBBLE_HEIGHT_DP.dp,
        animationSpec = tween(COLLAPSE_MS.toInt(), easing = FastOutSlowInEasing),
        label = "width",
    )
    val shape = RoundedCornerShape(BUBBLE_HEIGHT_DP.dp / 2)

    Box(
        Modifier.fillMaxSize().padding(BUBBLE_MARGIN_DP.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            Modifier
                .width(width)
                .height(BUBBLE_HEIGHT_DP.dp)
                .shadow(6.dp, shape)
                .clip(shape)
                .background(Ink)
                .border(1.dp, Edge, shape)
                .pointerInput(Unit) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        onDrag(amount.x, amount.y)
                    }
                }
                .clickable(onClick = onTap),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Crossfade(
                targetState = when {
                    active -> "active"
                    showDone -> "done"
                    else -> "idle"
                },
                animationSpec = tween(180),
                label = "bubble",
            ) { mode ->
                when (mode) {
                    // Laid out at full width and revealed by the growing clip, so nothing squashes.
                    "active" -> Box(
                        Modifier.fillMaxHeight().wrapContentWidth(Alignment.End, unbounded = true)
                            .requiredWidth(PILL_WIDTH_DP.dp),
                    ) {
                        ListeningPill(state, onCancel = onCancel, onStop = onTap)
                    }
                    "done" -> Box(Modifier.size(BUBBLE_HEIGHT_DP.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, "Inserted", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    else -> Box(Modifier.size(BUBBLE_HEIGHT_DP.dp), contentAlignment = Alignment.Center) {
                        if (state.phase == Phase.Loading) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            MurmurMark(
                                color = if (state.phase == Phase.Off) Soft.copy(alpha = 0.4f) else Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListeningPill(state: DictationState, onCancel: () -> Unit, onStop: () -> Unit) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, "Cancel", tint = Soft, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            Waveform(state.levels, Modifier.fillMaxWidth().height(14.dp))
            Spacer(Modifier.height(1.dp))
            Text(
                state.partial.ifBlank { "Listening…" }.takeLast(60),
                color = if (state.partial.isBlank()) Soft else Color.White,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        }
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(Color.White).clickable(onClick = onStop),
            contentAlignment = Alignment.Center,
        ) {
            if (state.phase == Phase.Finishing) {
                CircularProgressIndicator(color = Ink, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            } else {
                Box(Modifier.size(11.dp).clip(RoundedCornerShape(3.dp)).background(Ink))
            }
        }
    }
}

/** Scrolling level meter: one rounded bar per recent audio chunk, newest on the right. */
@Composable
fun Waveform(levels: List<Float>, modifier: Modifier, color: Color = Color.White, bars: Int = 28) {
    Canvas(modifier) {
        val gap = size.width / bars
        val stroke = gap * 0.55f
        val shown = levels.takeLast(bars)
        val offset = bars - shown.size
        for (i in 0 until bars) {
            val level = if (i >= offset) shown[i - offset] else 0f
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
