package com.itantra.voicemesh.reliability

import com.itantra.voicemesh.messaging.Message
import com.itantra.voicemesh.messaging.MessageId
import com.itantra.voicemesh.messaging.NodeId
import com.itantra.voicemesh.routing.AodvRouter
import com.itantra.voicemesh.routing.SeenCache

/**
 * Sits on top of [AodvRouter] and implements the spec §7 reliability behaviors that
 * are about *messages*, not packets: ACK, retry with a bounded attempt count,
 * duplicate suppression (so the same message is never spoken twice by TTS), and a
 * store-and-forward outbox. Packet-level concerns (route discovery, TTL/hop limit,
 * dedup of route requests) already live in [AodvRouter]; this layer only ever calls
 * [AodvRouter.sendData] with an opaque, envelope-wrapped payload.
 *
 * Like [AodvRouter], time-based behavior is driven by explicit [tick] calls rather
 * than an internal scheduler, keeping this deterministic and unit-testable.
 */
class ReliabilityLayer(
    private val router: AodvRouter,
    private val outbox: OutboxStore = InMemoryOutboxStore(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val ackTimeoutMillis: Long = 4_000,
    private val maxAttempts: Int = 5,
    private val maxQueueLifetimeMillis: Long = 5 * 60_000,
    private val onMessageReceived: (Message) -> Unit = {},
    private val onDeliveryOutcome: (MessageId, DeliveryOutcome) -> Unit = { _, _ -> },
) {
    private val dedup = SeenCache<Pair<NodeId, MessageId>>(maxEntries = 1024, entryLifetimeMillis = 60_000)

    init {
        router.attachDataHandler(::handleIncomingEnvelope)
    }

    /** Enqueues [message] and attempts to send it now; the outbox retains it until ACKed, given up on, or expired. */
    fun sendMessage(message: Message) {
        val now = clock()
        outbox.upsert(OutboxEntry(message, enqueuedAtMillis = now, lastSentAtMillis = now, attempts = 1))
        router.sendData(message.destinationNodeId, WireEnvelopeCodec.encode(WireEnvelope.MessagePayload(message)))
    }

    /** Call periodically (e.g. once a second) to drive retries, give-ups, and queue expiry. */
    fun tick() {
        val now = clock()
        for (entry in outbox.all()) {
            val age = now - entry.enqueuedAtMillis
            if (age >= maxQueueLifetimeMillis) {
                outbox.remove(entry.message.id)
                onDeliveryOutcome(entry.message.id, DeliveryOutcome.EXPIRED)
                continue
            }
            val waitingForAck = now - entry.lastSentAtMillis >= ackTimeoutMillis
            if (!waitingForAck) continue

            if (entry.attempts >= maxAttempts) {
                outbox.remove(entry.message.id)
                onDeliveryOutcome(entry.message.id, DeliveryOutcome.FAILED_MAX_RETRIES)
                continue
            }

            outbox.upsert(entry.copy(lastSentAtMillis = now, attempts = entry.attempts + 1))
            router.sendData(
                entry.message.destinationNodeId,
                WireEnvelopeCodec.encode(WireEnvelope.MessagePayload(entry.message)),
            )
            onDeliveryOutcome(entry.message.id, DeliveryOutcome.RETRYING)
        }
    }

    private fun handleIncomingEnvelope(@Suppress("UNUSED_PARAMETER") fromNodeId: NodeId, payload: ByteArray) {
        when (val envelope = WireEnvelopeCodec.decode(payload)) {
            is WireEnvelope.MessagePayload -> handleIncomingMessage(envelope.message)
            is WireEnvelope.AckPayload -> handleIncomingAck(envelope.messageId)
        }
    }

    private fun handleIncomingMessage(message: Message) {
        val isNew = dedup.observeAndCheckIfNew(message.dedupKey, clock())

        // Ack every receipt, even a duplicate: the sender's earlier ACK may have been
        // lost, and re-acking costs one small packet versus a needless retry storm.
        router.sendData(
            message.sourceNodeId,
            WireEnvelopeCodec.encode(WireEnvelope.AckPayload(message.id)),
        )

        if (isNew) {
            onMessageReceived(message)
        }
    }

    private fun handleIncomingAck(messageId: MessageId) {
        if (outbox.get(messageId) != null) {
            outbox.remove(messageId)
            onDeliveryOutcome(messageId, DeliveryOutcome.ACKED)
        }
    }
}
