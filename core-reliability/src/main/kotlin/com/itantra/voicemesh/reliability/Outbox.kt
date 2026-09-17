package com.itantra.voicemesh.reliability

import com.itantra.voicemesh.messaging.Message
import com.itantra.voicemesh.messaging.MessageId

enum class DeliveryOutcome {
    ACKED,
    RETRYING,
    FAILED_MAX_RETRIES,
    EXPIRED,
}

data class OutboxEntry(
    val message: Message,
    val enqueuedAtMillis: Long,
    val lastSentAtMillis: Long,
    val attempts: Int,
)

/**
 * Store-and-forward queue for outbound messages awaiting an ACK (spec §7 "local
 * outbox/store-and-forward"). This is the persistence *contract*, not the
 * persistence: an in-memory implementation is provided for tests, and the app is
 * expected to back it with the Room database from spec §10 so queued messages survive
 * a restart or a temporary network partition — that Android-specific implementation
 * is out of scope for this module.
 */
interface OutboxStore {
    fun upsert(entry: OutboxEntry)
    fun remove(messageId: MessageId)
    fun get(messageId: MessageId): OutboxEntry?
    fun all(): List<OutboxEntry>
}

class InMemoryOutboxStore : OutboxStore {
    private val entries = mutableMapOf<MessageId, OutboxEntry>()

    @Synchronized
    override fun upsert(entry: OutboxEntry) {
        entries[entry.message.id] = entry
    }

    @Synchronized
    override fun remove(messageId: MessageId) {
        entries.remove(messageId)
    }

    @Synchronized
    override fun get(messageId: MessageId): OutboxEntry? = entries[messageId]

    @Synchronized
    override fun all(): List<OutboxEntry> = entries.values.toList()
}
