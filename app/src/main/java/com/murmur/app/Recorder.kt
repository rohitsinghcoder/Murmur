package com.murmur.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Only one dictation may use the microphone and the speech model at a time: the bubble
 * ([DictationService]) and the voice keyboard ([VoiceKeyboard]) both take this before recording
 * and give it back when their transcript is done.
 */
object MicGate {
    private var owner: Any? = null

    @Synchronized
    fun acquire(who: Any): Boolean {
        if (owner != null && owner !== who) return false
        owner = who
        return true
    }

    @Synchronized
    fun release(who: Any) {
        if (owner === who) owner = null
    }

    val isBusy: Boolean
        @Synchronized get() = owner != null
}

/** Loudness of a chunk mapped to 0..1: room noise stays near 0, normal speech fills it. */
fun micLevel(chunk: FloatArray): Float {
    var sum = 0.0
    for (s in chunk) sum += s * s
    val rms = sqrt(sum / chunk.size)
    val db = 20 * log10(rms + 1e-6)
    val x = ((db + 50) / 40).toFloat().coerceIn(0f, 1f)
    return x * x
}

/**
 * Records from the microphone and streams it through [Engine]'s model, one dictation at a
 * time: the same pipeline as [DictationService], without the foreground service. Used by the
 * voice keyboard, whose visible window is what allows it to record.
 *
 * Callbacks run on background threads.
 */
class VoiceRecorder(private val ctx: Context, private val callbacks: Callbacks) {

    interface Callbacks {
        /** Live transcript, already tidied. */
        fun onPartial(text: String) {}

        /** Recent microphone levels (0..1), newest last. */
        fun onLevels(levels: List<Float>) {}

        /** Recording stopped; the final words are being decoded. */
        fun onFinishing() {}

        /** The final, tidied transcript (may be blank). */
        fun onDone(text: String, audioMs: Long, latencyMs: Long)

        fun onCancelled() {}

        fun onError(message: String)
    }

    private sealed interface Outcome {
        data class Text(val text: String, val audioMs: Long, val latencyMs: Long) : Outcome
        data object Cancelled : Outcome
        data class Failed(val message: String) : Outcome
    }

    private companion object {
        /** Audio kept after the stop tap, for a word still being finished. */
        const val TAIL_MS = 200L

        /** Marks the end of the recording in the audio queue. */
        val END = FloatArray(0)
    }

    private val worker = Executors.newSingleThreadExecutor()

    @Volatile private var recording = false
    @Volatile private var cancelled = false

    /** Why the microphone stopped delivering audio in the current dictation, if it did. */
    @Volatile private var micError: String? = null

    /** A dictation is running (recording or finishing). */
    @Volatile
    var isBusy = false
        private set

    /**
     * Loads the model if needed, on the worker thread, and warms it up so the first dictation
     * is quick. [done] gets null on success or the error, on the worker thread.
     */
    fun preload(done: (Throwable?) -> Unit) {
        worker.execute {
            try {
                val fresh = !Engine.isLoaded
                Engine.load(ctx)
                // A throwaway dictation warms the model up. Skipped while the bubble is
                // dictating, since two dictations can't share the model at once.
                val warmup = Any()
                if (fresh && MicGate.acquire(warmup)) {
                    try {
                        Engine.transcriber(ctx).run {
                            try {
                                accept(FloatArray(SAMPLE_RATE))
                                finish()
                            } finally {
                                release()
                            }
                        }
                    } finally {
                        MicGate.release(warmup)
                    }
                }
                done(null)
            } catch (e: Throwable) {
                done(e)
            }
        }
    }

    /**
     * Starts a dictation. Returns false if one is already running here or elsewhere (the
     * bubble), in which case nothing happens.
     */
    fun start(): Boolean {
        if (isBusy || !MicGate.acquire(this)) return false
        isBusy = true
        recording = true
        cancelled = false
        try {
            worker.execute {
                val outcome = try {
                    session()
                } catch (e: Throwable) {
                    Outcome.Failed("Dictation failed: ${e.message}")
                }
                recording = false
                isBusy = false
                MicGate.release(this)
                when (outcome) {
                    is Outcome.Text -> callbacks.onDone(outcome.text, outcome.audioMs, outcome.latencyMs)
                    Outcome.Cancelled -> callbacks.onCancelled()
                    is Outcome.Failed -> callbacks.onError(outcome.message)
                }
            }
        } catch (e: Exception) {
            // The worker was shut down.
            recording = false
            isBusy = false
            MicGate.release(this)
            return false
        }
        return true
    }

    /** Stops listening and delivers the transcript. */
    fun finish() {
        recording = false
    }

    /** Stops listening and throws the transcript away. */
    fun cancel() {
        cancelled = true
        recording = false
    }

    /** Cancels any dictation and stops the worker once it's done. */
    fun shutdown() {
        cancel()
        worker.shutdown()
    }

    private fun session(): Outcome {
        micError = null
        val audio = try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
            )
            // Two seconds of headroom in case a decode step runs long.
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                max(minBuf, SAMPLE_RATE * 4 * 2),
            )
        } catch (e: SecurityException) {
            return Outcome.Failed("Microphone permission is missing.")
        } catch (e: IllegalArgumentException) {
            return Outcome.Failed("The microphone is busy or unavailable.")
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            return Outcome.Failed("The microphone is busy or unavailable.")
        }

        // The mic is read on its own thread and queued, so a slow decode step never makes the
        // recorder drop audio, and everything said before the tap still gets transcribed. It
        // starts before the model's stream is set up (which may mean loading the model), so
        // the first word isn't clipped.
        val queue = LinkedBlockingQueue<FloatArray>()
        val reader = Thread({ record(audio, queue) }, "murmur-ime-mic")
        reader.start()
        var transcriber: Transcriber? = null
        try {
            val t = Engine.transcriber(ctx).also { transcriber = it }
            var samples = 0L
            val pending = ArrayList<FloatArray>()
            while (true) {
                pending.add(queue.take())
                // Catch up in one step if audio queued up while the last chunk was decoding.
                queue.drainTo(pending)
                val ended = pending.removeAll { it === END }
                if (!cancelled) {
                    val chunk = concat(pending)
                    if (chunk.isNotEmpty()) {
                        samples += chunk.size
                        callbacks.onPartial(Cleanup.tidy(t.accept(chunk)))
                    }
                }
                pending.clear()
                if (ended) break
            }

            if (cancelled) return Outcome.Cancelled
            callbacks.onFinishing()
            val t0 = SystemClock.elapsedRealtime()
            val text = Cleanup.tidy(t.finish())
            val latency = SystemClock.elapsedRealtime() - t0
            // Nothing came through because the mic failed or another app holds it: say so
            // instead of silently typing nothing.
            micError?.takeIf { text.isBlank() }?.let { return Outcome.Failed(it) }
            return Outcome.Text(text, samples * 1000 / SAMPLE_RATE, latency)
        } catch (e: Throwable) {
            // Stop the recorder as well; it frees the mic after its current 50 ms read.
            cancelled = true
            recording = false
            reader.join(500)
            throw e
        } finally {
            transcriber?.release()
        }
    }

    /** Records until stopped, plus a short tail so a last word cut off by the tap is kept. */
    private fun record(audio: AudioRecord, queue: LinkedBlockingQueue<FloatArray>) {
        val buf = FloatArray(SAMPLE_RATE / 20) // 50 ms
        val levels = ArrayDeque<Float>()
        var stopAt = Long.MAX_VALUE
        try {
            audio.startRecording()
            if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                micError = "The microphone is busy or unavailable."
                return
            }
            while (!cancelled && SystemClock.elapsedRealtime() < stopAt) {
                if (!recording && stopAt == Long.MAX_VALUE) stopAt = SystemClock.elapsedRealtime() + TAIL_MS
                val n = audio.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    // Permission revoked or the audio server died: keep what was heard so far.
                    micError = "The microphone stopped working."
                    break
                }
                if (n == 0) continue
                val chunk = buf.copyOf(n)
                queue.put(chunk)
                levels.addLast(micLevel(chunk))
                if (levels.size > 36) levels.removeFirst()
                callbacks.onLevels(levels.toList())
            }
            // Android silences an app's recording (all zeros, no error) while another app,
            // such as a call, holds the mic -- or if it isn't allowed to record right now.
            if (runCatching { audio.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false)) {
                micError = "Another app is using the microphone."
            }
        } catch (e: Exception) {
            // An exception escaping this thread would crash the whole app.
            micError = "The microphone is busy or unavailable."
        } finally {
            runCatching { audio.stop() }
            audio.release()
            queue.put(END)
        }
    }

    private fun concat(chunks: List<FloatArray>): FloatArray {
        if (chunks.size == 1) return chunks[0]
        val out = FloatArray(chunks.sumOf { it.size })
        var at = 0
        for (c in chunks) {
            c.copyInto(out, at)
            at += c.size
        }
        return out
    }
}
