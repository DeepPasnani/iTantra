package com.itantra.voicemesh.messaging

/**
 * Identifies one mesh node (one phone). Compact 32-bit id so it fits cheaply into
 * every routing/data packet header alongside [MessageId]. Derived by the app from a
 * locally generated random value at first run, not from any hardware identifier.
 */
@JvmInline
value class NodeId(val value: UInt) {
    override fun toString(): String = "Node(${value.toString(16)})"

    companion object {
        val BROADCAST = NodeId(0xFFFFFFFFu)
        val UNKNOWN = NodeId(0u)
    }
}

/**
 * Identifies one logical message from one source node. Uniqueness only needs to hold
 * per-source (dedup key is [NodeId] + [MessageId] together, mirroring how AODV pairs an
 * originator address with a broadcast id) so a simple monotonic counter is sufficient
 * and avoids pulling in a UUID dependency for an 8-byte field.
 */
@JvmInline
value class MessageId(val value: ULong) {
    override fun toString(): String = "Msg(${value.toString(16)})"
}

/** Generates [MessageId]s that stay monotonic within a process and are seeded from wall-clock time so a restart is very unlikely to reissue an id an in-flight message from a previous run still uses. */
class MessageIdGenerator(startAt: Long = System.currentTimeMillis()) {
    private var counter: ULong = startAt.toULong() shl 20

    @Synchronized
    fun next(): MessageId {
        counter += 1u
        return MessageId(counter)
    }
}
