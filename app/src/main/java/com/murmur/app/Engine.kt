package com.murmur.app

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

const val SAMPLE_RATE = 16000

/**
 * Owns the streaming speech model (NVIDIA Nemotron Speech Streaming via sherpa-onnx).
 * The model is large (~650 MB), so it is loaded once and kept in memory.
 */
object Engine {
    @Volatile
    private var recognizer: OnlineRecognizer? = null

    val isLoaded get() = recognizer != null

    /** App-private storage; nothing else on the phone can read or change the model. */
    fun modelDir(ctx: Context) = File(ctx.filesDir, "model").apply { mkdirs() }

    private fun find(dir: File, prefix: String): File? =
        dir.listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
            ?.minByOrNull { if ("int8" in it.name) 0 else 1 }

    fun isModelInstalled(ctx: Context): Boolean {
        val dir = modelDir(ctx)
        return listOf("encoder", "decoder", "joiner").all { find(dir, it) != null } &&
            File(dir, "tokens.txt").exists()
    }

    @Synchronized
    fun load(ctx: Context): OnlineRecognizer {
        recognizer?.let { return it }
        val dir = modelDir(ctx)
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 128, dither = 0f),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = find(dir, "encoder")!!.absolutePath,
                    decoder = find(dir, "decoder")!!.absolutePath,
                    joiner = find(dir, "joiner")!!.absolutePath,
                ),
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = 4,
                provider = "cpu",
            ),
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
        return OnlineRecognizer(config = config).also { recognizer = it }
    }
}

/** One dictation: feed audio in, read the running transcript out. */
class Transcriber(private val rec: OnlineRecognizer) {
    private val stream: OnlineStream = rec.createStream("")
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
