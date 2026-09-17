package com.itantra.voicemesh.transport

import com.itantra.voicemesh.messaging.NodeId

/**
 * A single-hop link layer abstraction. On a real phone this is backed by a Wi-Fi
 * Direct / hotspot socket to one directly-reachable neighbor (see :transport-wifi);
 * in tests it is backed by an in-process queue so the AODV-style routing and
 * reliability logic in :core-routing / :core-reliability can be exercised across many
 * virtual nodes without any device (spec §12 calls for on-device validation of the
 * real transport separately — this abstraction is what makes the routing logic itself
 * testable ahead of that).
 *
 * Deliberately one-hop only: [Transport] never claims to reach a node it is not
 * directly linked to. Multi-hop delivery is entirely the job of the routing layer.
 */
interface Transport {
    val localNodeId: NodeId

    /** Currently reachable one-hop neighbors. */
    val neighbors: Set<NodeId>

    /** Best-effort send of one packet to a directly reachable neighbor. */
    fun send(neighborId: NodeId, payload: ByteArray)

    fun addListener(listener: TransportListener)
    fun removeListener(listener: TransportListener)
}

interface TransportListener {
    fun onPacketReceived(fromNeighborId: NodeId, payload: ByteArray)
    fun onNeighborJoined(neighborId: NodeId)
    fun onNeighborLost(neighborId: NodeId)
}
