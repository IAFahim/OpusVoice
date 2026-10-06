package com.example.webrtc

import com.example.audio.AudioConfig
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Real-time Adaptive Jitter Buffer implementing RFC 3550 guidelines for WebRTC audio playout.
 *
 * Responsibilities:
 * 1. Absorbs network delay variation (jitter) over UDP transport.
 * 2. Reorders out-of-order RTP packets.
 * 3. Calculates inter-arrival jitter in real time using the RFC 3550 algorithm:
 *    D(i,j) = (R_j - R_i) - (S_j - S_i)
 *    J = J + (|D| - J) / 16
 * 4. Adaptively adjusts target buffer delay to balance low latency with smooth playout.
 * 5. Detects lost packets and signals Packet Loss Concealment (PLC).
 */
class JitterBuffer(
    var adaptiveEnabled: Boolean = true,
    var manualBufferDepthMs: Int = 60
) {
    // Priority map sorted by unwrapped sequence number
    private val packetQueue = ConcurrentSkipListMap<Long, RtpPacket>()

    // Sequence number un-wrapping (handles 16-bit 0..65535 rollover)
    private var maxSeqSeen: Int = -1
    private var seqEpoch: Long = 0
    private var lastPlayoutUnwrappedSeq: Long = -1

    // RFC 3550 Jitter estimation state
    private var lastArrivalTimestampMs: Long = 0
    private var lastRtpTimestamp: Long = 0
    private var jitterEstimateSamples: Double = 0.0 // in 48kHz units

    // Telemetry & metrics
    private val totalPacketsReceived = AtomicLong(0)
    private val totalPacketsLost = AtomicLong(0)
    private val totalPacketsLate = AtomicLong(0)
    private val totalPacketsDuplicates = AtomicLong(0)
    private var consecutiveLostCount: Int = 0

    // Timing
    private var playoutStartTimeMs: Long = 0
    private var firstPacketTimestamp: Long = 0

    @Synchronized
    fun reset() {
        packetQueue.clear()
        maxSeqSeen = -1
        seqEpoch = 0
        lastPlayoutUnwrappedSeq = -1
        lastArrivalTimestampMs = 0
        lastRtpTimestamp = 0
        jitterEstimateSamples = 0.0
        totalPacketsReceived.set(0)
        totalPacketsLost.set(0)
        totalPacketsLate.set(0)
        totalPacketsDuplicates.set(0)
        consecutiveLostCount = 0
        playoutStartTimeMs = 0
        firstPacketTimestamp = 0
    }

    /**
     * Pushes an incoming RTP packet from UDP network into the jitter buffer.
     */
    @Synchronized
    fun push(packet: RtpPacket) {
        val arrivalMs = System.currentTimeMillis()
        totalPacketsReceived.incrementAndGet()

        // 1. Calculate RFC 3550 Inter-arrival Jitter
        if (lastArrivalTimestampMs != 0L) {
            // Convert arrival delta to 48kHz audio sample units:
            // deltaArrivalSamples = deltaArrivalMs * 48
            val deltaArrivalSamples = (arrivalMs - lastArrivalTimestampMs) * (AudioConfig.SAMPLE_RATE / 1000)
            val deltaRtpSamples = packet.timestamp - lastRtpTimestamp

            // D(i, j) = arrival_diff - send_diff
            val delayVariation = abs(deltaArrivalSamples - deltaRtpSamples)
            // J = J + (|D| - J) / 16
            jitterEstimateSamples += (delayVariation - jitterEstimateSamples) / 16.0
        }
        lastArrivalTimestampMs = arrivalMs
        lastRtpTimestamp = packet.timestamp

        // 2. Sequence number un-wrapping
        val unwrappedSeq = unwrapSequenceNumber(packet.sequenceNumber)

        // Drop late packet if already played
        if (lastPlayoutUnwrappedSeq != -1L && unwrappedSeq <= lastPlayoutUnwrappedSeq) {
            totalPacketsLate.incrementAndGet()
            return
        }

        // Duplicate check
        if (packetQueue.containsKey(unwrappedSeq)) {
            totalPacketsDuplicates.incrementAndGet()
            return
        }

        packetQueue[unwrappedSeq] = packet

        // Initialize playout timing clock if first packet
        if (playoutStartTimeMs == 0L) {
            playoutStartTimeMs = arrivalMs
            firstPacketTimestamp = packet.timestamp
        }
    }

    /**
     * Unwraps 16-bit sequence number (0..65535) into a monotonically increasing 64-bit integer.
     */
    private fun unwrapSequenceNumber(seq: Int): Long {
        if (maxSeqSeen == -1) {
            maxSeqSeen = seq
            return seq.toLong()
        }

        val delta = (seq - maxSeqSeen + 65536) % 65536
        if (delta < 32768) {
            // Sequence number moved forward
            if (seq < maxSeqSeen) {
                seqEpoch += 65536
            }
            maxSeqSeen = seq
        }
        return seqEpoch + seq
    }

    /**
     * Retrieves the next packet ready for audio rendering, or null if buffering.
     * If an expected packet is past deadline, advances queue and signals packet loss.
     */
    @Synchronized
    fun pollNextPlayout(): PlayoutResult {
        if (packetQueue.isEmpty()) {
            return PlayoutResult.Empty
        }

        val targetBufferDepthMs = getEffectiveBufferDepthMs()
        val now = System.currentTimeMillis()
        val elapsedSinceStart = now - playoutStartTimeMs

        // If buffer has not filled enough for initial target depth, wait
        if (elapsedSinceStart < targetBufferDepthMs && packetQueue.size < 2) {
            return PlayoutResult.Buffering
        }

        val firstEntry = packetQueue.firstEntry() ?: return PlayoutResult.Empty
        val unwrappedSeq = firstEntry.key
        val packet = firstEntry.value

        // Check if next in sequence
        val expectedSeq = if (lastPlayoutUnwrappedSeq == -1L) unwrappedSeq else lastPlayoutUnwrappedSeq + 1

        if (unwrappedSeq > expectedSeq) {
            // Gap detected! Check if gap is due to missing packet or still in transit
            val packetWaitTimeMs = now - packet.arrivalTimestampMs
            if (packetWaitTimeMs < targetBufferDepthMs && consecutiveLostCount < 3) {
                // Wait briefly for missing packet to arrive
                return PlayoutResult.WaitingForRetransmit
            } else {
                // Missing packet is lost: increment loss counter and advance sequence
                val lostCount = (unwrappedSeq - expectedSeq).toInt()
                totalPacketsLost.addAndGet(lostCount.toLong())
                consecutiveLostCount += lostCount
                lastPlayoutUnwrappedSeq = unwrappedSeq
                packetQueue.remove(unwrappedSeq)
                return PlayoutResult.PacketWithLoss(packet, lostCount)
            }
        }

        // Normal ordered playout
        consecutiveLostCount = 0
        lastPlayoutUnwrappedSeq = unwrappedSeq
        packetQueue.remove(unwrappedSeq)
        return PlayoutResult.PacketReady(packet)
    }

    /**
     * Current jitter calculated in milliseconds.
     */
    fun getJitterMs(): Double {
        // Samples @ 48kHz to milliseconds: samples / 48
        return (jitterEstimateSamples / (AudioConfig.SAMPLE_RATE / 1000.0)).coerceAtLeast(0.0)
    }

    /**
     * Effective target playout delay.
     * WebRTC standard adapts buffer depth to approx 2.5x - 3x measured network jitter + 20ms base.
     */
    fun getEffectiveBufferDepthMs(): Int {
        return if (adaptiveEnabled) {
            val estimatedJitter = getJitterMs()
            val adaptiveDepth = (20 + estimatedJitter * 2.8).toInt()
            adaptiveDepth.coerceIn(20, 240)
        } else {
            manualBufferDepthMs.coerceIn(20, 300)
        }
    }

    fun getPacketLossRatePercent(): Double {
        val received = totalPacketsReceived.get()
        val lost = totalPacketsLost.get()
        val totalExpected = received + lost
        if (totalExpected <= 0) return 0.0
        return ((lost.toDouble() / totalExpected) * 100.0).coerceIn(0.0, 100.0)
    }

    fun getSnapshot(): JitterBufferStats {
        val depthMs = getEffectiveBufferDepthMs()
        val jitterMs = getJitterMs()
        val lossPercent = getPacketLossRatePercent()
        return JitterBufferStats(
            queuedPackets = packetQueue.size,
            bufferDepthMs = depthMs,
            jitterMs = jitterMs,
            totalReceived = totalPacketsReceived.get(),
            totalLost = totalPacketsLost.get(),
            totalLate = totalPacketsLate.get(),
            lossRatePercent = lossPercent
        )
    }

    sealed class PlayoutResult {
        object Empty : PlayoutResult()
        object Buffering : PlayoutResult()
        object WaitingForRetransmit : PlayoutResult()
        data class PacketReady(val packet: RtpPacket) : PlayoutResult()
        data class PacketWithLoss(val packet: RtpPacket, val lostCount: Int) : PlayoutResult()
    }
}

data class JitterBufferStats(
    val queuedPackets: Int,
    val bufferDepthMs: Int,
    val jitterMs: Double,
    val totalReceived: Long,
    val totalLost: Long,
    val totalLate: Long,
    val lossRatePercent: Double
)
