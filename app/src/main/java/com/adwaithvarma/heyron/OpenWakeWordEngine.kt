package com.adwaithvarma.heyron

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.nio.FloatBuffer
import java.util.ArrayDeque

/**
 * openWakeWord engine: fully on-device, no API key, no account, no network.
 *
 * Faithful port of the openWakeWord streaming pipeline
 * (openwakeword/utils.py :: AudioFeatures._streaming_features +
 *  openwakeword/model.py :: Model.predict, single-80ms-chunk path):
 *
 *   16 kHz mono int16 PCM
 *     -> accumulate 1280-sample (80 ms) chunks
 *     -> melspectrogram.onnx on the last 1280 + 480 samples
 *        (the +480 = 160*3 samples of STFT overlap, exactly as in
 *        _streaming_melspectrogram), then the fixed transform spec/10 + 2
 *        (the default melspec_transform in _get_melspectrogram)
 *        => 32 mel bins, ~97 frames/sec (hop = 160 samples = 10 ms)
 *     -> embedding_model.onnx (Google speech_embedding style) on the last
 *        76 mel frames => 96-dim embedding, one per 80 ms chunk
 *     -> wake-word .onnx on the last 16 embeddings [1, 16, 96]
 *        => score in [0, 1]; fires when score >= threshold
 *   First 5 predictions are forced to 0 while buffers warm up
 *   (mirrors model.py's "zero predictions for first 5 frames").
 *
 * Recommended threshold is 0.5 (openWakeWord's own default in
 * _get_positive_prediction_frames and their examples). Tunable at runtime.
 */
class OpenWakeWordEngine(
    private val context: Context,
    /** Asset filename of the wake-word model, e.g. "hey_ron.onnx". */
    private val wakeModelAsset: String,
    override val keywordName: String,
    private val threshold: Float = 0.5f,
) : WakeWordEngine {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 1280          // 80 ms @ 16 kHz
        private const val MELSPEC_OVERLAP = 480         // 160*3, STFT overlap (utils.py)
        private const val MEL_BINS = 32
        private const val EMBED_WINDOW_FRAMES = 76     // embedding model window
        private const val EMBED_DIM = 96
        private const val RAW_BUFFER_MAXLEN = 160000   // 10 s of raw audio
        private const val MELSPEC_BUFFER_MAXLEN = 970  // 10 * 97 frames/sec
        private const val FEATURE_BUFFER_MAXLEN = 120  // ~9.6 s of embeddings
        private const val WARMUP_FRAMES = 5

        private const val ASSET_MELSPEC = "melspectrogram.onnx"
        private const val ASSET_EMBEDDING = "embedding_model.onnx"
    }

    private var onWake: (() -> Unit)? = null

    private var env: OrtEnvironment? = null
    private var melspecSession: OrtSession? = null
    private var embeddingSession: OrtSession? = null
    private var wakeSession: OrtSession? = null
    private var melspecInputName = "input"       // refined from the session
    private var embeddingInputName = "input_1"   // refined from the session
    private var wakeInputName = ""               // refined from the session
    private var wakeFeatureFrames = 16           // refined from the session

    private var recorder: AudioRecord? = null
    private var audioThread: Thread? = null
    @Volatile private var running = false

    // Streaming buffers (mirrors AudioFeatures)
    private val rawBuffer = ArrayDeque<Short>()
    private val melspecBuffer = ArrayDeque<FloatArray>()   // each FloatArray(32)
    private val featureBuffer = ArrayDeque<FloatArray>()  // each FloatArray(96)
    private var warmupRemaining = WARMUP_FRAMES

    override fun setOnWakeListener(listener: () -> Unit) {
        onWake = listener
    }

    /** Loads the three ONNX models. Throws on failure — caller shows the error. */
    @Throws(Exception::class)
    fun loadModels() {
        env = OrtEnvironment.getEnvironment()
        val e = env!!

        melspecSession = e.createSession(readAsset(ASSET_MELSPEC))
        embeddingSession = e.createSession(readAsset(ASSET_EMBEDDING))
        wakeSession = e.createSession(readAsset(wakeModelAsset))

        melspecInputName = melspecSession!!.inputNames.first()
        embeddingInputName = embeddingSession!!.inputNames.first()
        wakeInputName = wakeSession!!.inputNames.first()
        // model.py: model_inputs[mdl] = session input shape[1] (feature frames)
        wakeFeatureFrames = (wakeSession!!.inputInfo[wakeInputName]?.info as? ai.onnxruntime.TensorInfo)
            ?.shape?.get(1)?.toInt() ?: 16

        // Mirror utils.py __init__: melspec buffer starts as 76 frames of ones.
        repeat(EMBED_WINDOW_FRAMES) { melspecBuffer.add(FloatArray(MEL_BINS) { 1f }) }
    }

    override fun start() {
        if (running) return
        if (env == null) throw IllegalStateException("loadModels() must be called before start()")

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) throw IllegalStateException("Mic not available (min buffer=$minBuf)")
        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, CHUNK_SAMPLES * 4)
        )
        if (recorder!!.state != AudioRecord.STATE_INITIALIZED) {
            recorder!!.release(); recorder = null
            throw IllegalStateException("AudioRecord failed to initialize")
        }
        recorder!!.startRecording()

        running = true
        audioThread = Thread(::audioLoop, "oww-audio").apply { start() }
    }

    override fun stop() {
        running = false
        try {
            audioThread?.join(1000)
        } catch (_: InterruptedException) { }
        audioThread = null
        try {
            recorder?.stop()
        } catch (_: Exception) { }
        recorder?.release()
        recorder = null
    }

    override fun release() {
        stop()
        melspecSession?.close(); embeddingSession?.close(); wakeSession?.close()
        melspecSession = null; embeddingSession = null; wakeSession = null
        env?.close(); env = null
    }

    // ---------- audio loop ----------

    private fun audioLoop() {
        val chunk = ShortArray(CHUNK_SAMPLES)
        while (running) {
            var read = 0
            while (read < CHUNK_SAMPLES && running) {
                val r = recorder?.read(chunk, read, CHUNK_SAMPLES - read) ?: -1
                if (r < 0) break
                read += r
            }
            if (read != CHUNK_SAMPLES) continue
            try {
                val score = processChunk(chunk)
                if (score >= threshold) onWake?.invoke()
            } catch (t: Throwable) {
                // A single bad chunk must never kill the always-on loop.
                android.util.Log.w("HeyRon", "chunk failed: ${t.message}")
            }
        }
    }

    /**
     * One 80 ms chunk through the pipeline. Returns the wake-word score.
     * Mirrors AudioFeatures.__call__ -> _streaming_features + Model.predict.
     */
    private fun processChunk(chunk: ShortArray): Float {
        // 1. Buffer raw audio (cap 10 s, like raw_data_buffer maxlen=sr*10).
        for (s in chunk) rawBuffer.addLast(s)
        while (rawBuffer.size > RAW_BUFFER_MAXLEN) rawBuffer.pollFirst()

        // 2. Melspectrogram over the last 1280 + 480 samples.
        val window = FloatArray(CHUNK_SAMPLES + MELSPEC_OVERLAP)
        val tail = rawBuffer.toList().takeLast(window.size)
        for (i in tail.indices) window[i] = tail[i].toFloat()
        val melFrames = runMelspec(window)          // each FloatArray(32)
        for (f in melFrames) {
            melspecBuffer.addLast(f)
            while (melspecBuffer.size > MELSPEC_BUFFER_MAXLEN) melspecBuffer.pollFirst()
        }

        // 3. Embedding over the last 76 mel frames -> 96-dim vector.
        val embedWindow = melspecBuffer.toList().takeLast(EMBED_WINDOW_FRAMES)
        if (embedWindow.size < EMBED_WINDOW_FRAMES) return 0f
        val embedding = runEmbedding(embedWindow)  // FloatArray(96)
        featureBuffer.addLast(embedding)
        while (featureBuffer.size > FEATURE_BUFFER_MAXLEN) featureBuffer.pollFirst()

        // 4. Wake-word model over the last N embeddings -> score.
        val feats = featureBuffer.toList().takeLast(wakeFeatureFrames)
        if (feats.size < wakeFeatureFrames) return 0f
        var score = runWakeWord(feats)

        // 5. Warmup: force the first predictions to 0 (model.py does the same).
        if (warmupRemaining > 0) {
            warmupRemaining--
            score = 0f
        }
        return score
    }

    // ---------- ONNX stages ----------

    /** melspectrogram.onnx: [1, N] float32 in -> [1, frames, 32]; then spec/10 + 2. */
    private fun runMelspec(samples: FloatArray): List<FloatArray> {
        val e = env!!; val session = melspecSession!!
        val tensor = OnnxTensor.createTensor(e, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong()))
        tensor.use {
            session.run(mapOf(melspecInputName to tensor)).use { result ->
                val flat = flattenFloat(result[0].value)
                val nFrames = flat.size / MEL_BINS
                return List(nFrames) { i ->
                    FloatArray(MEL_BINS) { j -> flat[i * MEL_BINS + j] / 10f + 2f }
                }
            }
        }
    }

    /** embedding_model.onnx: [1, 76, 32, 1] -> 96-dim embedding. */
    private fun runEmbedding(frames: List<FloatArray>): FloatArray {
        val e = env!!; val session = embeddingSession!!
        val input = FloatArray(1 * EMBED_WINDOW_FRAMES * MEL_BINS * 1)
        for (i in frames.indices)
            for (j in 0 until MEL_BINS)
                input[i * MEL_BINS + j] = frames[i][j]
        val tensor = OnnxTensor.createTensor(
            e, FloatBuffer.wrap(input), longArrayOf(1, EMBED_WINDOW_FRAMES.toLong(), MEL_BINS.toLong(), 1)
        )
        tensor.use {
            session.run(mapOf(embeddingInputName to tensor)).use { result ->
                return flattenFloat(result[0].value)
            }
        }
    }

    /** wake-word .onnx: [1, 16, 96] -> single score. */
    private fun runWakeWord(feats: List<FloatArray>): Float {
        val e = env!!; val session = wakeSession!!
        val input = FloatArray(1 * wakeFeatureFrames * EMBED_DIM)
        for (i in feats.indices)
            for (j in 0 until EMBED_DIM)
                input[i * EMBED_DIM + j] = feats[i][j]
        val tensor = OnnxTensor.createTensor(
            e, FloatBuffer.wrap(input), longArrayOf(1, wakeFeatureFrames.toLong(), EMBED_DIM.toLong())
        )
        tensor.use {
            session.run(mapOf(wakeInputName to tensor)).use { result ->
                return flattenFloat(result[0].value)[0]
            }
        }
    }

    // ---------- helpers ----------

    private fun readAsset(name: String): ByteArray =
        context.assets.open(name).use { it.readBytes() }

    /** Recursively flattens ORT's nested-array tensor values to a FloatArray. */
    private fun flattenFloat(value: Any?): FloatArray {
        val out = ArrayList<Float>(128)
        fun rec(v: Any?) {
            when (v) {
                is FloatArray -> for (f in v) out.add(f)
                is DoubleArray -> for (d in v) out.add(d.toFloat())
                is Array<*> -> for (x in v) rec(x)
                is Float -> out.add(v)
                is Double -> out.add(v.toFloat())
                is Number -> out.add(v.toFloat())
                null -> throw IllegalStateException("Null tensor value")
                else -> throw IllegalStateException("Unexpected tensor value type: ${v.javaClass}")
            }
        }
        rec(value)
        return out.toFloatArray()
    }
}
