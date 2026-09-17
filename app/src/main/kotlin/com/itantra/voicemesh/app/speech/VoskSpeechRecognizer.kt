package com.itantra.voicemesh.app.speech

import android.content.Context
import com.itantra.voicemesh.messaging.Language
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Real offline STT using Vosk/Kaldi. Only wired up for the languages that actually
 * have a ready-made lightweight Vosk model (spec §11 requires the engine choice to be
 * benchmarked per language, not assumed — this session only gets as far as "wired up
 * and buildable", not "benchmarked"): Hindi, Gujarati, Telugu. The other six languages
 * in scope (Marathi, Kannada, Malayalam, Tamil, Odia, Bengali) have no Vosk model —
 * see [IndicConformerSpeechRecognizer] for the intended eventual replacement covering
 * them. See this session's chat notes for exact model download URLs and where to
 * place the unpacked files under `app/src/main/assets/models/vosk/<code>/`.
 *
 * Each [Language]'s model is loaded once and cached: Kaldi model load is a
 * multi-second operation on a phone and must not happen on every PTT press.
 *
 * Sentence segmentation (spec §3 "STT should react to pauses/stoppages and form
 * sentence-level messages") maps onto Vosk's [RecognitionListener.onResult]: Vosk
 * fires that once per detected pause/endpoint while still listening, which is exactly
 * one sentence-level utterance — not [RecognitionListener.onFinalResult], which only
 * fires once when [stopListening] flushes the stream.
 */
class VoskSpeechRecognizer(private val context: Context) : SpeechRecognizer {
    private val modelCache = mutableMapOf<Language, Model>()
    private var activeService: SpeechService? = null

    override fun startListening(language: Language, onSentenceRecognized: (String) -> Unit) {
        val assetFolder = ASSET_FOLDER_BY_LANGUAGE[language]
        if (assetFolder == null) {
            onSentenceRecognized("[Vosk has no model for ${language.displayName}]")
            return
        }

        val cachedModel = modelCache[language]
        if (cachedModel != null) {
            beginListening(cachedModel, onSentenceRecognized)
            return
        }

        StorageService.unpack(
            context,
            assetFolder,
            "vosk-model-${language.code}",
            { model ->
                modelCache[language] = model
                beginListening(model, onSentenceRecognized)
            },
            { exception -> onSentenceRecognized("[vosk model load failed: ${exception.message}]") },
        )
    }

    override fun stopListening() {
        activeService?.stop()
        activeService = null
    }

    private fun beginListening(model: Model, onSentenceRecognized: (String) -> Unit) {
        val recognizer = Recognizer(model, SAMPLE_RATE_HZ)
        val service = SpeechService(recognizer, SAMPLE_RATE_HZ)
        activeService = service
        service.startListening(object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) = Unit

            override fun onResult(hypothesis: String?) {
                extractText(hypothesis)?.let(onSentenceRecognized)
            }

            override fun onFinalResult(hypothesis: String?) {
                extractText(hypothesis)?.let(onSentenceRecognized)
                activeService = null
            }

            override fun onError(exception: Exception?) {
                onSentenceRecognized("[vosk recognition error: ${exception?.message}]")
            }

            override fun onTimeout() = Unit
        })
    }

    private fun extractText(hypothesisJson: String?): String? {
        val text = hypothesisJson?.let { runCatching { JSONObject(it).optString("text") }.getOrNull() }
        return text?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val SAMPLE_RATE_HZ = 16000.0f

        /**
         * Folder under `app/src/main/assets/` where each language's unpacked Vosk
         * model must live (see the chat response for exact download URLs and the
         * required rename of the extracted top-level folder to just the language
         * code, e.g. `vosk-model-small-hi-0.22/` -> `hi/`).
         */
        private val ASSET_FOLDER_BY_LANGUAGE = mapOf(
            Language.HINDI to "models/vosk/hi",
            Language.GUJARATI to "models/vosk/gu",
            Language.TELUGU to "models/vosk/te",
        )

        val SUPPORTED_LANGUAGES: Set<Language> = ASSET_FOLDER_BY_LANGUAGE.keys
    }
}
