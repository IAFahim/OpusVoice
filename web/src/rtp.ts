/**
 * Minimal RFC 3550 RTP packetizer — exactly the header the OpusVoice stack uses
 * (Android app's AudioRecorder.kt and the C# receiver's RtpHeader.cs):
 * 12 bytes, version 2, no extensions/CSRCs, payload type 111 (WebRTC Opus),
 * big-endian sequence/timestamp/SSRC, timestamp advancing 960 ticks per 20 ms
 * frame at 48 kHz. The receiver strips this header and keeps the Opus payload.
 */
export const RTP_HEADER_BYTES = 12
export const OPUS_PAYLOAD_TYPE = 111
export const SAMPLES_PER_FRAME = 960

export class RtpPacketizer {
  private seq: number
  private timestamp: number
  private readonly ssrc: number

  constructor(readonly payloadType = OPUS_PAYLOAD_TYPE, seed?: { seq: number; timestamp: number; ssrc: number }) {
    this.seq = seed?.seq ?? Math.floor(Math.random() * 0x10000)
    this.timestamp = seed?.timestamp ?? Math.floor(Math.random() * 0x100000000)
    this.ssrc = seed?.ssrc ?? Math.floor(Math.random() * 0x100000000)
  }

  /** Wraps one Opus frame in an RTP packet and advances seq/timestamp. */
  packet(payload: Uint8Array, timestampIncrement = SAMPLES_PER_FRAME): Uint8Array {
    const out = new Uint8Array(RTP_HEADER_BYTES + payload.length)
    out[0] = 0x80 // V = 2, P = X = CC = 0
    out[1] = this.payloadType & 0x7f
    out[2] = (this.seq >> 8) & 0xff
    out[3] = this.seq & 0xff
    const ts = this.timestamp >>> 0
    out[4] = (ts >>> 24) & 0xff
    out[5] = (ts >>> 16) & 0xff
    out[6] = (ts >>> 8) & 0xff
    out[7] = ts & 0xff
    const ssrc = this.ssrc >>> 0
    out[8] = (ssrc >>> 24) & 0xff
    out[9] = (ssrc >>> 16) & 0xff
    out[10] = (ssrc >>> 8) & 0xff
    out[11] = ssrc & 0xff
    out.set(payload, RTP_HEADER_BYTES)
    this.seq = (this.seq + 1) & 0xffff
    this.timestamp = (this.timestamp + timestampIncrement) >>> 0
    return out
  }
}
