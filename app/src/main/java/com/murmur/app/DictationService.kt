package com.murmur.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
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

        @Volatile
        var instance: DictationService? = null
            private set

        fun start(ctx: Context) =
            ctx.startForegroundService(Intent(ctx, DictationService::class.java))

        fun stop(ctx: Context) =
            ctx.startService(Intent(ctx, DictationService::class.java).setAction(ACTION_STOP))
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile private var recording = false
    @Volatile private var cancelled = false

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
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        if (Engine.isLoaded) {
            Murmur.update { it.copy(phase = Phase.Ready, error = null) }
        } else if (Murmur.state.value.phase != Phase.Loading) {
            Murmur.update { it.copy(phase = Phase.Loading, error = null) }
            worker.execute {
                val t0 = SystemClock.elapsedRealtime()
                try {
                    Transcriber(Engine.load(this)).run {
                        accept(FloatArray(SAMPLE_RATE))
                        finish()
                    }
                    val ms = SystemClock.elapsedRealtime() - t0
                    Murmur.update { it.copy(phase = Phase.Ready, loadMs = ms) }
                } catch (e: Throwable) {
                    Murmur.update { it.copy(phase = Phase.Off, error = "Couldn't load the voice model: ${e.message}") }
                    main.post { stopSelf() }
                }
            }
        }
        // Not sticky: Android won't allow restarting a mic service from the background anyway.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancel()
        instance = null
        Murmur.update { it.copy(phase = Phase.Off, partial = "", levels = emptyList()) }
        worker.shutdown()
        super.onDestroy()
    }

    /** Starts listening; [onText] gets the final transcript on the main thread. */
    fun listen(onText: (String) -> Unit): Boolean {
        if (Murmur.state.value.phase != Phase.Ready) return false
        recording = true
        cancelled = false
        Murmur.update { it.copy(phase = Phase.Listening, partial = "", levels = emptyList(), error = null) }
        worker.execute { runSession(onText) }
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

    private fun runSession(onText: (String) -> Unit) {
        val transcriber = Transcriber(Engine.load(this))
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
            fail(transcriber, "Microphone permission is missing.")
            return
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            fail(transcriber, "The microphone is busy or unavailable.")
            return
        }

        val buf = FloatArray(SAMPLE_RATE / 20) // 50 ms
        val levels = ArrayDeque<Float>()
        var samples = 0L
        audio.startRecording()
        try {
            while (recording) {
                val n = audio.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                val chunk = buf.copyOf(n)
                samples += n
                val text = transcriber.accept(chunk)
                levels.addLast(level(chunk))
                if (levels.size > 36) levels.removeFirst()
                Murmur.update { it.copy(partial = text, levels = levels.toList()) }
            }
        } finally {
            audio.stop()
            audio.release()
        }

        if (cancelled) {
            transcriber.release()
            Murmur.update { it.copy(phase = Phase.Ready, partial = "", levels = emptyList()) }
            return
        }
        Murmur.update { it.copy(phase = Phase.Finishing) }
        val t0 = SystemClock.elapsedRealtime()
        val text = transcriber.finish()
        val latency = SystemClock.elapsedRealtime() - t0
        Murmur.update {
            it.copy(
                phase = Phase.Ready,
                partial = "",
                levels = emptyList(),
                lastLatencyMs = latency,
                lastAudioMs = samples * 1000 / SAMPLE_RATE,
            )
        }
        if (text.isNotBlank()) main.post { onText(text) }
    }

    private fun fail(transcriber: Transcriber, message: String) {
        transcriber.release()
        Murmur.update { it.copy(phase = Phase.Ready, partial = "", levels = emptyList(), error = message) }
    }

    /** Loudness of a chunk mapped to 0..1 over a -60..0 dB range. */
    private fun level(chunk: FloatArray): Float {
        var sum = 0.0
        for (s in chunk) sum += s * s
        val rms = sqrt(sum / chunk.size)
        val db = 20 * log10(rms + 1e-6)
        return ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
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
