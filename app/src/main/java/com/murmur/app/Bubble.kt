package com.murmur.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

const val BUBBLE_HEIGHT_DP = 52

private val Ink = Color(0xFF141417)
private val Edge = Color(0x1FFFFFFF)
private val Accent = Color(0xFF8B7CF6)
private val Soft = Color(0xB3FFFFFF)

/**
 * The floating control. A small mic circle when idle; while listening it widens into a pill
 * with a live waveform and transcript, a cancel button and a stop button.
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
    val active = state.phase == Phase.Listening || state.phase == Phase.Finishing

    Box(Modifier.padding(8.dp)) {
        Box(
            Modifier
                .shadow(10.dp, CircleShape)
                .clip(RoundedCornerShape(BUBBLE_HEIGHT_DP.dp / 2))
                .background(Ink)
                .border(1.dp, Edge, RoundedCornerShape(BUBBLE_HEIGHT_DP.dp / 2))
                .pointerInput(Unit) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        onDrag(amount.x, amount.y)
                    }
                }
                .clickable(onClick = onTap)
                .animateContentSize(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
                .height(BUBBLE_HEIGHT_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = when {
                    showDone -> "done"
                    active -> "active"
                    else -> "idle"
                },
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith fadeOut() },
                label = "bubble",
            ) { mode ->
                when (mode) {
                    "done" -> Box(Modifier.size(BUBBLE_HEIGHT_DP.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, "Inserted", tint = Accent, modifier = Modifier.size(26.dp))
                    }
                    "active" -> ListeningPill(state, onCancel = onCancel, onStop = onTap)
                    else -> Box(Modifier.size(BUBBLE_HEIGHT_DP.dp), contentAlignment = Alignment.Center) {
                        if (state.phase == Phase.Loading) {
                            CircularProgressIndicator(
                                color = Accent,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(22.dp),
                            )
                        } else {
                            Icon(
                                painterResource(R.drawable.ic_mic),
                                contentDescription = "Dictate",
                                tint = if (state.phase == Phase.Ready) Color.White else Soft.copy(alpha = 0.45f),
                                modifier = Modifier.size(24.dp),
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
        Modifier.width(272.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, "Cancel", tint = Soft, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            Waveform(state.levels, Modifier.fillMaxWidth().height(18.dp))
            Spacer(Modifier.height(2.dp))
            Text(
                state.partial.ifBlank { "Listening…" }.takeLast(60),
                color = if (state.partial.isBlank()) Soft else Color.White,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        }
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Accent).clickable(onClick = onStop),
            contentAlignment = Alignment.Center,
        ) {
            if (state.phase == Phase.Finishing) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
            }
        }
    }
}

/** Scrolling level meter: one rounded bar per recent audio chunk, newest on the right. */
@Composable
fun Waveform(levels: List<Float>, modifier: Modifier, color: Color = Color.White, bars: Int = 36) {
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
