package com.murmur.app

import android.content.Context
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SpeedResult(
    val audioMs: Long,
    val decodeMs: Long,
    /** Processor time Murmur used meanwhile; the NPU's work doesn't count towards it. */
    val cpuMs: Long,
    val backend: Backend?,
    val text: String,
) {
    /** How many times faster than real time the model ran. */
    val speedup get() = audioMs.toFloat() / decodeMs.coerceAtLeast(1)
}

/** Transcribes the bundled sample recording as fast as possible to measure model speed. */
object SpeedTest {
    fun sample(ctx: Context) = File(Engine.modelDir(ctx), "sample.wav")

    fun run(ctx: Context, wav: File = sample(ctx)): SpeedResult {
        val samples = readWav(wav)
        val t = Engine.transcriber(ctx)
        val t0 = SystemClock.elapsedRealtime()
        val cpu0 = Process.getElapsedCpuTime()
        val chunk = SAMPLE_RATE / 20
        val text = try {
            var i = 0
            while (i < samples.size) {
                t.accept(samples.copyOfRange(i, minOf(i + chunk, samples.size)))
                i += chunk
            }
            t.finish()
        } finally {
            t.release()
        }
        return SpeedResult(
            audioMs = samples.size * 1000L / SAMPLE_RATE,
            decodeMs = SystemClock.elapsedRealtime() - t0,
            cpuMs = Process.getElapsedCpuTime() - cpu0,
            backend = Engine.backend,
            text = text,
        )
    }

    /** Reads a 16 kHz, 16-bit mono PCM WAV file into floats. */
    private fun readWav(file: File): FloatArray {
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            if (size < 0) break
            if (id == "fmt " && size >= 16 && pos + 24 <= bytes.size) {
                // Any other format would be fed to the model as noise and give a bogus result.
                val format = buf.getShort(pos + 8).toInt() and 0xFFFF
                val channels = buf.getShort(pos + 10).toInt()
                val rate = buf.getInt(pos + 12)
                val bits = buf.getShort(pos + 22).toInt()
                require((format == 1 || format == 0xFFFE) && channels == 1 && rate == SAMPLE_RATE && bits == 16) {
                    "${file.name} must be 16 kHz 16-bit mono PCM " +
                        "(is ${rate / 1000.0} kHz, $bits-bit, $channels channel(s))"
                }
            }
            if (id == "data") {
                val count = minOf(size, bytes.size - pos - 8) / 2
                return FloatArray(count) { buf.getShort(pos + 8 + it * 2) / 32768f }
            }
            pos += 8 + size + (size and 1)
        }
        error("No audio data in ${file.name}")
    }
}
