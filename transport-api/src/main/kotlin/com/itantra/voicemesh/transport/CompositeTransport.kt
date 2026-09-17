package com.itantra.voicemesh.transport

import com.itantra.voicemesh.messaging.NodeId
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Merges several one-hop [Transport]s into one logical [Transport] for [AodvRouter]-
 * style code that only wants to talk to a single transport instance.
 *
 * This is how a "relay / bridge node" (spec §5) is modeled: a phone that is
 * simultaneously a client on one Wi-Fi Direct group/hotspot and the owner of another
 * is just a node whose [Transport] happens to be a [CompositeTransport] wrapping one
 * [Transport] per group it participates in. Whether a given phone's chipset/OS
 * actually supports being in two such roles at once is exactly the concurrency
 * question spec §5/§12 says must be verified on real hardware — this class does not
 * make that claim; it only avoids hard-coding "one node has exactly one radio role"
 * into the routing layer so that verified concurrency can be used when it exists.
 */
class CompositeTransport(
    override val localNodeId: NodeId,
    private val children: List<Transport>,
) : Transport {
    private val listeners = CopyOnWriteArrayList<TransportListener>()

    // Which child transport a currently-reachable neighbor was last seen through.
    private val neighborOwner = mutableMapOf<NodeId, Transport>()

    override val neighbors: Set<NodeId> get() = neighborOwner.keys.toSet()

    init {
        children.forEach { child ->
            child.addListener(object : TransportListener {
                override fun onPacketReceived(fromNeighborId: NodeId, payload: ByteArray) {
                    listeners.forEach { it.onPacketReceived(fromNeighborId, payload) }
                }

                override fun onNeighborJoined(neighborId: NodeId) {
                    // If the same peer is briefly reachable via more than one child
                    // (e.g. overlapping groups), the first to report it wins.
                    if (neighborOwner.putIfAbsent(neighborId, child) == null) {
                        listeners.forEach { it.onNeighborJoined(neighborId) }
                    }
                }

                override fun onNeighborLost(neighborId: NodeId) {
                    if (neighborOwner[neighborId] === child) {
                        neighborOwner.remove(neighborId)
                        listeners.forEach { it.onNeighborLost(neighborId) }
                    }
                }
            })
        }
    }

    override fun send(neighborId: NodeId, payload: ByteArray) {
        neighborOwner[neighborId]?.send(neighborId, payload)
    }

    override fun addListener(listener: TransportListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: TransportListener) {
        listeners.remove(listener)
    }
}
