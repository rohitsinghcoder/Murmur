package com.murmur.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Runs the speed test on each installed backend and logs the results under the tag
 * "MurmurBench". Debug builds only; see scripts/benchmark.sh.
 */
class BenchmarkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val runs = intent.getIntExtra("runs", 3)
        // Optional: another 16 kHz WAV inside the app's files folder, e.g. "model-npu/en.wav".
        val wav = intent.getStringExtra("wav")?.let { File(ctx.filesDir, it) } ?: SpeedTest.sample(ctx)
        Thread {
            val useNpu = Prefs.useNpu(ctx)
            try {
                for (backend in listOf(Backend.Npu, Backend.Cpu)) bench(ctx, backend, runs, wav)
            } catch (e: Throwable) {
                Log.e(TAG, "failed", e)
            } finally {
                Prefs.setUseNpu(ctx, useNpu)
                Engine.unload()
                Log.i(TAG, "done")
            }
        }.start()
    }

    private fun bench(ctx: Context, backend: Backend, runs: Int, wav: File) {
        Prefs.setUseNpu(ctx, backend == Backend.Npu)
        Engine.unload()
        val t0 = SystemClock.elapsedRealtime()
        Engine.load(ctx)
        val loadMs = SystemClock.elapsedRealtime() - t0
        if (Engine.backend != backend) {
            Log.i(TAG, "$backend: not available (loaded ${Engine.backend})")
            return
        }
        SpeedTest.run(ctx, wav) // warm-up
        repeat(runs) {
            val r = SpeedTest.run(ctx, wav)
            Log.i(
                TAG,
                // Only the number goes through format(): a "%" in the transcript would throw.
                "$backend load=${loadMs}ms audio=${r.audioMs}ms decode=${r.decodeMs}ms " +
                    "speed=${"%.1f".format(r.speedup)}x cpu=${r.cpuMs}ms text=\"${r.text}\"",
            )
        }
    }

    companion object {
        const val TAG = "MurmurBench"
    }
}
