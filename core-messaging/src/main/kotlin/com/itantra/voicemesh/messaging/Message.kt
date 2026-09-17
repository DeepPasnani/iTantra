package com.itantra.voicemesh.messaging

/**
 * Priority is the one piece of metadata that lets an urgent alert be distinguished
 * from a normal conversational message (spec: non-interruptible, max-volume playback
 * for URGENT; also usable later to prioritize forwarding/queueing order).
 */
enum class Priority(val wireId: Byte) {
    NORMAL(0),
    URGENT(1),
    ;

    companion object {
        fun fromWireId(id: Byte): Priority =
            entries.firstOrNull { it.wireId == id }
                ?: throw IllegalArgumentException("Unknown priority wireId=$id")
    }
}

/**
 * The compact, sentence-level unit produced by on-device STT and consumed by on-device
 * TTS. This — not audio — is what travels the network (spec §1/§4).
 */
data class Message(
    val id: MessageId,
    val sourceNodeId: NodeId,
    val destinationNodeId: NodeId,
    val language: Language,
    val priority: Priority,
    val originTimestampMillis: Long,
    val text: String,
) {
    /** Key used for dedup/ACK matching: an id is only guaranteed unique per source node. */
    val dedupKey: Pair<NodeId, MessageId> get() = sourceNodeId to id

    companion object {
        const val MAX_TEXT_LENGTH_CHARS = 1024

        fun create(
            idGenerator: MessageIdGenerator,
            sourceNodeId: NodeId,
            destinationNodeId: NodeId,
            language: Language,
            priority: Priority,
            text: String,
            nowMillis: Long = System.currentTimeMillis(),
        ): Message = Message(
            id = idGenerator.next(),
            sourceNodeId = sourceNodeId,
            destinationNodeId = destinationNodeId,
            language = language,
            priority = priority,
            originTimestampMillis = nowMillis,
            text = TextNormalizer.normalize(text),
        )
    }
}

/**
 * Normalizes raw STT output into the form that gets sent on the wire: trims, collapses
 * internal whitespace introduced by recognizer pauses, and bounds length so one runaway
 * utterance cannot blow the "hundreds of bytes" payload target (spec §8).
 */
object TextNormalizer {
    fun normalize(raw: String): String {
        val collapsed = raw.trim().replace(Regex("\\s+"), " ")
        return if (collapsed.length > Message.MAX_TEXT_LENGTH_CHARS) {
            collapsed.take(Message.MAX_TEXT_LENGTH_CHARS)
        } else {
            collapsed
        }
    }
}
