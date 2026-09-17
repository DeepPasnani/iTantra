package com.itantra.voicemesh.app.speech

import com.itantra.voicemesh.messaging.Language

/**
 * Integration point for AI4Bharat's IndicConformer ASR
 * (`ai4bharat/indic-conformer-600m-multilingual`, MIT license, all 22 scheduled
 * Indian languages including the six this app has no Vosk model for: Marathi,
 * Kannada, Malayalam, Tamil, Odia, Bengali). Deliberately NOT implemented yet: AI4Bharat
 * ships this as a HuggingFace `transformers`/`torchaudio` Python model with no
 * documented mobile/ONNX deployment path, so making it real on-device STT is a
 * separate project, not a config change. In order:
 *
 * 1. Export the 600M-parameter model to ONNX (`torch.onnx.export` or `optimum-cli`)
 *    and quantize it — the HF repo's several quantization variants suggest int8
 *    export is at least feasible, but the resulting size/latency on a phone has not
 *    been measured by anyone this project can point to.
 * 2. Reproduce the exact feature extraction (mel-spectrogram/fbank sample rate,
 *    n_mels, hop/win length) the model was trained with — not published in the model
 *    card, so this means reading it out of the training code or the HF
 *    preprocessor config, not guessing.
 * 3. Implement decoding in Kotlin against the ONNX Runtime output tensors — it's a
 *    "Hybrid CTC + RNNT" model, so greedy CTC decoding is the simpler starting point
 *    before attempting RNNT beam search.
 * 4. Run inference via `com.microsoft.onnxruntime:onnxruntime-android` and benchmark
 *    WER, latency, RAM, and Flash footprint on real target phones per spec §11/§12
 *    before claiming this works for any language.
 *
 * Until that's done, this reports itself as unavailable rather than pretending to
 * recognize speech — see [LanguageRoutingSpeechRecognizer], which falls back to this
 * for exactly the six languages above.
 */
class IndicConformerSpeechRecognizer : SpeechRecognizer {
    override fun startListening(language: Language, onSentenceRecognized: (String) -> Unit) {
        onSentenceRecognized(
            "[AI4Bharat IndicConformer not yet integrated for ${language.displayName} " +
                "— ONNX export + decoder still need to be built, see class doc]",
        )
    }

    override fun stopListening() = Unit
}
