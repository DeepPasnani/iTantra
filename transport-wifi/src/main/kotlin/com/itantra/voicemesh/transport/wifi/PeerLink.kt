package com.itantra.voicemesh.transport.wifi

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.Executors

/**
 * One TCP connection to one directly-linked neighbor, framed as [length:4][bytes].
 * A dedicated read thread per link keeps this simple (no coroutine/NIO dependency) at
 * the node counts this is designed for (spec §8: ~20-50 nodes per domain, so at most a
 * handful of direct links per relay node in practice).
 */
internal class PeerLink(
    private val socket: Socket,
    private val onFrame: (ByteArray) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val output = DataOutputStream(socket.getOutputStream())
    private val input = DataInputStream(socket.getInputStream())
    private val writeLock = Any()
    private val readExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var closed = false

    fun start() {
        readExecutor.submit {
            try {
                while (!closed) {
                    val length = input.readInt()
                    require(length in 0..MAX_FRAME_BYTES) { "frame too large: $length" }
                    val bytes = ByteArray(length)
                    input.readFully(bytes)
                    onFrame(bytes)
                }
            } catch (_: Exception) {
                // Socket closed or peer went out of range: treated as a link break.
            } finally {
                close()
            }
        }
    }

    fun send(payload: ByteArray) {
        synchronized(writeLock) {
            if (closed) return
            try {
                output.writeInt(payload.size)
                output.write(payload)
                output.flush()
            } catch (_: Exception) {
                close()
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
        readExecutor.shutdownNow()
        onClosed()
    }

    companion object {
        // Generous bound: real messages stay in the hundreds of bytes (spec §8); this
        // just guards against a corrupt/hostile length prefix wedging the read loop.
        private const val MAX_FRAME_BYTES = 64 * 1024
    }
}
