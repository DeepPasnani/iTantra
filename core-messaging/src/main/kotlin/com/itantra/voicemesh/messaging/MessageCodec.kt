package com.itantra.voicemesh.messaging

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Hand-rolled compact binary encoding instead of a general-purpose serialization
 * library: the whole point of this layer (spec §1/§8) is that only a small text
 * message crosses the network, so the fixed header is kept to 27 bytes rather than
 * paying JSON/tag-overhead for every field.
 *
 * Layout: [version:1][msgId:8][sourceNode:4][destNode:4][priority:1][language:1]
 *         [timestamp:8][textLenUtf8:2][textUtf8:N]
 */
object MessageCodec {
    private const val VERSION: Byte = 1
    const val HEADER_SIZE_BYTES = 1 + 8 + 4 + 4 + 1 + 1 + 8 + 2

    fun encode(message: Message): ByteArray {
        val textBytes = message.text.toByteArray(StandardCharsets.UTF_8)
        require(textBytes.size <= UShort.MAX_VALUE.toInt()) { "text too long to encode" }

        val buffer = ByteBuffer.allocate(HEADER_SIZE_BYTES + textBytes.size)
        buffer.put(VERSION)
        buffer.putLong(message.id.value.toLong())
        buffer.putInt(message.sourceNodeId.value.toInt())
        buffer.putInt(message.destinationNodeId.value.toInt())
        buffer.put(message.priority.wireId)
        buffer.put(message.language.wireId)
        buffer.putLong(message.originTimestampMillis)
        buffer.putShort(textBytes.size.toShort())
        buffer.put(textBytes)
        return buffer.array()
    }

    fun decode(bytes: ByteArray): Message {
        val buffer = ByteBuffer.wrap(bytes)
        val version = buffer.get()
        require(version == VERSION) { "unsupported message wire version=$version" }

        val id = MessageId(buffer.long.toULong())
        val sourceNodeId = NodeId(buffer.int.toUInt())
        val destinationNodeId = NodeId(buffer.int.toUInt())
        val priority = Priority.fromWireId(buffer.get())
        val language = Language.fromWireId(buffer.get())
        val timestamp = buffer.long
        val textLen = buffer.short.toInt() and 0xFFFF
        val textBytes = ByteArray(textLen)
        buffer.get(textBytes)
        val text = String(textBytes, StandardCharsets.UTF_8)

        return Message(
            id = id,
            sourceNodeId = sourceNodeId,
            destinationNodeId = destinationNodeId,
            language = language,
            priority = priority,
            originTimestampMillis = timestamp,
            text = text,
        )
    }
}
