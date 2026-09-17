package com.itantra.voicemesh.transport

import com.itantra.voicemesh.messaging.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompositeTransportTest {
    @Test
    fun `merges neighbors from two child transports and routes sends to the right one`() {
        val fabricA = InMemoryMeshFabric()
        val fabricB = InMemoryMeshFabric()

        val bridgeSideA = InMemoryTransport(NodeId(1u), fabricA)
        val bridgeSideB = InMemoryTransport(NodeId(1u), fabricB)
        val composite = CompositeTransport(NodeId(1u), listOf(bridgeSideA, bridgeSideB))

        val peerOnA = InMemoryTransport(NodeId(2u), fabricA)
        InMemoryTransport(NodeId(3u), fabricB) // keeps node 3 registered on fabricB so it's reachable
        fabricA.link(NodeId(1u), NodeId(2u))
        fabricB.link(NodeId(1u), NodeId(3u))

        assertEquals(setOf(NodeId(2u), NodeId(3u)), composite.neighbors)

        val receivedByPeerA = mutableListOf<String>()
        peerOnA.addListener(object : TransportListener {
            override fun onPacketReceived(fromNeighborId: NodeId, payload: ByteArray) {
                receivedByPeerA.add(String(payload))
            }
            override fun onNeighborJoined(neighborId: NodeId) {}
            override fun onNeighborLost(neighborId: NodeId) {}
        })

        composite.send(NodeId(2u), "hello via group A".toByteArray())
        assertEquals(listOf("hello via group A"), receivedByPeerA)

        fabricA.unlink(NodeId(1u), NodeId(2u))
        assertTrue(NodeId(2u) !in composite.neighbors)
        assertTrue(NodeId(3u) in composite.neighbors)
    }
}
