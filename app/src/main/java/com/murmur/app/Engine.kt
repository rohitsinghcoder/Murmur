package com.murmur.app

import android.content.Context
import android.os.Build
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.QnnConfig
import java.io.File

const val SAMPLE_RATE = 16000

/** Where the speech model runs. */
enum class Backend(val label: String) {
    Npu("NPU"),
    Cpu("CPU"),
}

/** Languages the NPU model (Nemotron 3.5, multilingual) can be told to expect. */
enum class Language(val code: String, val label: String) {
    English("en", "English"),
    Hindi("hi", "Hindi"),
    Auto("auto", "Auto-detect"),
}

/**
 * Owns the streaming speech model (NVIDIA Nemotron via sherpa-onnx), loaded once and kept in
 * memory. Two installs are supported:
 * - `files/model-npu`: Nemotron 3.5 compiled for this phone's Snapdragon NPU (Qualcomm QNN
 *   context binaries). Fast and light on the CPU, but only runs on the exact chip it was built
 *   for, named in `soc.txt`.
 * - `files/model`: Nemotron Speech Streaming EN as ONNX on the CPU. Works on any phone and is
 *   the fallback when the NPU can't be used.
 */
object Engine {
    @Volatile
    private var recognizer: OnlineRecognizer? = null

    /** The backend of the loaded model, or null before it loads. */
    @Volatile
    var backend: Backend? = null
        private set

    val isLoaded get() = recognizer != null

    /** App-private storage; nothing else on the phone can read or change the model. */
    fun modelDir(ctx: Context) = File(ctx.filesDir, "model").apply { mkdirs() }

    fun npuDir(ctx: Context) = File(ctx.filesDir, "model-npu")

    private fun find(dir: File, prefix: String): File? =
        dir.listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
            ?.minByOrNull { if ("int8" in it.name) 0 else 1 }

    fun isCpuInstalled(ctx: Context): Boolean {
        val dir = modelDir(ctx)
        return listOf("encoder", "decoder", "joiner").all { find(dir, it) != null } &&
            File(dir, "tokens.txt").exists()
    }

    /** The NPU model is installed and was compiled for this phone's chip. */
    fun isNpuInstalled(ctx: Context): Boolean {
        val dir = npuDir(ctx)
        val files = listOf("encoder.bin", "decoder.bin", "joiner.bin", "tokens.txt")
        if (!files.all { File(dir, it).exists() }) return false
        val soc = File(dir, "soc.txt").takeIf { it.exists() }?.readText()?.trim()
        return Build.VERSION.SDK_INT >= 31 && soc.equals(Build.SOC_MODEL, ignoreCase = true)
    }

    fun isModelInstalled(ctx: Context) = isNpuInstalled(ctx) || isCpuInstalled(ctx)

    /** The backend [load] will use. */
    fun preferredBackend(ctx: Context): Backend? = when {
        isNpuInstalled(ctx) && Prefs.useNpu(ctx) && !Prefs.npuFailed(ctx) -> Backend.Npu
        isCpuInstalled(ctx) -> Backend.Cpu
        isNpuInstalled(ctx) -> Backend.Npu
        else -> null
    }

    @Synchronized
    fun load(ctx: Context): OnlineRecognizer {
        recognizer?.let { return it }
        if (Prefs.npuLoading(ctx)) {
            // The last NPU load never finished: the process died inside it.
            Prefs.setNpuLoading(ctx, false)
            Prefs.setNpuFailed(ctx, true)
        }
        var use = preferredBackend(ctx) ?: error("No voice model installed")
        val rec = if (use == Backend.Npu) {
            try {
                loadNpu(ctx)
            } catch (e: Exception) {
                Prefs.setNpuLoading(ctx, false)
                Prefs.setNpuFailed(ctx, true)
                if (!isCpuInstalled(ctx)) throw e
                use = Backend.Cpu
                null
            }
        } else null
        return (rec ?: OnlineRecognizer(config = config(cpuModel(ctx)))).also {
            recognizer = it
            backend = use
        }
    }

    /** Loads the model if needed and starts a dictation in the chosen language. */
    fun transcriber(ctx: Context): Transcriber {
        val rec = load(ctx)
        // Only the multilingual NPU model takes a language; the CPU model is English-only.
        return Transcriber(rec, Prefs.language(ctx).code.takeIf { backend == Backend.Npu })
    }

    /** Frees the model, so the next [load] picks the backend again. */
    @Synchronized
    fun unload() {
        recognizer?.release()
        recognizer = null
        backend = null
    }

    private fun loadNpu(ctx: Context): OnlineRecognizer {
        // A broken NPU setup aborts the whole process inside native code rather than throwing,
        // so remember that a load is in progress: if it never finishes, the next start uses
        // the CPU instead of crashing again.
        Prefs.setNpuLoading(ctx, true)
        // The NPU half of Qualcomm's runtime (libQnnHtpV81Skel.so) is loaded by the DSP, which
        // looks for it on ADSP_LIBRARY_PATH; it ships in the APK's native library folder.
        OnlineRecognizer.prependAdspLibraryPath(ctx.applicationInfo.nativeLibraryDir)
        val dir = npuDir(ctx)
        val model = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                qnnConfig = QnnConfig(
                    backendLib = "libQnnHtp.so",
                    systemLib = "libQnnSystem.so",
                    contextBinary = listOf("encoder.bin", "decoder.bin", "joiner.bin")
                        .joinToString(",") { File(dir, it).absolutePath },
                ),
            ),
            tokens = File(dir, "tokens.txt").absolutePath,
            numThreads = 1,
            provider = "qnn",
            modelType = "nemo_transducer",
        )
        return OnlineRecognizer(config = config(model)).also { Prefs.setNpuLoading(ctx, false) }
    }

    private fun cpuModel(ctx: Context): OnlineModelConfig {
        val dir = modelDir(ctx)
        return OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = find(dir, "encoder")!!.absolutePath,
                decoder = find(dir, "decoder")!!.absolutePath,
                joiner = find(dir, "joiner")!!.absolutePath,
            ),
            tokens = File(dir, "tokens.txt").absolutePath,
            numThreads = 4,
            provider = "cpu",
        )
    }

    private fun config(model: OnlineModelConfig) = OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 128, dither = 0f),
        modelConfig = model,
        // Split long dictation into segments at natural pauses, which keeps each
        // decode short. The user still decides when dictation ends.
        endpointConfig = EndpointConfig(
            rule1 = EndpointRule(false, 2.4f, 0f),
            rule2 = EndpointRule(true, 1.2f, 0f),
            rule3 = EndpointRule(false, 0f, 30f),
        ),
        enableEndpoint = true,
        decodingMethod = "greedy_search",
    )
}

/** One dictation: feed audio in, read the running transcript out. */
class Transcriber(private val rec: OnlineRecognizer, language: String? = null) {
    private val stream: OnlineStream = rec.createStream("").apply {
        if (language != null) setOption("language", language)
    }
    private val committed = StringBuilder()

    private fun join(tail: String) =
        listOf(committed.toString(), tail).filter { it.isNotBlank() }.joinToString(" ")

    /** Adds audio and returns the transcript so far. */
    fun accept(samples: FloatArray): String {
        stream.acceptWaveform(samples, SAMPLE_RATE)
        while (rec.isReady(stream)) rec.decode(stream)
        val partial = rec.getResult(stream).text.trim()
        if (rec.isEndpoint(stream)) {
            if (partial.isNotEmpty()) {
                if (committed.isNotEmpty()) committed.append(' ')
                committed.append(partial)
            }
            rec.reset(stream)
            return committed.toString()
        }
        return join(partial)
    }

    /** Flushes the last chunk and returns the final transcript. */
    fun finish(): String {
        // Silence padding lets the streaming encoder emit the final words.
        stream.acceptWaveform(FloatArray(SAMPLE_RATE * 8 / 10), SAMPLE_RATE)
        stream.inputFinished()
        while (rec.isReady(stream)) rec.decode(stream)
        val text = join(rec.getResult(stream).text.trim())
        stream.release()
        return text
    }

    fun release() = stream.release()
}
