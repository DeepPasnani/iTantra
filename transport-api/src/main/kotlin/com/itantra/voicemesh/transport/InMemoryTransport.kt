package com.itantra.voicemesh.transport

import com.itantra.voicemesh.messaging.NodeId
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A test/simulation double for [Transport]. A shared [InMemoryMeshFabric] plays the
 * role of the radio medium: each [InMemoryTransport] only delivers to neighbors the
 * fabric currently says are in range, so tests can build an arbitrary, changeable
 * multi-node topology in a single JVM process and drive AODV-style route discovery,
 * forwarding, and route-break recovery deterministically — without needing real
 * phones or real Wi-Fi (spec §12's on-device validation is still required separately
 * for the real transport-wifi implementation).
 */
class InMemoryMeshFabric {
    private val transports = mutableMapOf<NodeId, InMemoryTransport>()
    private val links = mutableSetOf<Pair<NodeId, NodeId>>()

    fun register(transport: InMemoryTransport) {
        transports[transport.localNodeId] = transport
    }

    private fun key(a: NodeId, b: NodeId): Pair<NodeId, NodeId> =
        if (a.value < b.value) a to b else b to a

    /** Makes [a] and [b] direct one-hop neighbors of each other. */
    fun link(a: NodeId, b: NodeId) {
        if (links.add(key(a, b))) {
            transports[a]?.onNeighborAppeared(b)
            transports[b]?.onNeighborAppeared(a)
        }
    }

    /** Breaks the direct link between [a] and [b] (simulates moving out of range). */
    fun unlink(a: NodeId, b: NodeId) {
        if (links.remove(key(a, b))) {
            transports[a]?.onNeighborDisappeared(b)
            transports[b]?.onNeighborDisappeared(a)
        }
    }

    fun isLinked(a: NodeId, b: NodeId): Boolean = links.contains(key(a, b))

    internal fun deliver(from: NodeId, to: NodeId, payload: ByteArray) {
        if (!isLinked(from, to)) return
        transports[to]?.onPacketArrived(from, payload)
    }
}

class InMemoryTransport(
    override val localNodeId: NodeId,
    private val fabric: InMemoryMeshFabric,
) : Transport {
    private val listeners = CopyOnWriteArrayList<TransportListener>()
    private val currentNeighbors = mutableSetOf<NodeId>()

    override val neighbors: Set<NodeId> get() = currentNeighbors.toSet()

    init {
        fabric.register(this)
    }

    override fun send(neighborId: NodeId, payload: ByteArray) {
        fabric.deliver(localNodeId, neighborId, payload)
    }

    override fun addListener(listener: TransportListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: TransportListener) {
        listeners.remove(listener)
    }

    internal fun onNeighborAppeared(neighborId: NodeId) {
        if (currentNeighbors.add(neighborId)) {
            listeners.forEach { it.onNeighborJoined(neighborId) }
        }
    }

    internal fun onNeighborDisappeared(neighborId: NodeId) {
        if (currentNeighbors.remove(neighborId)) {
            listeners.forEach { it.onNeighborLost(neighborId) }
        }
    }

    internal fun onPacketArrived(fromNeighborId: NodeId, payload: ByteArray) {
        listeners.forEach { it.onPacketReceived(fromNeighborId, payload) }
    }
}
