package com.itantra.voicemesh.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageCodecTest {
    @Test
    fun `round trips a normal message`() {
        val message = Message.create(
            idGenerator = MessageIdGenerator(startAt = 1_700_000_000_000L),
            sourceNodeId = NodeId(11u),
            destinationNodeId = NodeId(22u),
            language = Language.HINDI,
            priority = Priority.NORMAL,
            text = "Bridge ahead is washed out, use the north path",
        )

        val decoded = MessageCodec.decode(MessageCodec.encode(message))

        assertEquals(message, decoded)
    }

    @Test
    fun `round trips broadcast destination and urgent priority`() {
        val message = Message.create(
            idGenerator = MessageIdGenerator(),
            sourceNodeId = NodeId(5u),
            destinationNodeId = NodeId.BROADCAST,
            language = Language.TAMIL,
            priority = Priority.URGENT,
            text = "Evacuate now",
        )

        val decoded = MessageCodec.decode(MessageCodec.encode(message))

        assertEquals(NodeId.BROADCAST, decoded.destinationNodeId)
        assertEquals(Priority.URGENT, decoded.priority)
    }

    @Test
    fun `encoded payload stays compact for a long sentence`() {
        val longText = "word ".repeat(150).trim() // ~150 words, within the 100-200 word target
        val message = Message.create(
            idGenerator = MessageIdGenerator(),
            sourceNodeId = NodeId(1u),
            destinationNodeId = NodeId(2u),
            language = Language.MARATHI,
            priority = Priority.NORMAL,
            text = longText,
        )

        val encoded = MessageCodec.encode(message)

        // Spec §8: a 100-200 word message plus metadata should stay on the order of
        // hundreds of bytes, not kilobytes.
        assertTrue(encoded.size < 1024, "encoded size was ${encoded.size} bytes")
        assertEquals(message, MessageCodec.decode(encoded))
    }

    @Test
    fun `normalizer collapses recognizer whitespace and trims`() {
        val normalized = TextNormalizer.normalize("  need   water   at   camp   3  \n")
        assertEquals("need water at camp 3", normalized)
    }

    @Test
    fun `normalizer bounds runaway length`() {
        val huge = "a".repeat(5000)
        val normalized = TextNormalizer.normalize(huge)
        assertEquals(Message.MAX_TEXT_LENGTH_CHARS, normalized.length)
    }

    @Test
    fun `message id generator is monotonic and does not collide across calls`() {
        val generator = MessageIdGenerator(startAt = 1_700_000_000_000L)
        val ids = (1..1000).map { generator.next().value }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.zipWithNext().all { (a, b) -> b > a })
    }
}
