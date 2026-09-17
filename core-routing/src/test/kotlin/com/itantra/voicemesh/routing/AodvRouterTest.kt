package com.itantra.voicemesh.routing

import com.itantra.voicemesh.messaging.NodeId
import com.itantra.voicemesh.transport.InMemoryMeshFabric
import com.itantra.voicemesh.transport.InMemoryTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A settable clock so tests can simulate elapsed time without real sleeps. */
private class TestClock(var nowMillis: Long = 0L) {
    fun get(): Long = nowMillis
    fun advance(byMillis: Long) {
        nowMillis += byMillis
    }
}

private class TestNode(id: NodeId, fabric: InMemoryMeshFabric, clock: TestClock) {
    val transport = InMemoryTransport(id, fabric)
    val delivered = mutableListOf<Pair<NodeId, String>>()
    val discoveryResults = mutableListOf<DiscoveryResult>()
    val router = AodvRouter(
        localNodeId = id,
        transport = transport,
        clock = clock::get,
        onDataDelivered = { from, payload -> delivered.add(from to String(payload)) },
        onDiscoveryResult = { discoveryResults.add(it) },
    )
}

private fun node(value: UInt) = NodeId(value)

class AodvRouterTest {
    @Test
    fun `delivers directly between one-hop neighbors after discovery`() {
        val clock = TestClock()
        val fabric = InMemoryMeshFabric()
        val a = TestNode(node(1u), fabric, clock)
        val b = TestNode(node(2u), fabric, clock)
        fabric.link(a.router.localNodeId, b.router.localNodeId)

        a.router.sendData(b.router.localNodeId, "hello".toByteArray())

        assertEquals(listOf(a.router.localNodeId to "hello"), b.delivered)
        assertTrue(a.discoveryResults.any { it is DiscoveryResult.RouteFound })
    }

    @Test
    fun `forwards data across a multi-hop chain via an intermediate relay`() {
        val clock = TestClock()
        val fabric = InMemoryMeshFabric()
        val a = TestNode(node(1u), fabric, clock)
        val relay = TestNode(node(2u), fabric, clock)
        val c = TestNode(node(3u), fabric, clock)
        fabric.link(a.router.localNodeId, relay.router.localNodeId)
        fabric.link(relay.router.localNodeId, c.router.localNodeId)
        // A and C are not directly linked: only reachable through the relay.

        a.router.sendData(c.router.localNodeId, "need water at camp 3".toByteArray())

        assertEquals(listOf(a.router.localNodeId to "need water at camp 3"), c.delivered)
        assertTrue(relay.delivered.isEmpty(), "the relay should forward, not consume, the data packet")
    }

    @Test
    fun `duplicate route requests in a cyclic topology are suppressed`() {
        val clock = TestClock()
        val fabric = InMemoryMeshFabric()
        val a = TestNode(node(1u), fabric, clock)
        val b = TestNode(node(2u), fabric, clock)
        val d = TestNode(node(3u), fabric, clock)
        val c = TestNode(node(4u), fabric, clock)
        // Diamond: A-B-C and A-D-C, plus B-D, so RREQs can loop without dedup.
        fabric.link(a.router.localNodeId, b.router.localNodeId)
        fabric.link(a.router.localNodeId, d.router.localNodeId)
        fabric.link(b.router.localNodeId, c.router.localNodeId)
        fabric.link(d.router.localNodeId, c.router.localNodeId)
        fabric.link(b.router.localNodeId, d.router.localNodeId)

        a.router.sendData(c.router.localNodeId, "ping".toByteArray())

        assertEquals(listOf(a.router.localNodeId to "ping"), c.delivered)
    }

    @Test
    fun `route break triggers rediscovery over an alternate path`() {
        val clock = TestClock()
        val fabric = InMemoryMeshFabric()
        val a = TestNode(node(1u), fabric, clock)
        val relay1 = TestNode(node(2u), fabric, clock)
        val relay2 = TestNode(node(3u), fabric, clock)
        val d = TestNode(node(4u), fabric, clock)
        fabric.link(a.router.localNodeId, relay1.router.localNodeId)
        fabric.link(relay1.router.localNodeId, d.router.localNodeId)
        fabric.link(a.router.localNodeId, relay2.router.localNodeId)
        fabric.link(relay2.router.localNodeId, d.router.localNodeId)

        a.router.sendData(d.router.localNodeId, "first".toByteArray())
        assertEquals(1, d.delivered.size)

        // Simulate relay1 moving out of range of both A and D.
        fabric.unlink(a.router.localNodeId, relay1.router.localNodeId)
        fabric.unlink(relay1.router.localNodeId, d.router.localNodeId)

        a.router.sendData(d.router.localNodeId, "second".toByteArray())

        assertEquals(
            listOf(a.router.localNodeId to "first", a.router.localNodeId to "second"),
            d.delivered,
        )
    }

    @Test
    fun `route discovery times out when destination is unreachable`() {
        val clock = TestClock()
        val fabric = InMemoryMeshFabric()
        val a = TestNode(node(1u), fabric, clock)
        val unreachable = node(99u)

        a.router.sendData(unreachable, "hello?".toByteArray())
        assertTrue(a.discoveryResults.isEmpty(), "should not resolve before the timeout")

        clock.advance(3_000)
        a.router.tick()

        val expected: List<DiscoveryResult> = listOf(DiscoveryResult.TimedOut(unreachable))
        assertEquals(expected, a.discoveryResults)
    }

    @Test
    fun `expired routes are purged and re-discovered on next send`() {
        val clock = TestClock()
        val fabric = InMemoryMeshFabric()
        val a = TestNode(node(1u), fabric, clock)
        val b = TestNode(node(2u), fabric, clock)
        fabric.link(a.router.localNodeId, b.router.localNodeId)

        a.router.sendData(b.router.localNodeId, "one".toByteArray())
        assertTrue(a.router.hasRouteTo(b.router.localNodeId))

        clock.advance(60_000) // well past the default route lifetime
        a.router.tick()

        assertTrue(a.router.currentRoutes().none { it.destination == b.router.localNodeId })
    }
}
