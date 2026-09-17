package com.itantra.voicemesh.routing

import com.itantra.voicemesh.messaging.NodeId

/**
 * Control/data packets for the AODV-STYLE routing layer (spec §6/§16: this is an
 * AODV-inspired on-demand routing scheme, not a standards-complete RFC 3561
 * implementation — e.g. only the true destination generates a route reply, there is
 * no sequence-number freshness comparison, and route error propagation is best-effort
 * rather than precisely mirroring precursor lists).
 */
sealed class RoutingPacket {
    /** Flooded to discover a route to [destinationNodeId]. */
    data class RouteRequest(
        val broadcastId: UInt,
        val originNodeId: NodeId,
        val destinationNodeId: NodeId,
        val hopCount: Int,
        val ttl: Int,
    ) : RoutingPacket()

    /** Sent by the destination back along the reverse path once an RREQ reaches it. */
    data class RouteReply(
        val originNodeId: NodeId,
        val destinationNodeId: NodeId,
        val hopCount: Int,
        val lifetimeMillis: Long,
    ) : RoutingPacket()

    /** Reports that [unreachableNodeId] is no longer reachable via [reporterNodeId]. */
    data class RouteError(
        val unreachableNodeId: NodeId,
        val reporterNodeId: NodeId,
    ) : RoutingPacket()

    /** An opaque application payload (an encoded Message or an Ack) forwarded hop by hop. */
    data class Data(
        val sourceNodeId: NodeId,
        val destinationNodeId: NodeId,
        val ttl: Int,
        val hopCount: Int,
        val payload: ByteArray,
    ) : RoutingPacket() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Data) return false
            return sourceNodeId == other.sourceNodeId &&
                destinationNodeId == other.destinationNodeId &&
                ttl == other.ttl &&
                hopCount == other.hopCount &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = sourceNodeId.hashCode()
            result = 31 * result + destinationNodeId.hashCode()
            result = 31 * result + ttl
            result = 31 * result + hopCount
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    companion object {
        /** Default hop limit: bounds how long a stale/looping packet can circulate (spec §7). */
        const val DEFAULT_TTL = 12
    }
}
