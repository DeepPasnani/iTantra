package com.itantra.voicemesh.app.speech

import com.itantra.voicemesh.messaging.Language
import com.itantra.voicemesh.messaging.Message

/**
 * Offline speech-to-text: listens, reacts to pauses, and hands back one sentence-level
 * utterance per call (spec §3 "STT should react to pauses/stoppages and form
 * sentence-level messages"). Deliberately just an interface for now — this session's
 * scope is the network/reliability core (see spec §15 build order); a real engine
 * (e.g. Vosk) still needs the benchmark pass from spec §11/§12 across all ten
 * languages before it can be claimed to work, so no accuracy/latency numbers should
 * be attached to whatever implementation eventually plugs in here.
 */
interface SpeechRecognizer {
    fun startListening(language: Language, onSentenceRecognized: (String) -> Unit)
    fun stopListening()
}

/** Fixed canned output, clearly not real recognition — for exercising the network path end to end while no engine is wired up. */
class StubSpeechRecognizer : SpeechRecognizer {
    override fun startListening(language: Language, onSentenceRecognized: (String) -> Unit) {
        onSentenceRecognized("[stub STT — ${language.displayName} recognizer not yet integrated]")
    }

    override fun stopListening() = Unit
}

/**
 * Offline text-to-speech. Priority matters here per spec §1: an URGENT message must
 * play at max volume and be non-interruptible, a NORMAL one should not preempt
 * whatever is already playing. Same caveat as [SpeechRecognizer]: a stub only, and the
 * eventual real engine must be an open-source one running fully on-device — notably
 * NOT the Android system TextToSpeech API on most phones, since its actual voice
 * engine is typically Google's proprietary closed-source TTS even though the Java API
 * itself is part of AOSP (spec §3/§9 constraint: "No proprietary voice SDKs").
 */
interface SpeechSynthesizer {
    fun speak(message: Message)
}

class StubSpeechSynthesizer(private val onSpeak: (Message) -> Unit) : SpeechSynthesizer {
    override fun speak(message: Message) {
        onSpeak(message)
    }
}

/**
 * Sends a language to [VoskSpeechRecognizer] when a real model is wired up for it
 * (Hindi, Gujarati, Telugu, English), otherwise falls back to
 * [IndicConformerSpeechRecognizer] for the remaining six (Marathi, Kannada, Malayalam,
 * Tamil, Odia, Bengali) — wired up and buildable, but not yet benchmarked on real
 * hardware (spec §16: don't claim STT works for a language beyond what's been verified).
 */
class LanguageRoutingSpeechRecognizer(
    private val vosk: VoskSpeechRecognizer,
    private val fallback: SpeechRecognizer,
) : SpeechRecognizer {
    private var lastUsedWasVosk = false

    override fun startListening(language: Language, onSentenceRecognized: (String) -> Unit) {
        if (language in VoskSpeechRecognizer.SUPPORTED_LANGUAGES) {
            lastUsedWasVosk = true
            vosk.startListening(language, onSentenceRecognized)
        } else {
            lastUsedWasVosk = false
            fallback.startListening(language, onSentenceRecognized)
        }
    }

    override fun stopListening() {
        if (lastUsedWasVosk) vosk.stopListening() else fallback.stopListening()
    }
}
