import { describe, expect, it } from 'vitest'
import { parseConnectionString, parseQrPayload, tryParseConnectionString } from '../src/payload'

// The same vector the Android app tests against (QrPayloadTest.kt).
const TICKET =
  'pinhole1:AwPvzauJZ0UjAQECBMCoASrIIgEEywBxB8giAgECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gZWZnaGlqa2xtbm9wcXJzdHV2d3h5ent8fX5-gIGCg4Q'

describe('pinhole tickets', () => {
  it('is recognized and parsed', () => {
    const payload = parseQrPayload(TICKET)
    expect(payload.kind).toBe('pinhole-ticket')
    if (payload.kind !== 'pinhole-ticket') return
    expect(payload.ticket).toBe(TICKET)
    expect(payload.parsed.peerId).toBe(0x0123456789abcdefn)
    expect(payload.parsed.natHint).toBe('cone')
    expect(payload.parsed.candidates.length).toBeGreaterThan(0)
    for (const candidate of payload.parsed.candidates) {
      expect(candidate.host.length).toBeGreaterThan(0)
      expect(candidate.port).toBeGreaterThanOrEqual(0)
      expect(candidate.port).toBeLessThanOrEqual(65535)
    }
  })

  it('tolerates surrounding whitespace and a missing prefix', () => {
    expect(parseQrPayload(`  ${TICKET}  `).kind).toBe('pinhole-ticket')
    expect(parseQrPayload(TICKET.replace('pinhole1:', '')).kind).toBe('pinhole-ticket')
  })

  it('rejects corrupted tickets as unknown', () => {
    expect(parseQrPayload('pinhole1:not-a-real-ticket').kind).toBe('unknown')
    // Valid base64url charset, but truncated payload.
    expect(parseQrPayload('pinhole1:AAAA').kind).toBe('unknown')
  })
})

describe('udp endpoints', () => {
  it('parses host, bracketed IPv6 and uppercase schemes', () => {
    expect(parseQrPayload('udp://192.168.1.42:5004')).toEqual({ kind: 'udp-endpoint', host: '192.168.1.42', port: 5004 })
    expect(parseQrPayload('udp://[fe80::1]:5004')).toEqual({ kind: 'udp-endpoint', host: 'fe80::1', port: 5004 })
    expect(parseQrPayload('UDP://stream.lan:65535')).toEqual({ kind: 'udp-endpoint', host: 'stream.lan', port: 65535 })
  })

  it('falls through to unknown on malformed uris', () => {
    expect(parseQrPayload('udp://host:notaport').kind).toBe('unknown')
    expect(parseQrPayload('udp://:5004').kind).toBe('unknown')
    expect(parseQrPayload('udp://host:0').kind).toBe('unknown')
    expect(parseQrPayload('udp://host:70000').kind).toBe('unknown')
    // Unbracketed IPv6 is ambiguous and must be refused.
    expect(parseQrPayload('udp://fe80::1:5004').kind).toBe('unknown')
  })
})

describe('arbitrary text', () => {
  it('stays unknown with its content intact', () => {
    expect(parseQrPayload('https://example.com/call').kind).toBe('unknown')
    const payload = parseQrPayload('hello world')
    expect(payload).toEqual({ kind: 'unknown', text: 'hello world' })
  })
})

describe('ws endpoints', () => {
  it('parses the receiver ws bridge QR', () => {
    expect(parseQrPayload('ws://192.168.1.42:8080/stream')).toEqual({
      kind: 'ws-endpoint',
      url: 'ws://192.168.1.42:8080/stream',
      host: '192.168.1.42',
      port: 8080
    })
    expect(parseQrPayload('WSS://example.com:9000/stream')).toMatchObject({ kind: 'ws-endpoint', port: 9000 })
  })

  it('rejects ws urls without a usable host:port', () => {
    expect(parseQrPayload('ws:///stream').kind).toBe('unknown')
    expect(parseQrPayload('ws://host/stream').kind).toBe('unknown') // no port
    expect(parseQrPayload('http://host:8080/stream').kind).toBe('unknown')
  })
})

describe('connection string internals', () => {
  it('tryParse returns null instead of throwing', () => {
    expect(tryParseConnectionString('pinhole1:zzz')).toBeNull()
    expect(tryParseConnectionString(TICKET)).not.toBeNull()
  })

  it('round-trips a hand-built v1 string with an IPv6 candidate', () => {
    // version 1, flags 0, peerId 1 (LE), natHint symmetric(2), 1 candidate:
    // family 16 + 16 address bytes + port 4400 (BE) + kind direct(1)
    const address = [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01]
    const payload = [1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 2, 1, 16, ...address, 0x11, 0x30, 1]
    const b64 = btoa(String.fromCharCode(...payload))
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '')
    const parsed = parseConnectionString(`pinhole1:${b64}`)
    expect(parsed.peerId).toBe(1n)
    expect(parsed.natHint).toBe('symmetric')
    expect(parsed.candidates).toEqual([{ kind: 'direct', host: '2001:db8::1', port: 4400 }])
    expect(parsed.staticKey).toBeNull()
    expect(parsed.endpointKey).toBeNull()
  })
})
