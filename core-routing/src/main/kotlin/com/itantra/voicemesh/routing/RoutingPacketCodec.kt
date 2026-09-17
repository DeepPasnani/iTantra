package com.itantra.voicemesh.routing

import com.itantra.voicemesh.messaging.NodeId
import java.nio.ByteBuffer

/** Compact binary wire format, same rationale as [com.itantra.voicemesh.messaging.MessageCodec]. */
object RoutingPacketCodec {
    private const val TAG_RREQ: Byte = 1
    private const val TAG_RREP: Byte = 2
    private const val TAG_RERR: Byte = 3
    private const val TAG_DATA: Byte = 4

    fun encode(packet: RoutingPacket): ByteArray = when (packet) {
        is RoutingPacket.RouteRequest -> ByteBuffer.allocate(15).apply {
            put(TAG_RREQ)
            putInt(packet.broadcastId.toInt())
            putInt(packet.originNodeId.value.toInt())
            putInt(packet.destinationNodeId.value.toInt())
            put(packet.hopCount.toByte())
            put(packet.ttl.toByte())
        }.array()

        is RoutingPacket.RouteReply -> ByteBuffer.allocate(18).apply {
            put(TAG_RREP)
            putInt(packet.originNodeId.value.toInt())
            putInt(packet.destinationNodeId.value.toInt())
            put(packet.hopCount.toByte())
            putLong(packet.lifetimeMillis)
        }.array()

        is RoutingPacket.RouteError -> ByteBuffer.allocate(9).apply {
            put(TAG_RERR)
            putInt(packet.unreachableNodeId.value.toInt())
            putInt(packet.reporterNodeId.value.toInt())
        }.array()

        is RoutingPacket.Data -> ByteBuffer.allocate(13 + packet.payload.size).apply {
            put(TAG_DATA)
            putInt(packet.sourceNodeId.value.toInt())
            putInt(packet.destinationNodeId.value.toInt())
            put(packet.ttl.toByte())
            put(packet.hopCount.toByte())
            putShort(packet.payload.size.toShort())
            put(packet.payload)
        }.array()
    }

    fun decode(bytes: ByteArray): RoutingPacket {
        val buffer = ByteBuffer.wrap(bytes)
        return when (val tag = buffer.get()) {
            TAG_RREQ -> RoutingPacket.RouteRequest(
                broadcastId = buffer.int.toUInt(),
                originNodeId = NodeId(buffer.int.toUInt()),
                destinationNodeId = NodeId(buffer.int.toUInt()),
                hopCount = buffer.get().toInt(),
                ttl = buffer.get().toInt(),
            )

            TAG_RREP -> RoutingPacket.RouteReply(
                originNodeId = NodeId(buffer.int.toUInt()),
                destinationNodeId = NodeId(buffer.int.toUInt()),
                hopCount = buffer.get().toInt(),
                lifetimeMillis = buffer.long,
            )

            TAG_RERR -> RoutingPacket.RouteError(
                unreachableNodeId = NodeId(buffer.int.toUInt()),
                reporterNodeId = NodeId(buffer.int.toUInt()),
            )

            TAG_DATA -> {
                val sourceNodeId = NodeId(buffer.int.toUInt())
                val destinationNodeId = NodeId(buffer.int.toUInt())
                val ttl = buffer.get().toInt()
                val hopCount = buffer.get().toInt()
                val payloadLen = buffer.short.toInt() and 0xFFFF
                val payload = ByteArray(payloadLen)
                buffer.get(payload)
                RoutingPacket.Data(sourceNodeId, destinationNodeId, ttl, hopCount, payload)
            }

            else -> throw IllegalArgumentException("Unknown routing packet tag=$tag")
        }
    }
}
