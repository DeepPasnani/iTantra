package com.itantra.voicemesh.app.persistence

import com.itantra.voicemesh.messaging.Language
import com.itantra.voicemesh.messaging.Message
import com.itantra.voicemesh.messaging.MessageId
import com.itantra.voicemesh.messaging.NodeId
import com.itantra.voicemesh.messaging.Priority
import com.itantra.voicemesh.reliability.OutboxEntry
import com.itantra.voicemesh.reliability.OutboxStore

/** Adapts [OutboxDao] to :core-reliability's [OutboxStore] contract. */
class RoomOutboxStore(private val dao: OutboxDao) : OutboxStore {
    override fun upsert(entry: OutboxEntry) {
        dao.upsert(entry.toEntity())
    }

    override fun remove(messageId: MessageId) {
        dao.deleteById(messageId.value.toLong())
    }

    override fun get(messageId: MessageId): OutboxEntry? =
        dao.getById(messageId.value.toLong())?.toDomain()

    override fun all(): List<OutboxEntry> = dao.getAll().map { it.toDomain() }
}

private fun OutboxEntry.toEntity(): OutboxEntity = OutboxEntity(
    messageId = message.id.value.toLong(),
    sourceNodeId = message.sourceNodeId.value.toInt(),
    destinationNodeId = message.destinationNodeId.value.toInt(),
    languageWireId = message.language.wireId,
    priorityWireId = message.priority.wireId,
    originTimestampMillis = message.originTimestampMillis,
    text = message.text,
    enqueuedAtMillis = enqueuedAtMillis,
    lastSentAtMillis = lastSentAtMillis,
    attempts = attempts,
)

private fun OutboxEntity.toDomain(): OutboxEntry = OutboxEntry(
    message = Message(
        id = MessageId(messageId.toULong()),
        sourceNodeId = NodeId(sourceNodeId.toUInt()),
        destinationNodeId = NodeId(destinationNodeId.toUInt()),
        language = Language.fromWireId(languageWireId),
        priority = Priority.fromWireId(priorityWireId),
        originTimestampMillis = originTimestampMillis,
        text = text,
    ),
    enqueuedAtMillis = enqueuedAtMillis,
    lastSentAtMillis = lastSentAtMillis,
    attempts = attempts,
)
