package com.murmur.app

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

data class Dictation(
    val id: Long,
    val text: String,
    val at: Long,
    val audioMs: Long,
    /** Name of the app the text went into, or null for Murmur's own test box. */
    val app: String?,
) {
    val words get() = text.split(Regex("\\s+")).count { it.isNotBlank() }
}

/**
 * Every finished dictation, newest first. Stored as one JSON object per line in app-private
 * storage, so adding an entry is a cheap append.
 */
object History {
    private val _items = MutableStateFlow<List<Dictation>>(emptyList())
    val items: StateFlow<List<Dictation>> = _items

    @Volatile private var loaded = false

    /** Oldest entries beyond this are dropped, so the file and the list stay small. */
    private const val MAX_ITEMS = 5000

    /** Disk writes run here, in order, so saving never blocks the UI or the bubble. */
    private val io = Executors.newSingleThreadExecutor()

    private fun file(ctx: Context) = File(ctx.filesDir, "history.jsonl")

    /** Queues a disk write; a failed one (disk full) is skipped rather than crashing the app. */
    private fun write(block: () -> Unit) = io.execute { runCatching(block) }

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        val f = file(ctx)
        val lines = if (f.exists()) runCatching { f.readLines() }.getOrDefault(emptyList()) else emptyList()
        val parsed = lines.filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                Dictation(
                    id = o.getLong("id"),
                    text = o.getString("text"),
                    at = o.getLong("at"),
                    audioMs = o.optLong("audioMs"),
                    app = o.optString("app").ifBlank { null },
                )
            }.getOrNull()
        }
        _items.value = parsed.takeLast(MAX_ITEMS).reversed()
        loaded = true
        // A line cut short (the app died mid-write) would also swallow the next entry
        // appended to it, so write the file back cleanly; likewise once it grows too long.
        if (parsed.size != lines.count { it.isNotBlank() } || parsed.size > MAX_ITEMS) rewrite(ctx)
    }

    @Synchronized
    fun add(ctx: Context, text: String, audioMs: Long, appPackage: String?) {
        load(ctx)
        val now = System.currentTimeMillis()
        // Ids must be unique ([delete] goes by id), even for two entries in the same millisecond.
        val id = maxOf(now, (_items.value.firstOrNull()?.id ?: 0) + 1)
        val entry = Dictation(id, text, now, audioMs, appPackage?.let { appLabel(ctx, it) })
        _items.update { listOf(entry) + it }
        if (_items.value.size > MAX_ITEMS * 11 / 10) {
            _items.update { it.take(MAX_ITEMS) }
            rewrite(ctx)
        } else {
            val line = toJson(entry) + "\n"
            write { file(ctx).appendText(line) }
        }
    }

    @Synchronized
    fun delete(ctx: Context, id: Long) {
        load(ctx)
        _items.update { list -> list.filter { it.id != id } }
        rewrite(ctx)
    }

    @Synchronized
    fun clear(ctx: Context) {
        _items.value = emptyList()
        // A later load() must not read back the file before it is deleted.
        loaded = true
        write { file(ctx).delete() }
    }

    /** Replaces the file with the current list, atomically: a crash midway keeps the old file. */
    private fun rewrite(ctx: Context) {
        val all = _items.value.reversed().joinToString("") { toJson(it) + "\n" }
        write {
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(all)
            if (!tmp.renameTo(f)) tmp.delete()
        }
    }

    private fun toJson(d: Dictation) = JSONObject()
        .put("id", d.id)
        .put("text", d.text)
        .put("at", d.at)
        .put("audioMs", d.audioMs)
        .put("app", d.app ?: "")
        .toString()

    private fun appLabel(ctx: Context, pkg: String): String? {
        if (pkg == ctx.packageName) return null
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
