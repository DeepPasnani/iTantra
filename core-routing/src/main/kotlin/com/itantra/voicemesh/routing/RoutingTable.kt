package com.itantra.voicemesh.routing

import com.itantra.voicemesh.messaging.NodeId

data class RouteEntry(
    val destination: NodeId,
    val nextHop: NodeId,
    val hopCount: Int,
    val expiresAtMillis: Long,
)

/**
 * Route cache with expiry (spec §7 "Route expiry/rediscovery"). One entry per
 * destination — good enough at the 20-50 node scale this is designed for (§8); it is
 * not meant to hold every possible destination in a much larger network.
 */
class RoutingTable {
    private val entries = mutableMapOf<NodeId, RouteEntry>()

    @Synchronized
    fun routeTo(destination: NodeId, nowMillis: Long): RouteEntry? {
        val entry = entries[destination] ?: return null
        if (entry.expiresAtMillis <= nowMillis) {
            entries.remove(destination)
            return null
        }
        return entry
    }

    /** Installs [entry] only if it is new or strictly better (fewer hops) than what we have, refreshing its lifetime either way we've just heard about it. */
    @Synchronized
    fun offer(entry: RouteEntry) {
        val existing = entries[entry.destination]
        if (existing == null || entry.hopCount <= existing.hopCount || existing.expiresAtMillis < entry.expiresAtMillis) {
            entries[entry.destination] = entry
        }
    }

    @Synchronized
    fun invalidate(destination: NodeId) {
        entries.remove(destination)
    }

    /** Returns destinations whose route went through [brokenNeighbor], and removes them (spec: route break -> rediscovery). */
    @Synchronized
    fun invalidateRoutesThrough(brokenNeighbor: NodeId): List<NodeId> {
        val affected = entries.values.filter { it.nextHop == brokenNeighbor }.map { it.destination }
        affected.forEach { entries.remove(it) }
        return affected
    }

    @Synchronized
    fun purgeExpired(nowMillis: Long) {
        entries.entries.removeAll { it.value.expiresAtMillis <= nowMillis }
    }

    @Synchronized
    fun snapshot(): List<RouteEntry> = entries.values.toList()
}
