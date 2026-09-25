package com.murmur.app

import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode(val label: String) {
    System("System"),
    Light("Light"),
    Dark("Dark"),
    Amoled("AMOLED black"),
}

object Prefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("murmur", Context.MODE_PRIVATE)

    fun theme(ctx: Context): ThemeMode =
        prefs(ctx).getString("theme", null)
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: ThemeMode.System

    fun setTheme(ctx: Context, mode: ThemeMode) =
        prefs(ctx).edit().putString("theme", mode.name).apply()

    /** Bubble distance from the right edge, in px, once the user has dragged it. */
    fun bubbleX(ctx: Context): Int? = prefs(ctx).getInt("bubble_x", -1).takeIf { it >= 0 }

    /** Bubble window top, in px above the top of the keyboard. */
    fun bubbleLift(ctx: Context): Int? = prefs(ctx).getInt("bubble_lift", -1).takeIf { it >= 0 }

    fun setBubblePosition(ctx: Context, x: Int, lift: Int) =
        prefs(ctx).edit().putInt("bubble_x", x.coerceAtLeast(0)).putInt("bubble_lift", lift.coerceAtLeast(0)).apply()
}

@Composable
fun MurmurTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark, ThemeMode.Amoled -> true
    }
    val ctx = LocalContext.current
    val base = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    val scheme = if (mode == ThemeMode.Amoled) base.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0B0B0B),
        surfaceContainer = Color(0xFF111111),
        surfaceContainerHigh = Color(0xFF181818),
        surfaceContainerHighest = Color(0xFF202020),
    ) else base

    val activity = ctx as ComponentActivity
    DisposableEffect(dark) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose {}
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
