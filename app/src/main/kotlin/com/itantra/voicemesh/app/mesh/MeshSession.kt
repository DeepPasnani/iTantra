package com.itantra.voicemesh.app.mesh

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import com.itantra.voicemesh.messaging.Language
import com.itantra.voicemesh.messaging.Message
import com.itantra.voicemesh.messaging.MessageIdGenerator
import com.itantra.voicemesh.messaging.NodeId
import com.itantra.voicemesh.messaging.Priority
import com.itantra.voicemesh.app.persistence.RoomOutboxStore
import com.itantra.voicemesh.app.persistence.VoiceMeshDatabase
import com.itantra.voicemesh.app.speech.EspeakSpeechSynthesizer
import com.itantra.voicemesh.reliability.DeliveryOutcome
import com.itantra.voicemesh.reliability.ReliabilityLayer
import com.itantra.voicemesh.routing.AodvRouter
import com.itantra.voicemesh.transport.TransportListener
import com.itantra.voicemesh.transport.wifi.WifiDirectTransport

/**
 * Wires the transport/routing/reliability stack together for the app UI. This is a
 * thin composition layer, not a new abstraction: the actual logic lives in
 * :transport-wifi, :core-routing, and :core-reliability, all of which are unit-tested
 * independently of Android. What's here has NOT been run on real hardware yet (spec
 * §12 validation is still required) — treat the "connected neighbors" list and
 * delivery outcomes below as what the code claims, not as a measured result.
 */
class MeshSession(context: Context) {
    val localNodeId: NodeId = NodeIdentity.getOrCreate(context)

    private val transport = WifiDirectTransport(localNodeId, context)
    private val router = AodvRouter(localNodeId = localNodeId, transport = transport)
    private val idGenerator = MessageIdGenerator()
    private val outbox = RoomOutboxStore(VoiceMeshDatabase.getInstance(context).outboxDao())
    private val synthesizer = EspeakSpeechSynthesizer(context)

    val neighbors: SnapshotStateList<NodeId> = mutableListOf<NodeId>().toMutableStateList()
    val log: SnapshotStateList<String> = mutableListOf<String>().toMutableStateList()
    val lastOutcome = mutableStateOf<String?>(null)

    private val reliability = ReliabilityLayer(
        router = router,
        outbox = outbox,
        onMessageReceived = { message ->
            log.add(0, "RX from ${message.sourceNodeId} [${message.priority.name}]: ${message.text}")
            synthesizer.speak(message)
        },
        onDeliveryOutcome = { messageId, outcome -> lastOutcome.value = "$messageId -> $outcome" },
    )

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            router.tick()
            reliability.tick()
            handler.postDelayed(this, TICK_INTERVAL_MILLIS)
        }
    }

    fun start() {
        transport.start()
        transport.addListener(object : TransportListener {
            override fun onPacketReceived(fromNeighborId: NodeId, payload: ByteArray) = Unit
            override fun onNeighborJoined(neighborId: NodeId) {
                if (neighborId !in neighbors) neighbors.add(neighborId)
                log.add(0, "neighbor joined: $neighborId")
            }

            override fun onNeighborLost(neighborId: NodeId) {
                neighbors.remove(neighborId)
                log.add(0, "neighbor lost: $neighborId")
            }
        })
        transport.discoverPeers()
        handler.post(tickRunnable)
    }

    fun stop() {
        handler.removeCallbacks(tickRunnable)
        transport.stop()
        synthesizer.shutdown()
    }

    fun sendMessage(destination: NodeId, language: Language, priority: Priority, text: String) {
        val message = Message.create(
            idGenerator = idGenerator,
            sourceNodeId = localNodeId,
            destinationNodeId = destination,
            language = language,
            priority = priority,
            text = text,
        )
        log.add(0, "TX to $destination [${priority.name}]: $text")
        reliability.sendMessage(message)
    }

    companion object {
        private const val TICK_INTERVAL_MILLIS = 1_000L
    }
}
