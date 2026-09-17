package com.itantra.voicemesh.routing

import com.itantra.voicemesh.messaging.NodeId
import com.itantra.voicemesh.transport.Transport
import com.itantra.voicemesh.transport.TransportListener

/** Outcome of a route discovery attempt, reported once per [AodvRouter.discoverRoute] call or timeout. */
sealed class DiscoveryResult {
    data class RouteFound(val destination: NodeId, val hopCount: Int) : DiscoveryResult()
    data class TimedOut(val destination: NodeId) : DiscoveryResult()
}

/**
 * AODV-style (on-demand, reactive) multi-hop router (spec §6). Not a full RFC 3561
 * implementation: only the true destination replies, there's no destination
 * sequence-number freshness check, and route-error propagation only walks back one
 * hop at a time rather than maintaining full precursor lists. That's an intentional
 * simplification for this scale (§8: ~20-50 nodes per domain), not a claim of
 * standards compliance (§16).
 *
 * Time-based behavior (discovery timeout, route expiry) is driven by explicit calls
 * to [tick] rather than an internal scheduler, so this stays a plain, deterministic,
 * unit-testable Kotlin class with no Android/coroutine dependency; the app layer is
 * responsible for calling [tick] periodically (e.g. once a second).
 */
class AodvRouter(
    val localNodeId: NodeId,
    private val transport: Transport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val routeRequestTimeoutMillis: Long = 2_000,
    private val routeLifetimeMillis: Long = 30_000,
    private val maxTtl: Int = RoutingPacket.DEFAULT_TTL,
    onDataDelivered: (fromNodeId: NodeId, payload: ByteArray) -> Unit = { _, _ -> },
    private val onDiscoveryResult: (DiscoveryResult) -> Unit = {},
) : TransportListener {

    /** Mutable so a layer built on top (e.g. :core-reliability) can attach itself after construction via [attachDataHandler]. */
    private var dataHandler: (NodeId, ByteArray) -> Unit = onDataDelivered

    /** Replaces the handler invoked when a data packet addressed to this node arrives. */
    fun attachDataHandler(handler: (fromNodeId: NodeId, payload: ByteArray) -> Unit) {
        dataHandler = handler
    }

    private val routingTable = RoutingTable()
    private val seenBroadcasts = SeenCache<Pair<NodeId, UInt>>()
    private var broadcastCounter = 0u

    private data class PendingDiscovery(val destination: NodeId, val startedAtMillis: Long, val deadlineMillis: Long)
    private val pendingDiscoveries = mutableMapOf<NodeId, PendingDiscovery>()
    private val queuedPayloads = mutableMapOf<NodeId, MutableList<ByteArray>>()

    init {
        transport.addListener(this)
    }

    fun currentRoutes(): List<RouteEntry> = routingTable.snapshot()

    fun hasRouteTo(destination: NodeId): Boolean = routingTable.routeTo(destination, clock()) != null

    /**
     * Sends [payload] to [destinationNodeId]. If no valid route exists yet, the
     * payload is buffered in-memory and route discovery is (re)started — this is the
     * "if no route exists, discovery starts, message stays queued until connectivity
     * returns" behavior from spec §6. Durable store-and-forward across app restarts is
     * :core-reliability's job, layered on top of this.
     */
    fun sendData(destinationNodeId: NodeId, payload: ByteArray) {
        val route = routingTable.routeTo(destinationNodeId, clock())
        if (route != null) {
            transport.send(route.nextHop, encode(RoutingPacket.Data(localNodeId, destinationNodeId, maxTtl, 0, payload)))
        } else {
            queuedPayloads.getOrPut(destinationNodeId) { mutableListOf() }.add(payload)
            discoverRoute(destinationNodeId)
        }
    }

    /** Starts route discovery if one isn't already in flight for [destinationNodeId]. */
    fun discoverRoute(destinationNodeId: NodeId) {
        if (pendingDiscoveries.containsKey(destinationNodeId)) return
        val now = clock()
        pendingDiscoveries[destinationNodeId] = PendingDiscovery(destinationNodeId, now, now + routeRequestTimeoutMillis)
        broadcastCounter += 1u
        seenBroadcasts.observeAndCheckIfNew(localNodeId to broadcastCounter, now)
        val rreq = RoutingPacket.RouteRequest(broadcastCounter, localNodeId, destinationNodeId, hopCount = 0, ttl = maxTtl)
        floodToAllNeighborsExcept(rreq, exclude = null)
    }

    /** Call periodically (e.g. every second) to expire timed-out discoveries and stale routes. */
    fun tick() {
        val now = clock()
        routingTable.purgeExpired(now)
        val timedOut = pendingDiscoveries.values.filter { it.deadlineMillis <= now }.map { it.destination }
        for (destination in timedOut) {
            pendingDiscoveries.remove(destination)
            onDiscoveryResult(DiscoveryResult.TimedOut(destination))
        }
    }

    override fun onPacketReceived(fromNeighborId: NodeId, payload: ByteArray) {
        when (val packet = decode(payload)) {
            is RoutingPacket.RouteRequest -> handleRouteRequest(fromNeighborId, packet)
            is RoutingPacket.RouteReply -> handleRouteReply(fromNeighborId, packet)
            is RoutingPacket.RouteError -> handleRouteError(fromNeighborId, packet)
            is RoutingPacket.Data -> handleData(fromNeighborId, packet)
        }
    }

    override fun onNeighborJoined(neighborId: NodeId) {
        // A newly visible neighbor might unblock destinations we're still queuing for;
        // re-trigger discovery for anything we're still waiting on (cheap at this scale).
        queuedPayloads.keys.forEach { discoverRoute(it) }
    }

    override fun onNeighborLost(neighborId: NodeId) {
        val affectedDestinations = routingTable.invalidateRoutesThrough(neighborId)
        for (destination in affectedDestinations) {
            // Best-effort: tell our other neighbors this destination is no longer reachable via us either.
            floodToAllNeighborsExcept(RoutingPacket.RouteError(destination, localNodeId), exclude = null)
        }
        if (affectedDestinations.isNotEmpty()) {
            // Broken links trigger rediscovery (spec §6/§7) for anything still queued.
            queuedPayloads.keys.filter { it in affectedDestinations }.forEach { discoverRoute(it) }
        }
    }

    private fun handleRouteRequest(fromNeighborId: NodeId, packet: RoutingPacket.RouteRequest) {
        val now = clock()
        val isNew = seenBroadcasts.observeAndCheckIfNew(packet.originNodeId to packet.broadcastId, now)
        if (!isNew) return

        // Learn the reverse route back to the originator via whoever forwarded this to us.
        routingTable.offer(RouteEntry(packet.originNodeId, fromNeighborId, packet.hopCount + 1, now + routeLifetimeMillis))

        if (packet.destinationNodeId == localNodeId) {
            val reply = RoutingPacket.RouteReply(packet.originNodeId, localNodeId, hopCount = 0, lifetimeMillis = routeLifetimeMillis)
            transport.send(fromNeighborId, encode(reply))
            return
        }

        if (packet.hopCount + 1 >= maxTtl || packet.ttl <= 0) return // TTL/hop limit (spec §7)

        val forwarded = packet.copy(hopCount = packet.hopCount + 1, ttl = packet.ttl - 1)
        floodToAllNeighborsExcept(forwarded, exclude = fromNeighborId)
    }

    private fun handleRouteReply(fromNeighborId: NodeId, packet: RoutingPacket.RouteReply) {
        val now = clock()
        routingTable.offer(RouteEntry(packet.destinationNodeId, fromNeighborId, packet.hopCount + 1, now + routeLifetimeMillis))

        if (packet.originNodeId == localNodeId) {
            pendingDiscoveries.remove(packet.destinationNodeId)
            onDiscoveryResult(DiscoveryResult.RouteFound(packet.destinationNodeId, packet.hopCount + 1))
            flushQueuedPayloads(packet.destinationNodeId)
            return
        }

        // Forward the reply back toward the original requester along the reverse route we learned during RREQ flooding.
        val routeToOrigin = routingTable.routeTo(packet.originNodeId, now)
        if (routeToOrigin != null) {
            transport.send(routeToOrigin.nextHop, encode(packet.copy(hopCount = packet.hopCount + 1)))
        }
    }

    private fun handleRouteError(fromNeighborId: NodeId, packet: RoutingPacket.RouteError) {
        val hadRouteViaReporter = routingTable.routeTo(packet.unreachableNodeId, clock())?.nextHop == fromNeighborId
        if (!hadRouteViaReporter) return
        routingTable.invalidate(packet.unreachableNodeId)
        floodToAllNeighborsExcept(packet.copy(reporterNodeId = localNodeId), exclude = fromNeighborId)
        if (packet.unreachableNodeId in queuedPayloads) {
            discoverRoute(packet.unreachableNodeId)
        }
    }

    private fun handleData(fromNeighborId: NodeId, packet: RoutingPacket.Data) {
        if (packet.destinationNodeId == localNodeId) {
            dataHandler(packet.sourceNodeId, packet.payload)
            return
        }
        if (packet.ttl <= 0) return // TTL exhausted: drop stale/looping packet (spec §7)

        val now = clock()
        val route = routingTable.routeTo(packet.destinationNodeId, now)
        if (route == null) {
            // Route broke mid-flight: tell the previous hop and let the source retry/rediscover.
            transport.send(fromNeighborId, encode(RoutingPacket.RouteError(packet.destinationNodeId, localNodeId)))
            return
        }
        transport.send(route.nextHop, encode(packet.copy(ttl = packet.ttl - 1, hopCount = packet.hopCount + 1)))
    }

    private fun flushQueuedPayloads(destinationNodeId: NodeId) {
        val queued = queuedPayloads.remove(destinationNodeId) ?: return
        val route = routingTable.routeTo(destinationNodeId, clock()) ?: return
        for (payload in queued) {
            transport.send(route.nextHop, encode(RoutingPacket.Data(localNodeId, destinationNodeId, maxTtl, 0, payload)))
        }
    }

    private fun floodToAllNeighborsExcept(packet: RoutingPacket, exclude: NodeId?) {
        val bytes = encode(packet)
        for (neighborId in transport.neighbors) {
            if (neighborId != exclude) transport.send(neighborId, bytes)
        }
    }

    private fun encode(packet: RoutingPacket): ByteArray = RoutingPacketCodec.encode(packet)
    private fun decode(bytes: ByteArray): RoutingPacket = RoutingPacketCodec.decode(bytes)
}
