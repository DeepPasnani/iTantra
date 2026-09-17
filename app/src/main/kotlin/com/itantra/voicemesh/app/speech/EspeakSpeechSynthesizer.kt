package com.itantra.voicemesh.app.speech

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.itantra.voicemesh.messaging.Language
import com.itantra.voicemesh.messaging.Message
import com.itantra.voicemesh.messaging.Priority
import java.util.Locale

/**
 * Real offline TTS via the open-source eSpeak NG engine — NOT Android's built-in
 * system TextToSpeech engine, whose actual voice implementation on most phones is
 * Google's proprietary, closed-source TTS even though the Java API is AOSP (spec §3/
 * §9: "No proprietary voice SDKs"). This targets the "eSpeak NG" engine app
 * (package `com.reecedunn.espeak`, built from github.com/espeak-ng/espeak-ng's
 * android/ sources, distributable via F-Droid) by package name, so it works
 * regardless of whatever the OEM set as the phone's default engine — see this
 * session's chat notes for exactly what needs installing.
 *
 * Priority handling (spec §1 "urgent alerts must be announced at highest volume and
 * be non-interruptible"): a NORMAL message never interrupts anything (always queued);
 * an URGENT message jumps ahead of a queued/playing NORMAL message but will not cut
 * off an URGENT message that is already playing.
 */
class EspeakSpeechSynthesizer(context: Context) : SpeechSynthesizer {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val pending = ArrayDeque<Message>()

    @Volatile private var ready = false
    @Volatile private var urgentCurrentlyPlaying = false

    private var engine: TextToSpeech? = null

    init {
        engine = TextToSpeech(
            appContext,
            { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) flushPending() else ready = false
            },
            ESPEAK_ENGINE_PACKAGE,
        )
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId?.startsWith(URGENT_UTTERANCE_PREFIX) == true) urgentCurrentlyPlaying = true
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId?.startsWith(URGENT_UTTERANCE_PREFIX) == true) urgentCurrentlyPlaying = false
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) {
                if (utteranceId?.startsWith(URGENT_UTTERANCE_PREFIX) == true) urgentCurrentlyPlaying = false
            }
        })
    }

    override fun speak(message: Message) {
        if (!ready) {
            pending.addLast(message)
            return
        }
        val engine = this.engine ?: return
        engine.setLanguage(localeFor(message.language))

        val isUrgent = message.priority == Priority.URGENT
        val utteranceId = (if (isUrgent) URGENT_UTTERANCE_PREFIX else NORMAL_UTTERANCE_PREFIX) + message.id

        if (isUrgent) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        }

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, if (isUrgent) 1.0f else 0.8f)
        }

        // Never interrupt an already-playing urgent alert; an urgent alert does jump
        // the queue ahead of whatever normal message is playing/queued.
        val queueMode = if (isUrgent && !urgentCurrentlyPlaying) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(message.text, queueMode, params, utteranceId)
    }

    fun shutdown() {
        engine?.shutdown()
        engine = null
    }

    private fun flushPending() {
        while (pending.isNotEmpty()) speak(pending.removeFirst())
    }

    private fun localeFor(language: Language): Locale = Locale(language.code)

    companion object {
        const val ESPEAK_ENGINE_PACKAGE = "com.reecedunn.espeak"
        private const val URGENT_UTTERANCE_PREFIX = "URGENT:"
        private const val NORMAL_UTTERANCE_PREFIX = "NORMAL:"
    }
}
