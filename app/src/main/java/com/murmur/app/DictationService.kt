package com.murmur.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Foreground service of type "microphone". Android only lets an app use the mic from the
 * background if such a service was started while the app was on screen, so the user starts
 * this once from the app and the bubble can then dictate in any app.
 */
class DictationService : Service() {

    companion object {
        private const val ACTION_STOP = "com.murmur.app.STOP"
        private const val CHANNEL = "murmur"
        private const val NOTIFICATION_ID = 1

        /** Audio kept after the stop tap, for a word still being finished. */
        private const val TAIL_MS = 200L

        /** A dictation in which no new words came for this long stops by itself. */
        private const val AUTO_STOP_MS = 30_000L

        /** Waveform bars kept: the widest waveform (the app's try-it box) shows this many. */
        private const val LEVELS = 48

        /** Marks the end of the recording in the audio queue. */
        private val END = FloatArray(0)

        /**
         * Runs everything that touches the model, one job at a time. Shared by all instances
         * of the service: an instance stopped and quickly started again must not have its
         * old unload run after the new instance's load.
         */
        private val worker = Executors.newSingleThreadExecutor { r ->
            Thread({
                // Above normal apps: the user is waiting on this.
                Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
                r.run()
            }, "murmur-model")
        }

        @Volatile
        var instance: DictationService? = null
            private set

        fun start(ctx: Context) =
            ctx.startForegroundService(Intent(ctx, DictationService::class.java))

        fun stop(ctx: Context) =
            ctx.startService(Intent(ctx, DictationService::class.java).setAction(ACTION_STOP))
    }

    private val main = Handler(Looper.getMainLooper())

    /**
     * One dictation's flags. Each dictation gets its own, so a recorder thread that is slow
     * to stop can't be revived by the next dictation resetting shared flags.
     */
    private class Session {
        @Volatile var recording = true
        @Volatile var cancelled = false

        /** Why the microphone stopped delivering audio, if it did. */
        @Volatile var micError: String? = null
    }

    @Volatile private var session: Session? = null

    /** Set in [onDestroy]: work still finishing on the worker must not report Ready. */
    @Volatile private var destroyed = false

    /** This instance has a model load queued or running. */
    @Volatile private var loading = false

    /** The phase to return to after a dictation or load. */
    private val idle get() = if (destroyed) Phase.Off else Phase.Ready

    /**
     * Updates the shared state, unless a newer instance of the service has taken over: work
     * left over from a stopped instance must not overwrite what the new one reports.
     */
    private fun report(change: (DictationState) -> DictationState) {
        val current = instance
        if (current == null || current === this) Murmur.update(change)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Android 14+ refuses a microphone service without the mic permission or when the
            // app isn't on screen; that throws here instead of crashing the app.
            Murmur.update { it.copy(phase = Phase.Off, error = "Couldn't start Murmur: ${e.message}") }
            stopSelf()
            return START_NOT_STICKY
        }

        val phase = Murmur.state.value.phase
        if (phase.isActive || phase == Phase.Busy || loading) {
            // Started again mid-dictation, or while this instance loads: leave it running.
        } else if (phase != Phase.Ready || !Engine.isLoaded) {
            // Queued behind anything a stopped instance left on the worker (its unload, or a
            // load still running), so the model is loaded and warm when this reports Ready.
            loadModel()
        }
        // Not sticky: Android won't allow restarting a mic service from the background anyway.
        return START_NOT_STICKY
    }

    private fun loadModel() {
        loading = true
        Murmur.update { it.copy(phase = Phase.Loading, error = null) }
        worker.execute {
            try {
                warmUp()
            } finally {
                loading = false
            }
        }
    }

    /** Loads the model if needed and runs a throwaway dictation, so the first real one is quick. */
    private fun warmUp() {
        if (destroyed) return
        val t0 = SystemClock.elapsedRealtime()
        try {
            val wasLoaded = Engine.isLoaded
            Engine.transcriber(this).run {
                try {
                    accept(FloatArray(SAMPLE_RATE))
                    finish()
                } finally {
                    release()
                }
            }
            val ms = SystemClock.elapsedRealtime() - t0
            report {
                it.copy(
                    phase = idle,
                    loadMs = if (wasLoaded) it.loadMs ?: ms else ms,
                    backend = Engine.backend,
                )
            }
        } catch (e: Throwable) {
            report { it.copy(phase = Phase.Off, error = "Couldn't load the voice model: ${e.message}") }
            main.post { stopSelf() }
        }
    }

    /** Swaps in the model for the current settings (NPU or CPU). Only between dictations. */
    fun reloadModel() {
        if (destroyed || Murmur.state.value.phase != Phase.Ready) return
        // The worker runs dictations one at a time, so nothing is using the model meanwhile.
        worker.execute { Engine.unload() }
        loadModel()
    }

    override fun onDestroy() {
        destroyed = true
        cancel()
        if (instance === this) instance = null
        Murmur.update { it.copy(phase = Phase.Off, partial = "", levels = emptyList()) }
        // Murmur is off: give the model's memory back (hundreds of MB) once the worker is
        // done. The process stays alive for the bubble, so otherwise it would never be freed.
        // A new instance started meanwhile queues its load after this, so it loads again.
        worker.execute { if (instance == null) Engine.unload() }
        super.onDestroy()
    }

    /**
     * Runs the speed test on the worker, so no dictation can use the model at the same time;
     * [onResult] gets the outcome on the main thread. False if Murmur isn't ready.
     */
    fun speedTest(onResult: (Result<SpeedResult>) -> Unit): Boolean {
        if (destroyed || Murmur.state.value.phase != Phase.Ready) return false
        Murmur.update { it.copy(phase = Phase.Busy) }
        worker.execute {
            val result = runCatching { SpeedTest.run(this) }
            report { it.copy(phase = if (it.phase == Phase.Busy) idle else it.phase) }
            main.post { onResult(result) }
        }
        return true
    }

    /**
     * Starts listening; [onText] gets the final transcript on the main thread. The result is
     * also saved to [History], tagged with [appPackage] (the app it is typed into).
     */
    fun listen(appPackage: String? = null, onText: (String) -> Unit): Boolean {
        if (destroyed || Murmur.state.value.phase != Phase.Ready) return false
        val s = Session().also { session = it }
        Murmur.update { it.copy(phase = Phase.Listening, partial = "", levels = emptyList(), error = null) }
        worker.execute {
            try {
                runSession(s, appPackage, onText)
            } catch (e: Throwable) {
                // Never leave the bubble stuck in the listening pill.
                s.recording = false
                report {
                    it.copy(phase = idle, partial = "", levels = emptyList(), error = "Dictation failed: ${e.message}")
                }
            }
        }
        return true
    }

    /** Stops listening and delivers the transcript. */
    fun finish() {
        session?.recording = false
    }

    /** Stops listening and throws the transcript away, also while the last words are decoded. */
    fun cancel() {
        session?.let {
            it.cancelled = true
            it.recording = false
        }
    }

    private fun runSession(s: Session, appPackage: String?, onText: (String) -> Unit) {
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
            fail("Microphone permission is missing.")
            return
        } catch (e: IllegalArgumentException) {
            fail("The microphone is busy or unavailable.")
            return
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            fail("The microphone is busy or unavailable.")
            return
        }

        // The mic is read on its own thread and queued, so a slow decode step never makes the
        // recorder drop audio, and everything said before the tap still gets transcribed. It
        // starts before the model's stream is set up, so the first word isn't clipped.
        val queue = LinkedBlockingQueue<FloatArray>()
        val reader = Thread({ record(s, audio, queue) }, "murmur-mic")
        reader.start()
        // Pause music and videos while dictating: the microphone would pick them up too.
        val audioManager = getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        runCatching { audioManager.requestAudioFocus(focus) }
        var transcriber: Transcriber? = null
        try {
            val t = Engine.transcriber(this).also { transcriber = it }
            // The tidying rules are English: in other languages they would drop real words.
            val english = Engine.backend != Backend.Npu || Prefs.language(this) == Language.English
            var samples = 0L
            var lastText = ""
            var lastChange = SystemClock.elapsedRealtime()
            val pending = ArrayList<FloatArray>()
            while (true) {
                pending.add(queue.take())
                // Catch up in one step if audio queued up while the last chunk was decoding.
                queue.drainTo(pending)
                val ended = pending.removeAll { it === END }
                if (!s.cancelled) {
                    val chunk = concat(pending)
                    if (chunk.isNotEmpty()) {
                        samples += chunk.size
                        t.accept(chunk)?.let { raw ->
                            val text = Cleanup.tidy(raw, english, live = true)
                            if (text != lastText) {
                                lastText = text
                                lastChange = SystemClock.elapsedRealtime()
                                report { it.copy(partial = text) }
                            }
                        }
                    }
                }
                pending.clear()
                if (ended) break
                // A pill left open by mistake would keep the microphone and model busy.
                if (s.recording && SystemClock.elapsedRealtime() - lastChange > AUTO_STOP_MS) {
                    s.recording = false
                }
            }

            if (s.cancelled) {
                report { it.copy(phase = idle, partial = "", levels = emptyList()) }
                return
            }
            report { it.copy(phase = Phase.Finishing) }
            val t0 = SystemClock.elapsedRealtime()
            val text = Cleanup.tidy(t.finish(), english)
            val latency = SystemClock.elapsedRealtime() - t0
            // Nothing came through because the mic failed or another app holds it: say so
            // instead of silently typing nothing.
            val problem = if (text.isBlank()) s.micError ?: "Didn't catch anything" else null
            // Insert first, then leave the active phase: the pill then collapses straight into
            // the check mark instead of flashing the idle icon in between.
            val audioMs = samples * 1000 / SAMPLE_RATE
            main.post {
                if (s.cancelled) {
                    // Cancelled while the last words were being decoded.
                    report { it.copy(phase = idle, partial = "", levels = emptyList()) }
                    return@post
                }
                if (text.isNotBlank()) {
                    onText(text)
                    History.add(this, text, audioMs, appPackage)
                }
                report {
                    it.copy(
                        phase = idle,
                        partial = "",
                        levels = emptyList(),
                        error = problem,
                        lastLatencyMs = latency,
                        lastAudioMs = audioMs,
                    )
                }
            }
        } catch (e: Throwable) {
            // Stop the recorder as well; it frees the mic after its current 50 ms read.
            s.cancelled = true
            s.recording = false
            reader.join(500)
            throw e
        } finally {
            transcriber?.release()
            runCatching { audioManager.abandonAudioFocusRequest(focus) }
        }
    }

    /** Records until the user stops, plus a short tail so a last word cut off by the tap is kept. */
    private fun record(s: Session, audio: AudioRecord, queue: LinkedBlockingQueue<FloatArray>) {
        // A late read drops audio and stutters the waveform; this thread does very little work.
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = FloatArray(SAMPLE_RATE / 20) // 50 ms
        val levels = ArrayDeque<Float>()
        var stopAt = Long.MAX_VALUE
        try {
            audio.startRecording()
            if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                s.micError = "The microphone is busy or unavailable."
                return
            }
            while (!s.cancelled && SystemClock.elapsedRealtime() < stopAt) {
                if (!s.recording && stopAt == Long.MAX_VALUE) stopAt = SystemClock.elapsedRealtime() + TAIL_MS
                val n = audio.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    // Permission revoked or the audio server died: keep what was heard so far.
                    s.micError = "The microphone stopped working."
                    break
                }
                if (n == 0) continue
                val chunk = buf.copyOf(n)
                queue.put(chunk)
                levels.addLast(level(chunk))
                if (levels.size > LEVELS) levels.removeFirst()
                Murmur.update { it.copy(levels = levels.toList()) }
            }
            // Android silences an app's recording (all zeros, no error) while another app,
            // such as a call, holds the mic.
            if (runCatching { audio.activeRecordingConfiguration?.isClientSilenced == true }.getOrDefault(false)) {
                s.micError = "Another app is using the microphone."
            }
        } catch (e: Exception) {
            // An exception escaping this thread would crash the whole app.
            s.micError = "The microphone is busy or unavailable."
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

    private fun fail(message: String) {
        report { it.copy(phase = idle, partial = "", levels = emptyList(), error = message) }
    }

    /** Loudness of a chunk mapped to 0..1: room noise stays near 0, normal speech fills it. */
    private fun level(chunk: FloatArray): Float {
        var sum = 0.0
        for (s in chunk) sum += s * s
        val rms = sqrt(sum / chunk.size)
        val db = 20 * log10(rms + 1e-6)
        val x = ((db + 50) / 40).toFloat().coerceIn(0f, 1f)
        return x * x
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Murmur ready", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Murmur is ready to dictate"
                    setShowBadge(false)
                }
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, DictationService::class.java).setAction(ACTION_STOP),
            flags,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Murmur is ready")
            .setContentText("Tap the bubble above your keyboard to dictate")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .build()
    }
}
