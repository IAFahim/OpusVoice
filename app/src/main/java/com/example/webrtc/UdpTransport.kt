package com.example.webrtc

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

enum class TransportState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class NetworkTelemetry(
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val sendBitrateKbps: Double = 0.0,
    val receiveBitrateKbps: Double = 0.0,
    val targetAddress: String = "",
    val localPort: Int = 0
)

/**
 * High-performance low-latency UDP Socket transport for WebRTC RTP streaming.
 */
class UdpTransport(
    private val onPacketReceived: (RtpPacket) -> Unit,
    private val onStateChanged: (TransportState, String?) -> Unit,
    /** Reports the port actually bound — which differs from the requested one whenever it
     *  was busy and an ephemeral port was taken. Without this, a UI still showing the
     *  requested port sends peers at a port nobody listens on. */
    private val onBoundPort: ((requested: Int, bound: Int) -> Unit)? = null
) {
    companion object {
        private const val TAG = "UdpTransport"
        private const val BUFFER_SIZE = 2048
    }

    private var socket: DatagramSocket? = null
    private var sendJob: Job? = null
    private var receiveJob: Job? = null
    private var telemetryJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val sendChannel = Channel<RtpPacket>(Channel.BUFFERED)

    // Destination target
    var targetHost: String = "127.0.0.1"
    var targetPort: Int = 5004
    var localReceivePort: Int = 5004

    // Counters
    private val totalBytesSent = AtomicLong(0)
    private val totalBytesReceived = AtomicLong(0)
    private val totalPacketsSent = AtomicLong(0)
    private val totalPacketsReceived = AtomicLong(0)

    private var lastBytesSentSnapshot = 0L
    private var lastBytesReceivedSnapshot = 0L
    private var lastTimestampSnapshot = System.currentTimeMillis()

    var state: TransportState = TransportState.DISCONNECTED
        private set

    @Synchronized
    fun start(targetHost: String, targetPort: Int, localPort: Int = 5004) {
        if (state == TransportState.CONNECTED || state == TransportState.CONNECTING) {
            stop()
        }

        this.targetHost = targetHost.trim()
        this.targetPort = targetPort
        this.localReceivePort = localPort

        state = TransportState.CONNECTING
        onStateChanged(state, null)

        try {
            // Bind to local port for incoming RTP packets
            val boundSocket = try {
                DatagramSocket(localReceivePort)
            } catch (e: Exception) {
                // If local port is busy, fallback to any free port
                DatagramSocket()
            }
            boundSocket.receiveBufferSize = 64 * 1024
            boundSocket.sendBufferSize = 64 * 1024
            this.localReceivePort = boundSocket.localPort
            this.socket = boundSocket
            onBoundPort?.invoke(localPort, boundSocket.localPort)

            state = TransportState.CONNECTED
            onStateChanged(state, null)
            Log.i(TAG, "UDP Transport bound on port $localReceivePort, streaming to $targetHost:$targetPort")

            // Start Sender loop
            sendJob = scope.launch {
                val targetInet = InetAddress.getByName(this@UdpTransport.targetHost)
                val targetSocketAddress = InetSocketAddress(targetInet, this@UdpTransport.targetPort)

                for (packet in sendChannel) {
                    if (!isActive) break
                    try {
                        val packetBytes = packet.toByteArray()
                        val datagram = DatagramPacket(
                            packetBytes,
                            packetBytes.size,
                            targetSocketAddress
                        )
                        boundSocket.send(datagram)
                        totalBytesSent.addAndGet(packetBytes.size.toLong())
                        totalPacketsSent.incrementAndGet()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending UDP packet", e)
                    }
                }
            }

            // Start Receiver loop
            receiveJob = scope.launch {
                val buffer = ByteArray(BUFFER_SIZE)
                val datagram = DatagramPacket(buffer, buffer.size)

                while (isActive && !boundSocket.isClosed) {
                    try {
                        boundSocket.receive(datagram)
                        totalBytesReceived.addAndGet(datagram.length.toLong())
                        totalPacketsReceived.incrementAndGet()

                        val rtpPacket = RtpPacket.parse(
                            datagram.data,
                            datagram.offset,
                            datagram.length
                        )
                        if (rtpPacket != null) {
                            onPacketReceived(rtpPacket)
                        }
                    } catch (e: Exception) {
                        if (!boundSocket.isClosed) {
                            Log.e(TAG, "Error receiving UDP packet", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start UDP transport", e)
            state = TransportState.ERROR
            onStateChanged(state, e.localizedMessage)
        }
    }

    fun sendPacket(packet: RtpPacket) {
        if (state == TransportState.CONNECTED) {
            sendChannel.trySend(packet)
        }
    }

    fun getTelemetry(): NetworkTelemetry {
        val now = System.currentTimeMillis()
        val dtSec = maxOf(0.001, (now - lastTimestampSnapshot) / 1000.0)

        val curSent = totalBytesSent.get()
        val curRecv = totalBytesReceived.get()

        val sendBitrate = ((curSent - lastBytesSentSnapshot) * 8.0 / dtSec) / 1000.0
        val recvBitrate = ((curRecv - lastBytesReceivedSnapshot) * 8.0 / dtSec) / 1000.0

        lastBytesSentSnapshot = curSent
        lastBytesReceivedSnapshot = curRecv
        lastTimestampSnapshot = now

        return NetworkTelemetry(
            bytesSent = curSent,
            bytesReceived = curRecv,
            packetsSent = totalPacketsSent.get(),
            packetsReceived = totalPacketsReceived.get(),
            sendBitrateKbps = sendBitrate.coerceAtLeast(0.0),
            receiveBitrateKbps = recvBitrate.coerceAtLeast(0.0),
            targetAddress = "$targetHost:$targetPort",
            localPort = localReceivePort
        )
    }

    @Synchronized
    fun stop() {
        sendJob?.cancel()
        sendJob = null
        receiveJob?.cancel()
        receiveJob = null

        try {
            socket?.close()
            socket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing UDP socket", e)
        }

        state = TransportState.DISCONNECTED
        onStateChanged(state, null)
        Log.i(TAG, "UDP Transport stopped")
    }
}
