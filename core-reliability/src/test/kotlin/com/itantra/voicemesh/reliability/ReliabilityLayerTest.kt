package com.itantra.voicemesh.reliability

import com.itantra.voicemesh.messaging.Language
import com.itantra.voicemesh.messaging.Message
import com.itantra.voicemesh.messaging.MessageIdGenerator
import com.itantra.voicemesh.messaging.NodeId
import com.itantra.voicemesh.messaging.Priority
import com.itantra.voicemesh.routing.AodvRouter
import com.itantra.voicemesh.transport.InMemoryMeshFabric
import com.itantra.voicemesh.transport.InMemoryTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class TestClock(var nowMillis: Long = 0L) {
    fun get(): Long = nowMillis
    fun advance(byMillis: Long) {
        nowMillis += byMillis
    }
}

private class TestNode(id: NodeId, fabric: InMemoryMeshFabric, clock: TestClock) {
    val received = mutableListOf<Message>()
    val outcomes = mutableListOf<Pair<com.itantra.voicemesh.messaging.MessageId, DeliveryOutcome>>()
    val transport = InMemoryTransport(id, fabric)
    val router = AodvRouter(localNodeId = id, transport = transport, clock = clock::get)
    val reliability = ReliabilityLayer(
        router = router,
        clock = clock::get,
        onMessageReceived = { received.add(it) },
        onDeliveryOutcome = { id, outcome -> outcomes.add(id to outcome) },
    )
}

private fun node(value: UInt) = NodeId(value)

private fun message(from: NodeId, to: NodeId, text: String, idGen: MessageIdGenerator) = Message.create(
    idGenerator = idGen,
    sourceNodeId = from,
    destinationNodeId = to,
    language = Language.KANNADA,
    priority = Priority.NORMAL,
    text = text,
)

class ReliabilityLayerTest {
    @Test
    fun `delivers, dedups, and acks a message across a relay`() {
        val clock = TestClock()
        val fabric = InMemoryMeshFabric()
        val a = TestNode(node(1u), fabric, clock)
        val relay = TestNode(node(2u), fabric, clock)
        val b = TestNode(node(3u), fabric, clock)
        fabric.link(node(1u), node(2u))
        fabric.link(node(2u), node(3u))

        val idGen = MessageIdGenerator(startAt = 1_700_000_000_000L)
        val msg = message(node(1u), node(3u), "help needed at the north bridge", idGen)

        a.reliability.sendMessage(msg)

        assertEquals(listOf(msg), b.received)
        assertTrue(relay.received.isEmpty(), "the relay must not treat forwarded data as delivered to it")
        assertEquals(listOf(msg.id to DeliveryOutcome.ACKED), a.outcomes)
    }

    @Test
    fun `retries after ack timeout then succeeds once the ack arrives`() {
        val clock = TestClock()
        val fabric = InMemoryMeshFabric()
        val a = TestNode(node(1u), fabric, clock)
        val b = TestNode(node(2u), fabric, clock)
        fabric.link(node(1u), node(2u))

        val idGen = MessageIdGenerator()
        val msg = message(node(1u), node(2u), "status check", idGen)
        a.reliability.sendMessage(msg)

        assertEquals(1, b.received.size)
        assertEquals(listOf(msg.id to DeliveryOutcome.ACKED), a.outcomes)

        // Even though it already succeeded, advancing past the ack timeout and ticking
        // again must not re-report or double-retry a message that's already out of the
        // outbox (it was removed on ACK).
        clock.advance(10_000)
        a.reliability.tick()
        assertEquals(1, a.outcomes.size)
    }

    @Test
    fun `gives up after max retries when destination is unreachable`() {
        val clock = TestClock()
        val fabric = InMemoryMeshFabric()
        val a = TestNode(node(1u), fabric, clock)
        val unreachable = node(42u)

        val idGen = MessageIdGenerator()
        val msg = message(node(1u), unreachable, "anyone there?", idGen)
        a.reliability.sendMessage(msg)

        repeat(6) {
            clock.advance(4_100)
            a.reliability.tick()
        }

        assertTrue(a.outcomes.any { it.second == DeliveryOutcome.FAILED_MAX_RETRIES })
        assertEquals(DeliveryOutcome.FAILED_MAX_RETRIES, a.outcomes.last().second)
    }

    @Test
    fun `store-and-forward delivers once a route break is repaired`() {
        val clock = TestClock()
        val fabric = InMemoryMeshFabric()
        val a = TestNode(node(1u), fabric, clock)
        val b = TestNode(node(2u), fabric, clock)
        // No link yet: destination is temporarily unreachable (partition).

        val idGen = MessageIdGenerator()
        val msg = message(node(1u), node(2u), "queued while partitioned", idGen)
        a.reliability.sendMessage(msg)

        assertTrue(b.received.isEmpty())

        clock.advance(3_000)
        a.router.tick() // route discovery attempt times out, message stays queued in the outbox

        fabric.link(node(1u), node(2u)) // connectivity returns
        clock.advance(4_100)
        a.reliability.tick() // retry now finds a route and delivers

        assertEquals(listOf(msg), b.received)
    }

    @Test
    fun `duplicate delivery of the same message is not reported twice`() {
        val clock = TestClock()
        val fabric = InMemoryMeshFabric()
        val a = TestNode(node(1u), fabric, clock)
        val b = TestNode(node(2u), fabric, clock)
        fabric.link(node(1u), node(2u))

        val idGen = MessageIdGenerator()
        val msg = message(node(1u), node(2u), "duplicate test", idGen)

        // Simulate the ack getting lost: the sender resends the same message id.
        a.router.sendData(node(2u), WireEnvelopeCodec.encode(WireEnvelope.MessagePayload(msg)))
        a.router.sendData(node(2u), WireEnvelopeCodec.encode(WireEnvelope.MessagePayload(msg)))

        assertEquals(1, b.received.size, "TTS must not be asked to speak the same message twice")
    }
}
