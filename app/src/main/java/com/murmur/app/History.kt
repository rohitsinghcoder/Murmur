package com.murmur.app

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File

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

    private fun file(ctx: Context) = File(ctx.filesDir, "history.jsonl")

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        val f = file(ctx)
        _items.value = if (f.exists()) {
            f.readLines().mapNotNull { line ->
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
            }.reversed()
        } else {
            emptyList()
        }
        loaded = true
    }

    @Synchronized
    fun add(ctx: Context, text: String, audioMs: Long, appPackage: String?) {
        load(ctx)
        val now = System.currentTimeMillis()
        val entry = Dictation(now, text, now, audioMs, appPackage?.let { appLabel(ctx, it) })
        _items.update { listOf(entry) + it }
        file(ctx).appendText(toJson(entry) + "\n")
    }

    @Synchronized
    fun delete(ctx: Context, id: Long) {
        _items.update { list -> list.filter { it.id != id } }
        rewrite(ctx)
    }

    @Synchronized
    fun clear(ctx: Context) {
        _items.value = emptyList()
        file(ctx).delete()
    }

    private fun rewrite(ctx: Context) {
        file(ctx).writeText(_items.value.reversed().joinToString("") { toJson(it) + "\n" })
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
