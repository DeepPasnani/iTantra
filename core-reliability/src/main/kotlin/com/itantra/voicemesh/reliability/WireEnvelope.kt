package com.itantra.voicemesh.reliability

import com.itantra.voicemesh.messaging.Message
import com.itantra.voicemesh.messaging.MessageCodec
import com.itantra.voicemesh.messaging.MessageId
import java.nio.ByteBuffer

/**
 * The two things that ever travel as an [com.itantra.voicemesh.routing.RoutingPacket.Data]
 * payload: an application [Message], or the [Ack] confirming one was received (spec §7
 * "ACK — confirms successful reception"). Wrapped in one tagged envelope so the routing
 * layer stays ignorant of message/ack semantics — it only forwards opaque bytes.
 */
sealed class WireEnvelope {
    data class MessagePayload(val message: Message) : WireEnvelope()
    data class AckPayload(val messageId: MessageId) : WireEnvelope()
}

object WireEnvelopeCodec {
    private const val TAG_MESSAGE: Byte = 1
    private const val TAG_ACK: Byte = 2

    fun encode(envelope: WireEnvelope): ByteArray = when (envelope) {
        is WireEnvelope.MessagePayload -> {
            val body = MessageCodec.encode(envelope.message)
            ByteBuffer.allocate(1 + body.size).put(TAG_MESSAGE).put(body).array()
        }

        is WireEnvelope.AckPayload -> {
            ByteBuffer.allocate(9).put(TAG_ACK).putLong(envelope.messageId.value.toLong()).array()
        }
    }

    fun decode(bytes: ByteArray): WireEnvelope {
        val buffer = ByteBuffer.wrap(bytes)
        return when (val tag = buffer.get()) {
            TAG_MESSAGE -> {
                val body = ByteArray(bytes.size - 1)
                buffer.get(body)
                WireEnvelope.MessagePayload(MessageCodec.decode(body))
            }

            TAG_ACK -> WireEnvelope.AckPayload(MessageId(buffer.long.toULong()))
            else -> throw IllegalArgumentException("Unknown envelope tag=$tag")
        }
    }
}
