package com.murmur.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun copyText(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("Murmur", text))
}

fun shareText(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, null))
}

/**
 * "Today", "Yesterday", or a short date such as "Mon, 22 Sep". Earlier years add the year,
 * so the same day in two years doesn't become one group.
 */
fun dayLabel(ctx: Context, at: Long): String {
    val today = startOfDay(0)
    return when {
        at >= today -> "Today"
        at >= startOfDay(1) -> "Yesterday"
        else -> {
            val skeleton = if (at >= startOfYear()) "EEEdMMM" else "yMMMEd"
            DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton), at).toString()
        }
    }
}

private fun midnight(change: Calendar.() -> Unit): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    change()
}.timeInMillis

private fun startOfDay(daysAgo: Int): Long = midnight { add(Calendar.DAY_OF_YEAR, -daysAgo) }

private fun startOfYear(): Long = midnight { set(Calendar.DAY_OF_YEAR, 1) }

/** [time] is the phone's time format (12 or 24 hour), made once and shared by all rows. */
fun metaLine(time: java.text.DateFormat, d: Dictation): String = listOfNotNull(
    time.format(Date(d.at)),
    d.app,
    if (d.audioMs > 0) String.format(Locale.US, "%.0f s", d.audioMs / 1000f) else null,
).joinToString(" · ")

@Composable
fun HistoryScreen(
    items: List<Dictation>,
    loaded: Boolean,
    timeFormat: java.text.DateFormat,
    onBack: () -> Unit,
    onCopy: (Dictation) -> Unit,
    onDelete: (Dictation) -> Unit,
    onClearAll: () -> Unit,
) {
    val ctx = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val shown = remember(items, query) {
        val q = query.trim()
        if (q.isEmpty()) items
        else items.filter { it.text.contains(q, ignoreCase = true) || it.app?.contains(q, ignoreCase = true) == true }
    }
    // Day labels need a calendar per entry; work them out once per list, not per frame.
    val days = remember(shown) { shown.groupBy { dayLabel(ctx, it.at) } }
    val todayStart = remember { startOfDay(0) }
    val wordsToday = remember(items) { items.filter { it.at >= todayStart }.sumOf { it.words } }
    val wordsTotal = remember(items) { items.sumOf { it.words } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                // imePadding already lifts the list above the keyboard, which covers the nav bar.
                bottom = WindowInsets.navigationBars.exclude(WindowInsets.ime).asPaddingValues()
                    .calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "top") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack, modifier = Modifier.offset(x = (-8).dp)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                        Spacer(Modifier.weight(1f))
                        if (items.isNotEmpty()) {
                            Box(Modifier.offset(x = 8.dp)) {
                                var menu by remember { mutableStateOf(false) }
                                IconButton(onClick = { menu = true }) {
                                    Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                                }
                                DropdownMenu(
                                    expanded = menu,
                                    onDismissRequest = { menu = false },
                                    shape = RoundedCornerShape(20.dp),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Clear history") },
                                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                        onClick = { menu = false; confirmClear = true },
                                    )
                                }
                            }
                        }
                    }
                    Column(Modifier.padding(start = 4.dp, top = 12.dp, bottom = 16.dp)) {
                        Text("History", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (items.isEmpty()) (if (loaded) "Your dictations will appear here" else "")
                            else "${"%,d".format(wordsToday)} words today · ${"%,d".format(wordsTotal)} in total",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (items.isNotEmpty()) SearchField(query, onChange = { query = it })
                }
            }

            if (items.isEmpty()) {
                // Until the file is read, an empty list doesn't mean there is no history.
                if (loaded) item(key = "empty") {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 72.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(88.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_mic),
                                contentDescription = null,
                                modifier = Modifier.size(34.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        Text("Nothing yet", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Everything you dictate is saved here,\nonly on this phone.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else if (shown.isEmpty()) {
                item(key = "no-match") {
                    Text(
                        "No dictations match “${query.trim()}”",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 16.dp),
                    )
                }
            }

            days.forEach { (day, entries) ->
                item(key = "day-$day") {
                    Text(
                        day,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp).animateItem(),
                    )
                }
                items(entries, key = { it.id }) { d ->
                    DictationRow(
                        d,
                        modifier = Modifier.animateItem(),
                        timeFormat = timeFormat,
                        onCopy = { onCopy(d) },
                        onDelete = { onDelete(d) },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = {
                Text(
                    if (items.size == 1) "Your saved dictation will be deleted from this phone."
                    else "All ${items.size} saved dictations will be deleted from this phone."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClearAll() }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(10.dp))
            val style = MaterialTheme.typography.bodyLarge
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                "Search your dictations",
                                style = style,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/**
 * One saved dictation. Tap copies it; long-press offers share and delete, which TalkBack also
 * lists as actions. Deleting is immediate: the caller offers undo.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DictationRow(
    d: Dictation,
    modifier: Modifier,
    timeFormat: java.text.DateFormat,
    onCopy: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val meta = remember(d, timeFormat) { metaLine(timeFormat, d) }
    Box(modifier) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).combinedClickable(
                onClickLabel = "Copy",
                onClick = onCopy,
                onLongClickLabel = "More options",
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menu = true
                },
            ).semantics {
                customActions = listOfNotNull(
                    CustomAccessibilityAction("Share") { shareText(ctx, d.text); true },
                    onDelete?.let { delete -> CustomAccessibilityAction("Delete") { delete(); true } },
                )
            },
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                Text(
                    d.text,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            DropdownMenuItem(
                text = { Text("Share") },
                leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                onClick = { menu = false; shareText(ctx, d.text) },
            )
            if (onDelete != null) {
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}
