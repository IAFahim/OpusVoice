/**
 * Web twin of the Android app's QR router:
 *  - QrPayload router: app/src/main/java/com/example/qr/QrPayload.kt
 *  - connection-string grammar: pinhole/src/main/kotlin/pinhole/ConnectionString.kt
 *
 * Every QR-enabled feature grows a branch in parseQrPayload; the scanner stays
 * format-agnostic and hands over whatever the code contained.
 */

export const SCHEME = 'pinhole1'
export const MAX_CANDIDATES = 32
export const MAX_LENGTH = 8192
export const KEY_LENGTH = 32

/** NAT classification hint embedded by the publisher; symmetric means a direct punch is hopeless. */
export type NatHint = 'unknown' | 'cone' | 'symmetric'
export type CandidateKind = 'direct' | 'reflexive' | 'relay' | 'iroh-relay'

/** One dialable address. The minimal dialer punches direct and reflexive candidates. */
export interface Candidate {
  kind: CandidateKind
  host: string
  port: number
}

export interface ConnectionString {
  version: 1 | 2 | 3
  peerId: bigint
  natHint: NatHint
  candidates: Candidate[]
  staticKey: Uint8Array | null
  endpointKey: Uint8Array | null
}

export type QrPayload =
  | { kind: 'pinhole-ticket'; ticket: string; parsed: ConnectionString }
  | { kind: 'udp-endpoint'; host: string; port: number }
  | { kind: 'unknown'; text: string }

class Reader {
  private pos = 0
  constructor(private readonly data: Uint8Array) {}

  byte(): number {
    if (this.pos >= this.data.length) throw new Error('payload is truncated')
    return this.data[this.pos++]!
  }

  u64Le(): bigint {
    if (this.pos + 8 > this.data.length) throw new Error('payload is truncated')
    let value = 0n
    for (let i = 7; i >= 0; i--) value = (value << 8n) | BigInt(this.data[this.pos + i])
    this.pos += 8
    return value
  }

  endpoint(): { host: string; port: number } {
    const family = this.byte()
    if (family !== 4 && family !== 16) throw new Error('unknown address family')
    if (this.pos + family + 2 > this.data.length) throw new Error('payload is truncated')
    const raw = this.data.slice(this.pos, this.pos + family)
    this.pos += family
    const port = (this.byte() << 8) | this.byte()
    return { host: formatAddress(raw), port }
  }

  shortText(): string {
    const length = this.byte()
    if (length > 64 || this.pos + length > this.data.length) throw new Error('payload is truncated')
    let text = ''
    for (let i = 0; i < length; i++) text += String.fromCharCode(this.data[this.pos + i])
    this.pos += length
    return text
  }

  bytes(count: number): Uint8Array {
    if (this.pos + count > this.data.length) throw new Error('payload is truncated')
    const out = this.data.slice(this.pos, this.pos + count)
    this.pos += count
    return out
  }

  get atEnd(): boolean {
    return this.pos === this.data.length
  }

  get remaining(): number {
    return this.data.length - this.pos
  }
}

function formatIpv6(bytes: Uint8Array): string {
  const groups: number[] = []
  for (let i = 0; i < 16; i += 2) groups.push((bytes[i]! << 8) | bytes[i + 1]!)
  // Compress the longest run (>= 2) of zero groups into "::", like InetAddress.getHostAddress.
  let bestStart = -1
  let bestLen = 0
  let i = 0
  while (i < 8) {
    if (groups[i] === 0) {
      let j = i
      while (j < 8 && groups[j] === 0) j++
      if (j - i > bestLen) {
        bestLen = j - i
        bestStart = i
      }
      i = j
    } else {
      i++
    }
  }
  if (bestLen < 2) return groups.map(g => g.toString(16)).join(':')
  const head = groups.slice(0, bestStart).map(g => g.toString(16))
  const tail = groups.slice(bestStart + bestLen).map(g => g.toString(16))
  return `${head.join(':')}::${tail.join(':')}`
}

function formatAddress(bytes: Uint8Array): string {
  return bytes.length === 4 ? Array.from(bytes, b => b.toString()).join('.') : formatIpv6(bytes)
}

const NAT_HINTS: Record<number, NatHint> = { 0: 'unknown', 1: 'cone', 2: 'symmetric' }
const CANDIDATE_KINDS: Record<number, CandidateKind> = { 1: 'direct', 2: 'reflexive', 3: 'relay', 4: 'iroh-relay' }

/** Strict base64url charset, padding tolerated; anything else is malformed. */
function decodeBase64Url(text: string): Uint8Array {
  for (const c of text) {
    const ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c === '-' || c === '_' || c === '='
    if (!ok) throw new Error('payload is not valid base64url')
  }
  const pad = (4 - (text.length % 4)) % 4
  const base64 = text.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat(pad)
  const binary = atob(base64)
  const out = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i)
  return out
}

/**
 * "pinhole1:<base64url>" — a peer's stable id, NAT hint, candidate addresses and (v2/v3)
 * its pinned X25519 static key. Wire-compatible with Pinhole.Net's ConnectionString.
 * Throws on malformed input; lenient about surrounding whitespace and a missing prefix.
 */
export function parseConnectionString(text: string): ConnectionString {
  if (text.length > MAX_LENGTH) throw new Error(`connection string exceeds ${MAX_LENGTH} characters`)
  let t = text.trim()
  if (!t.includes(':')) t = `${SCHEME}:${t}`
  const colon = t.indexOf(':')
  if (!(colon > 0 && t.slice(0, colon) === SCHEME)) throw new Error(`expected a "${SCHEME}:" connection string`)

  const payload = decodeBase64Url(t.slice(colon + 1))
  if (payload.length < 13) throw new Error('payload too short')
  const version = payload[0]!
  if (version !== 1 && version !== 2 && version !== 3) throw new Error('unsupported connection string version')
  const flags = payload[1]!
  const expectedFlags = version === 2 ? 1 : version === 3 ? 3 : 0
  if (flags !== expectedFlags) throw new Error('unknown connection string flags')

  const reader = new Reader(payload.subarray(2))
  const peerId = reader.u64Le()
  const natHint = NAT_HINTS[reader.byte()]
  if (!natHint) throw new Error('unknown NAT hint')
  const count = reader.byte()
  if (count > MAX_CANDIDATES) throw new Error(`connection string carries more than ${MAX_CANDIDATES} candidates`)

  const candidates: Candidate[] = []
  for (let n = 0; n < count; n++) {
    const address = reader.endpoint()
    const kind = CANDIDATE_KINDS[reader.byte()]
    if (!kind) throw new Error('unknown candidate kind')
    if (kind === 'relay') {
      reader.endpoint() // relay server
      reader.shortText() // username
      reader.shortText() // credential
    } else if (kind === 'iroh-relay') {
      reader.shortText() // relay URL
      reader.bytes(KEY_LENGTH) // relay public key
    }
    candidates.push({ kind, host: address.host, port: address.port })
  }

  let staticKey: Uint8Array | null = null
  let endpointKey: Uint8Array | null = null
  if (version === 2) {
    if (reader.remaining !== KEY_LENGTH) throw new Error('v2 string must end with a 32-byte static key')
    staticKey = reader.bytes(KEY_LENGTH)
  } else if (version === 3) {
    if (reader.remaining !== 2 * KEY_LENGTH) throw new Error('v3 string must end with static and endpoint keys')
    staticKey = reader.bytes(KEY_LENGTH)
    endpointKey = reader.bytes(KEY_LENGTH)
  } else if (!reader.atEnd) {
    throw new Error('trailing bytes in connection string')
  }

  return { version, peerId, natHint, candidates, staticKey, endpointKey }
}

/** parse() for untrusted input (QR codes, paste): null instead of a throw. */
export function tryParseConnectionString(text: string): ConnectionString | null {
  try {
    return parseConnectionString(text)
  } catch {
    return null
  }
}

/** Turns raw QR text into the first matching typed payload. */
export function parseQrPayload(raw: string): QrPayload {
  const text = raw.trim()
  const parsed = tryParseConnectionString(text)
  if (parsed) return { kind: 'pinhole-ticket', ticket: text, parsed }
  const udp = parseUdpEndpoint(text)
  if (udp) return { kind: 'udp-endpoint', host: udp.host, port: udp.port }
  return { kind: 'unknown', text }
}

function parseUdpEndpoint(text: string): { host: string; port: number } | null {
  if (!text.toLowerCase().startsWith('udp://')) return null
  const body = text.slice('udp://'.length)
  const port = Number.parseInt(body.slice(body.lastIndexOf(':') + 1), 10)
  if (!Number.isInteger(port) || port < 1 || port > 65535) return null
  const hostPart = body.slice(0, body.lastIndexOf(':'))
  if (!hostPart) return null
  const bracketed = hostPart.startsWith('[') && hostPart.endsWith(']')
  const host = bracketed ? hostPart.slice(1, -1) : hostPart
  if (!host) return null
  if (!bracketed && host.includes(':')) return null // unbracketed IPv6 is ambiguous: refuse
  return { host, port }
}
