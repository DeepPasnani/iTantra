package com.itantra.voicemesh.transport.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import com.itantra.voicemesh.messaging.NodeId
import com.itantra.voicemesh.transport.Transport
import com.itantra.voicemesh.transport.TransportListener
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * [Transport] backed by a Wi-Fi Direct group.
 *
 * IMPORTANT — read spec §5 before relying on this: a Wi-Fi Direct group is a star
 * (one owner, N clients talking only to the owner), which maps cleanly onto this
 * class's one-hop [Transport] contract — the owner sees every client as a direct
 * neighbor, a client sees only the owner. It does NOT by itself give a phone the
 * ability to bridge two different groups (be a client in one and owner of another at
 * the same time); that depends on per-chipset/OEM Wi-Fi concurrency that spec §5/§12
 * explicitly say must be verified on the target hardware, not assumed. A verified
 * bridge/relay node is modeled by giving one node's [com.itantra.voicemesh.routing.AodvRouter]
 * a [com.itantra.voicemesh.transport.CompositeTransport] wrapping two
 * [WifiDirectTransport] instances — this class itself only ever manages one group.
 *
 * This class assumes the app/UI layer has already driven Wi-Fi Direct peer discovery
 * and connection (via [discoverPeers] / [connectToPeer]); it turns whatever group
 * WifiP2pManager reports into neighbor NodeIds by running a small handshake (each
 * side sends its 4-byte [NodeId] first) over a TCP socket to/from the group owner.
 * Entirely unverified on real hardware so far — see spec §12's validation plan
 * (hotspot/client capacity, concurrency, RSSI/throughput, moving-node link stability)
 * before treating this as production-ready.
 */
class WifiDirectTransport(
    override val localNodeId: NodeId,
    private val context: Context,
) : Transport {

    private val wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = wifiP2pManager.initialize(context, context.mainLooper, null)

    private val links = ConcurrentHashMap<NodeId, PeerLink>()
    private val listeners = CopyOnWriteArrayList<TransportListener>()
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val connectExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var started = false

    /** Latest peer scan results; set by the app/UI to drive a "nearby devices" list. */
    var onPeersDiscovered: (List<WifiP2pDevice>) -> Unit = {}

    override val neighbors: Set<NodeId> get() = links.keys.toSet()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    wifiP2pManager.requestPeers(channel) { peers ->
                        onPeersDiscovered(peers.deviceList.toList())
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    wifiP2pManager.requestConnectionInfo(channel) { info -> handleConnectionInfo(info) }
                }
            }
        }
    }

    /** Registers for Wi-Fi Direct system broadcasts. Call once, e.g. from a foreground service's onCreate. */
    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, filter)
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(receiver) }
        links.values.toList().forEach { it.close() }
        links.clear()
        serverSocket?.let { runCatching { it.close() } }
        serverSocket = null
    }

    /** Requires ACCESS_FINE_LOCATION / NEARBY_WIFI_DEVICES to already be granted (spec §5 hardware caveats apply). */
    fun discoverPeers() {
        wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(reason: Int) = Unit
        })
    }

    fun connectToPeer(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        wifiP2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(reason: Int) = Unit
        })
    }

    override fun send(neighborId: NodeId, payload: ByteArray) {
        links[neighborId]?.send(payload)
    }

    override fun addListener(listener: TransportListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: TransportListener) {
        listeners.remove(listener)
    }

    private fun handleConnectionInfo(info: WifiP2pInfo) {
        if (!info.groupFormed) return
        if (info.isGroupOwner) {
            startServerSocketIfNeeded()
        } else {
            connectToGroupOwner(info.groupOwnerAddress)
        }
    }

    private fun startServerSocketIfNeeded() {
        if (serverSocket != null) return
        val socket = ServerSocket(WIFI_DIRECT_PORT)
        serverSocket = socket
        acceptExecutor.submit {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: continue
                handshakeAndRegister(client)
            }
        }
    }

    private fun connectToGroupOwner(address: InetAddress) {
        connectExecutor.submit {
            val socket = runCatching { Socket(address, WIFI_DIRECT_PORT) }.getOrNull() ?: return@submit
            handshakeAndRegister(socket)
        }
    }

    private fun handshakeAndRegister(socket: Socket) {
        val remoteId = try {
            val output = DataOutputStream(socket.getOutputStream())
            output.writeInt(localNodeId.value.toInt())
            output.flush()
            val input = DataInputStream(socket.getInputStream())
            NodeId(input.readInt().toUInt())
        } catch (_: Exception) {
            runCatching { socket.close() }
            return
        }

        val link = PeerLink(
            socket = socket,
            onFrame = { bytes -> listeners.forEach { it.onPacketReceived(remoteId, bytes) } },
            onClosed = {
                links.remove(remoteId)
                listeners.forEach { it.onNeighborLost(remoteId) }
            },
        )
        links[remoteId] = link
        link.start()
        listeners.forEach { it.onNeighborJoined(remoteId) }
    }

    companion object {
        // Arbitrary fixed application port for the group's internal socket layer.
        private const val WIFI_DIRECT_PORT = 8988
    }
}
