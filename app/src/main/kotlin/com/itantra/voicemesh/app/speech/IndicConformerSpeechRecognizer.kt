package com.itantra.voicemesh.app.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.itantra.voicemesh.messaging.Language
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * Real offline STT for the six languages Vosk has no model for (Marathi, Kannada,
 * Malayalam, Tamil, Odia, Bengali), using AI4Bharat's per-language IndicConformer CTC
 * checkpoints (MIT license, `ai4bharat/indicconformer_stt_<code>_hybrid_ctc_rnnt_large`)
 * exported to ONNX and int8-quantized by OpenVoiceOS
 * (`huggingface.co/OpenVoiceOS/ai4bharat-indicconformer-<code>-onnx`, also MIT). See
 * [NemoLogMelFeatureExtractor] for the feature pipeline and [CtcGreedyDecoder] for how
 * the model's frame-level output becomes text — both are hand-ported from
 * github.com/istupakov/onnx-asr's Python reference and have not been checked against
 * that reference on real audio from this environment.
 *
 * Unlike Vosk, ONNX Runtime gives no built-in endpointing, so a fixed-threshold energy
 * VAD does it here: once a 20ms chunk's RMS crosses [SPEECH_RMS_THRESHOLD] an utterance
 * starts accumulating, and [SILENCE_HANGOVER_MS] of below-threshold audio ends it and
 * triggers one inference pass over the buffered audio. That threshold is a rough
 * constant, not calibrated against a real microphone or real ambient noise — along with
 * the feature-pipeline correctness above, it is the first thing to check once this runs
 * on an actual phone. No WER/latency numbers should be attached to this until that
 * happens, per this project's claims discipline.
 */
class IndicConformerSpeechRecognizer(private val context: Context) : SpeechRecognizer {
    private data class LoadedModel(val session: OrtSession, val vocab: Map<Int, String>, val blankIndex: Int)

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val modelCache = mutableMapOf<Language, LoadedModel>()
    private val featureExtractor = NemoLogMelFeatureExtractor()

    @Volatile private var recording = false
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    override fun startListening(language: Language, onSentenceRecognized: (String) -> Unit) {
        val assetFolder = ASSET_FOLDER_BY_LANGUAGE[language]
        if (assetFolder == null) {
            onSentenceRecognized("[IndicConformer has no model for ${language.displayName}]")
            return
        }

        val model = try {
            modelCache.getOrPut(language) { loadModel(assetFolder) }
        } catch (e: Exception) {
            onSentenceRecognized("[IndicConformer model load failed for ${language.displayName}: ${e.message}]")
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) {
            onSentenceRecognized("[IndicConformer: AudioRecord unsupported on this device]")
            return
        }

        @Suppress("MissingPermission")
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 4,
        )
        audioRecord = record
        recording = true
        record.startRecording()

        captureThread = Thread { runCaptureLoop(record, model, onSentenceRecognized) }.also { it.start() }
    }

    override fun stopListening() {
        recording = false
        captureThread?.join(1000)
        captureThread = null
        audioRecord?.let {
            runCatching { it.stop() }
            it.release()
        }
        audioRecord = null
    }

    private fun runCaptureLoop(record: AudioRecord, model: LoadedModel, onSentenceRecognized: (String) -> Unit) {
        val chunkSamples = SAMPLE_RATE_HZ / 50 // 20ms
        val chunk = ShortArray(chunkSamples)
        val speechBuffer = mutableListOf<Short>()
        var silenceRunMs = 0
        var speaking = false

        while (recording) {
            val read = record.read(chunk, 0, chunk.size)
            if (read <= 0) continue

            var sumSquares = 0.0
            for (i in 0 until read) sumSquares += chunk[i].toDouble() * chunk[i].toDouble()
            val rms = sqrt(sumSquares / read)

            when {
                rms > SPEECH_RMS_THRESHOLD -> {
                    speaking = true
                    silenceRunMs = 0
                    for (i in 0 until read) speechBuffer.add(chunk[i])
                }
                speaking -> {
                    silenceRunMs += CHUNK_MS
                    for (i in 0 until read) speechBuffer.add(chunk[i])
                    if (silenceRunMs >= SILENCE_HANGOVER_MS) {
                        finalizeUtterance(speechBuffer, model, onSentenceRecognized)
                        speechBuffer.clear()
                        speaking = false
                        silenceRunMs = 0
                    }
                }
            }
        }

        if (speaking) finalizeUtterance(speechBuffer, model, onSentenceRecognized)
    }

    private fun finalizeUtterance(samples: List<Short>, model: LoadedModel, onSentenceRecognized: (String) -> Unit) {
        if (samples.size < SAMPLE_RATE_HZ / 4) return // shorter than 250ms: noise, not an utterance
        val waveform = FloatArray(samples.size) { samples[it] / 32768.0f }
        val text = runCatching { recognize(waveform, model) }
            .getOrElse { "[IndicConformer inference error: ${it.message}]" }
        if (text.isNotBlank()) onSentenceRecognized(text)
    }

    private fun recognize(waveform: FloatArray, model: LoadedModel): String {
        val (features, numFrames) = featureExtractor.extract(waveform)
        if (numFrames <= 0) return ""

        val flat = FloatArray(features.size * numFrames)
        for (m in features.indices) System.arraycopy(features[m], 0, flat, m * numFrames, numFrames)

        OnnxTensor.createTensor(environment, FloatBuffer.wrap(flat), longArrayOf(1, features.size.toLong(), numFrames.toLong())).use { audio ->
            OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(numFrames.toLong())), longArrayOf(1)).use { length ->
                model.session.run(mapOf("audio_signal" to audio, "length" to length)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val logProbs = (result.get("logprobs").get().value as Array<Array<FloatArray>>)[0]
                    val encoderOutLen = minOf((numFrames - 1) / SUBSAMPLING_FACTOR + 1, logProbs.size)
                    return CtcGreedyDecoder.decode(logProbs, encoderOutLen, model.blankIndex, model.vocab)
                }
            }
        }
    }

    private fun loadModel(assetFolder: String): LoadedModel {
        val languageCode = assetFolder.substringAfterLast('/')
        val modelFile = copyAssetToFilesDir(
            "$assetFolder/model.int8.onnx",
            "indic-conformer/$languageCode/model.int8.onnx",
        )
        val session = environment.createSession(modelFile.absolutePath, OrtSession.SessionOptions())

        val vocab = mutableMapOf<Int, String>()
        var blankIndex = -1
        context.assets.open("$assetFolder/vocab.txt").bufferedReader().useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val separator = trimmed.lastIndexOf(' ')
                val token = trimmed.substring(0, separator)
                val id = trimmed.substring(separator + 1).toInt()
                vocab[id] = token.replace(WORD_BOUNDARY_MARKER, " ")
                if (token == "<blk>") blankIndex = id
            }
        }
        check(blankIndex >= 0) { "no <blk> token found in $assetFolder/vocab.txt" }
        return LoadedModel(session, vocab, blankIndex)
    }

    /** Copies an asset into internal storage once (size check only, not a checksum) so ONNX Runtime can mmap it by path. */
    private fun copyAssetToFilesDir(assetPath: String, relativeDestPath: String): File {
        val dest = File(context.filesDir, relativeDestPath)
        if (!dest.exists() || dest.length() == 0L) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
        }
        return dest
    }

    companion object {
        private const val SAMPLE_RATE_HZ = 16_000
        private const val CHUNK_MS = 20
        private const val SILENCE_HANGOVER_MS = 700
        private const val SPEECH_RMS_THRESHOLD = 500.0 // uncalibrated, see class doc
        private const val SUBSAMPLING_FACTOR = 4
        private const val WORD_BOUNDARY_MARKER = "▁"

        // All six commented out for a smaller demo-video build — their ONNX models were
        // moved to `models-disabled/indic-conformer/<code>` (not deleted). To restore a
        // language: move its folder back to `app/src/main/assets/models/indic-conformer/`
        // and uncomment its line below.
        private val ASSET_FOLDER_BY_LANGUAGE = mapOf<Language, String>(
            // Language.MARATHI to "models/indic-conformer/mr",
            // Language.KANNADA to "models/indic-conformer/kn",
            // Language.MALAYALAM to "models/indic-conformer/ml",
            // Language.TAMIL to "models/indic-conformer/ta",
            // Language.ODIA to "models/indic-conformer/or",
            // Language.BENGALI to "models/indic-conformer/bn",
        )

        val SUPPORTED_LANGUAGES: Set<Language> = ASSET_FOLDER_BY_LANGUAGE.keys
    }
}
