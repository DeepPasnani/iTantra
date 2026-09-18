package com.itantra.voicemesh.messaging

/**
 * The ten target languages from the problem statement (Hindi, Gujarati, Marathi,
 * Kannada, Malayalam, Tamil, Telugu, Odia, Bengali, English). [code] is the ISO 639-1
 * tag the STT/TTS engines are keyed by; [wireId] is the single byte carried on the
 * network so language metadata does not cost more than one byte on the wire — new
 * entries must only ever append with a new id, never reuse or renumber an existing
 * one, since that would break wire compatibility with already-deployed nodes.
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
    ENGLISH("en", "English", 10),
    ;

    companion object {
        private val byWireId = entries.associateBy { it.wireId }

        fun fromWireId(id: Byte): Language =
            byWireId[id] ?: throw IllegalArgumentException("Unknown language wireId=$id")
    }
}
