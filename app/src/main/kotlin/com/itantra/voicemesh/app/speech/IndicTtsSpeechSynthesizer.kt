package com.itantra.voicemesh.app.speech

import com.itantra.voicemesh.messaging.Message

/**
 * Integration point for AI4Bharat's Indic-TTS (FastPitch + HiFi-GAN, MIT license,
 * github.com/AI4Bharat/Indic-TTS), the intended higher-quality replacement for
 * [EspeakSpeechSynthesizer]'s formant-synthesis voice — it covers all nine Indian
 * languages in this app's current scope. Deliberately NOT implemented yet: weights
 * are downloadable from the repo's GitHub releases as raw PyTorch checkpoints (one
 * FastPitch acoustic model + one HiFi-GAN vocoder per language), with no mobile
 * export documented. Making this real on-device TTS requires, per language:
 *
 * 1. Export both the FastPitch and HiFi-GAN checkpoints to ONNX.
 * 2. Port or bundle the text-normalization/phonemizer frontend Indic-TTS expects
 *    (its own, not eSpeak-NG's — they are not interchangeable).
 * 3. Run both models via `com.microsoft.onnxruntime:onnxruntime-android` in sequence
 *    (text -> FastPitch -> mel spectrogram -> HiFi-GAN -> waveform) and play the
 *    resulting PCM.
 * 4. Benchmark synthesis latency/RTF and RAM on real target phones per spec §12
 *    before claiming this works.
 *
 * [EspeakSpeechSynthesizer] remains the synthesizer actually wired into
 * [com.itantra.voicemesh.app.mesh.MeshSession] today because it is the only one in
 * this codebase that works end to end; this class is intentionally not wired in
 * anywhere yet.
 */
class IndicTtsSpeechSynthesizer : SpeechSynthesizer {
    override fun speak(message: Message) {
        // Not implemented — see class doc. No-op rather than a fake success.
    }
}
