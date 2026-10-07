/**
 * Voice streaming: mic (or a 440 Hz test tone) → WebCodecs Opus encoder →
 * RTP packet → WebSocket → the receiver's `ws` bridge, which feeds the exact
 * same sink as the Android app's UDP stream. An optional monitor round-trips
 * our own encoded bytes through the decoder so the codec path is audible.
 */
import { RtpPacketizer, SAMPLES_PER_FRAME } from './rtp'

export interface VoiceStats {
  state: 'idle' | 'connecting' | 'streaming'
  packets: number
  bytes: number
  dropped: number
  seconds: number
}

export interface VoiceOptions {
  url: string // ws://host:port[/path]
  source: 'mic' | 'tone'
  bitrate: number
  monitor: boolean
  onStats: (stats: VoiceStats) => void
  onError: (message: string) => void
}

const CAPTURE_WORKLET = `
class CaptureProcessor extends AudioWorkletProcessor {
  constructor() {
    super()
    this.frame = new Float32Array(${SAMPLES_PER_FRAME})
    this.filled = 0
  }
  process(inputs) {
    const ch = inputs[0] && inputs[0][0]
    if (!ch) return true
    for (let i = 0; i < ch.length; i++) {
      this.frame[this.filled++] = ch[i]
      if (this.filled === this.frame.length) {
        this.port.postMessage(this.frame.slice(0))
        this.filled = 0
      }
    }
    return true
  }
}
registerProcessor('capture-processor', CaptureProcessor)
`

const PLAYER_WORKLET = `
class PlayerProcessor extends AudioWorkletProcessor {
  constructor() {
    super()
    this.ring = new Float32Array(48000 * 2) // 2 seconds of jitter headroom
    this.read = 0
    this.write = 0
    this.port.onmessage = e => {
      const data = e.data
      for (let i = 0; i < data.length; i++) {
        this.ring[this.write] = data[i]
        this.write = (this.write + 1) % this.ring.length
        if (this.write === this.read) this.read = (this.read + 1) % this.ring.length
      }
    }
  }
  process(_, outputs) {
    const out = outputs[0] && outputs[0][0]
    if (!out) return true
    for (let i = 0; i < out.length; i++) {
      if (this.read !== this.write) {
        out[i] = this.ring[this.read]
        this.read = (this.read + 1) % this.ring.length
      } else {
        out[i] = 0
      }
    }
    return true
  }
}
registerProcessor('player-processor', PlayerProcessor)
`

export function webCodecsSupported(): boolean {
  return typeof AudioEncoder !== 'undefined' && typeof AudioDecoder !== 'undefined' && typeof AudioData !== 'undefined'
}

/** lib.dom lacks EncodedAudioChunk.close(); the runtime API has it — callers own the handle. */
function closeChunk(chunk: EncodedAudioChunk): void {
  ;(chunk as EncodedAudioChunk & { close?: () => void }).close?.()
}

export class VoiceStreamer {
  private stats: VoiceStats = { state: 'idle', packets: 0, bytes: 0, dropped: 0, seconds: 0 }
  private ws: WebSocket | null = null
  private context: AudioContext | null = null
  private stream: MediaStream | null = null
  private captureNode: AudioWorkletNode | null = null
  private playerNode: AudioWorkletNode | null = null
  private encoder: AudioEncoder | null = null
  private decoder: AudioDecoder | null = null
  private readonly packetizer = new RtpPacketizer()
  private toneTimer = 0
  private toneDue = 0
  private statsTimer = 0
  private startedAt = 0
  private samplesSent = 0
  private tonePhase = 0
  /** Bumped on every start/stop; async continuations from an older session must not resurrect it. */
  private epoch = 0
  private wsErrored = false

  constructor(private readonly options: VoiceOptions) {}

  async start(): Promise<void> {
    if (!webCodecsSupported()) {
      throw new Error('This browser has no WebCodecs audio support — voice needs Chrome or Edge.')
    }
    const epoch = ++this.epoch
    this.wsErrored = false
    this.update({ state: 'connecting' })
    this.ws = new WebSocket(this.options.url)
    this.ws.binaryType = 'arraybuffer'
    this.ws.onopen = () => {
      if (epoch === this.epoch) void this.beginAudio(epoch)
    }
    this.ws.onclose = () => {
      if (this.stats.state !== 'idle') {
        this.stop()
        if (!this.wsErrored) this.options.onError('receiver closed the connection')
      }
    }
    this.ws.onerror = () => {
      this.wsErrored = true
      this.options.onError('WebSocket error — is the receiver running in ws mode?')
    }
  }

  private async beginAudio(epoch: number): Promise<void> {
    try {
      const stale = () => epoch !== this.epoch || this.stats.state === 'idle'
      this.context = new AudioContext({ sampleRate: 48000 })

      this.encoder = new AudioEncoder({
        output: chunk => this.sendChunk(chunk),
        error: e => this.options.onError(`encoder: ${e.message}`)
      })
      this.encoder.configure({
        codec: 'opus',
        sampleRate: 48000,
        numberOfChannels: 1,
        bitrate: this.options.bitrate
      })

      if (this.options.monitor) {
        this.decoder = new AudioDecoder({
          output: audioData => {
            const samples = new Float32Array(audioData.numberOfFrames)
            audioData.copyTo(samples, { planeIndex: 0, format: 'f32' })
            audioData.close()
            this.playerNode?.port.postMessage(samples)
          },
          error: e => this.options.onError(`decoder: ${e.message}`)
        })
        this.decoder.configure({ codec: 'opus', sampleRate: 48000, numberOfChannels: 1 })
        await this.addWorklet(PLAYER_WORKLET)
        if (stale()) return
        this.playerNode = new AudioWorkletNode(this.context, 'player-processor', { outputChannelCount: [1] })
        this.playerNode.connect(this.context.destination)
      }

      if (this.options.source === 'mic') {
        this.stream = await navigator.mediaDevices.getUserMedia({
          audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
        })
        if (stale()) {
          this.stream.getTracks().forEach(t => t.stop())
          this.stream = null
          return
        }
        const source = this.context.createMediaStreamSource(this.stream)
        await this.addWorklet(CAPTURE_WORKLET)
        if (stale()) return
        this.captureNode = new AudioWorkletNode(this.context, 'capture-processor', { numberOfOutputs: 0 })
        this.captureNode.port.onmessage = e => this.frame(e.data as Float32Array<ArrayBuffer>)
        source.connect(this.captureNode)
      } else {
        // Self-scheduling, wall-clock-anchored pacing: no drift, and a background
        // tab degrades to dropped frames instead of a burst of catch-up audio.
        this.toneDue = performance.now() + 20
        this.toneTimer = window.setTimeout(this.toneTick, 20)
      }

      this.startedAt = performance.now()
      this.statsTimer = window.setInterval(() => this.update({ seconds: (performance.now() - this.startedAt) / 1000 }), 250)
      this.update({ state: 'streaming' })
    } catch (e) {
      this.stop()
      this.options.onError(e instanceof Error ? e.message : String(e))
    }
  }

  private readonly toneTick = (): void => {
    if (!this.toneTimer) return
    this.frame(this.toneFrame())
    const now = performance.now()
    // Never chase more than two missed frames; late frames are dropped, not piled up.
    this.toneDue = Math.max(this.toneDue + 20, now - 40)
    this.toneTimer = window.setTimeout(this.toneTick, Math.max(0, this.toneDue - now))
  }

  /** Loads a worklet module from a blob URL and revokes the URL immediately after. */
  private async addWorklet(code: string): Promise<void> {
    const url = URL.createObjectURL(new Blob([code], { type: 'application/javascript' }))
    try {
      await this.context?.audioWorklet.addModule(url)
    } finally {
      URL.revokeObjectURL(url)
    }
  }

  /** 440 Hz sine at a safe level, continuous across frames — a headless-test-friendly source. */
  private toneFrame(): Float32Array<ArrayBuffer> {
    const frame = new Float32Array(SAMPLES_PER_FRAME)
    const w = (2 * Math.PI * 440) / 48000
    for (let i = 0; i < frame.length; i++) {
      frame[i] = Math.sin(this.tonePhase) * 0.2
      this.tonePhase += w
    }
    return frame
  }

  private frame(samples: Float32Array<ArrayBuffer>): void {
    if (!this.encoder || this.encoder.state !== 'configured') return
    // If the encoder is backed up, drop the frame instead of adding latency.
    if (this.encoder.encodeQueueSize > 64) {
      this.update({ dropped: this.stats.dropped + 1 })
      return
    }
    const data = new AudioData({
      format: 'f32-planar',
      sampleRate: 48000,
      numberOfFrames: samples.length,
      numberOfChannels: 1,
      timestamp: (this.samplesSent * 1_000_000) / 48,
      data: samples
    })
    this.samplesSent += samples.length
    this.encoder.encode(data)
    data.close() // the spec makes the caller own AudioData lifetime
  }

  private sendChunk(chunk: EncodedAudioChunk): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      closeChunk(chunk)
      return
    }
    // Backpressure guard: if the socket is far behind, drop the frame rather than grow latency.
    if (this.ws.bufferedAmount > 64_000) {
      this.update({ dropped: this.stats.dropped + 1 })
      closeChunk(chunk)
      return
    }
    const payload = new Uint8Array(chunk.byteLength)
    chunk.copyTo(payload)
    closeChunk(chunk)
    if (this.decoder && this.decoder.state === 'configured') {
      this.decoder.decode(new EncodedAudioChunk({ type: 'key', timestamp: chunk.timestamp, data: payload }))
    }
    const packet = this.packetizer.packet(payload)
    this.ws.send(packet)
    this.update({ packets: this.stats.packets + 1, bytes: this.stats.bytes + packet.byteLength })
  }

  private update(patch: Partial<VoiceStats>): void {
    this.stats = { ...this.stats, ...patch }
    this.options.onStats(this.stats)
  }

  stop(): void {
    if (this.stats.state === 'idle') return
    this.epoch++ // invalidate any in-flight start() continuation
    window.clearTimeout(this.toneTimer)
    window.clearInterval(this.statsTimer)
    this.toneTimer = 0
    this.statsTimer = 0
    try {
      this.encoder?.close()
      this.decoder?.close()
    } catch {
      /* already closed */
    }
    this.encoder = null
    this.decoder = null
    this.captureNode?.disconnect()
    this.captureNode = null
    this.playerNode?.disconnect()
    this.playerNode = null
    this.stream?.getTracks().forEach(t => t.stop())
    this.stream = null
    void this.context?.close().catch(() => undefined)
    this.context = null
    const ws = this.ws
    this.ws = null
    if (ws) {
      ws.onopen = null
      if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) ws.close(1000, 'stopped')
    }
    this.update({ state: 'idle' })
  }
}

/** True when the page (https) may not open this ws:// URL — anything but localhost. */
export function mixedContentBlocked(url: string, pageIsHttps: boolean): boolean {
  if (!pageIsHttps) return false
  try {
    const parsed = new URL(url)
    if (parsed.protocol === 'wss:') return false
    if (parsed.protocol !== 'ws:') return false
    return !(parsed.hostname === 'localhost' || parsed.hostname === '127.0.0.1' || parsed.hostname === '[::1]')
  } catch {
    return false
  }
}
