package com.itantra.voicemesh.messaging

/**
 * The nine target Indian languages (English dropped from scope: AI4Bharat's ASR/TTS
 * models — this project's chosen STT/TTS source — don't cover it, see chat notes).
 * [code] is the ISO 639-1 tag the STT/TTS engines are keyed by; [wireId] is the single
 * byte carried on the network so language metadata does not cost more than one byte
 * on the wire.
 */
enum class Language(val code: String, val displayName: String, val wireId: Byte) {
    HINDI("hi", "Hindi", 1),
    GUJARATI("gu", "Gujarati", 2),
    MARATHI("mr", "Marathi", 3),
    KANNADA("kn", "Kannada", 4),
    MALAYALAM("ml", "Malayalam", 5),
    TAMIL("ta", "Tamil", 6),
    TELUGU("te", "Telugu", 7),
    ODIA("or", "Odia", 8),
    BENGALI("bn", "Bengali", 9),
    ;

    companion object {
        private val byWireId = entries.associateBy { it.wireId }

        fun fromWireId(id: Byte): Language =
            byWireId[id] ?: throw IllegalArgumentException("Unknown language wireId=$id")
    }
}
