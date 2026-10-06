# OpusVoice QR (desktop)

An Avalonia (.NET 10) desktop companion to the OpusVoice Android app: it replicates the
Android QR discovery system — decoding QR codes that carry Pinhole connection strings and
`udp://` targets, routing them through the same typed payload parser, rendering QR codes from
any text, and keeping scan history — and adds the voice streaming half of the app: Opus audio
captured from the microphone or a built-in 440 Hz test tone, framed as RTP and sent over UDP.

## Layout

```
desktop/
  OpusVoice.Qr.sln
  src/OpusVoice.Qr.Core/         payload router + connection-string parser + RTP framing (no UI deps)
  src/OpusVoice.Qr.Audio/        pure-C# Opus codec wrapper + test-tone generator (Concentus)
  src/OpusVoice.Qr/              the Avalonia 11.3 app (Scan / Generate / Voice / History tabs)
  tests/OpusVoice.Qr.Core.Tests/ xUnit tests: the six Android parity tests plus parser, RTP,
                                 tone and codec coverage
```

## Run

```bash
dotnet run --project src/OpusVoice.Qr        # from this directory
dotnet run --project src/OpusVoice.Qr -c Release
```

Build and test:

```bash
dotnet build -c Release    # zero errors, zero warnings from the app's own code
dotnet test                # 50 tests, all green
```

## Linux prerequisites

- **Display**: any X11 or Wayland session (the app is a desktop GUI; it is not meant for
  headless use, although every hardware-dependent path degrades gracefully there).
- **Camera (optional)**: a V4L2 device (`/dev/video0`). The OpenCV native runtime ships inside
  the build via the `OpenCvSharp4.official.runtime.linux-x64` package (`libOpenCvSharpExtern.so`),
  so no system OpenCV install is needed — just glibc/libstdc++. Without a camera the Scan tab
  shows a clear status message and the "paste instead" path keeps working.
- **Audio (optional)**: `arecord` and `aplay` from **alsa-utils** for microphone capture and the
  loopback monitor. Neither is required for the test tone, which is generated in pure C# — on a
  machine with no microphone and no sound stack the tone still proves the whole pipeline
  (PCM → Opus → RTP → UDP). Playback (loopback) also disables itself gracefully when no audio
  device exists; streaming continues.
- On **Windows**, microphone capture and playback use NAudio (`WaveInEvent` / `WaveOutEvent`)
  instead of the ALSA tools.

## The tabs

### Scan
Live webcam preview, continuously decoding QR codes (ZXing, QR-only via `QRCodeReader`; frames
are converted to grayscale and wrapped in a luminance source). The **first decode wins** —
deliver-once semantics mirroring the Android scanner's `AtomicBoolean` gate — after which
scanning stops and the routed result is shown. Every scan (camera or pasted) is routed through
`QrPayload.Parse` and appended to the history. A "Paste instead" box runs any text through the
same router for machines without cameras.

### Generate
Multi-line text in, live QR preview out (QRCoder, **ECC level L** to match the C# receiver's
terminal QRs), with copy-text and save-PNG buttons. When the input parses as a pinhole ticket
or `udp://` endpoint, structured details appear beside the QR: string version, peer ID as hex,
NAT hint, every candidate as `host:port` + kind, and static/endpoint key presence.

### Voice
Streams Opus audio over UDP to a host:port:

- **Source**: microphone (arecord on Linux, WaveInEvent on Windows) or the built-in 440 Hz
  test tone (pure C#, works headless).
- **Codec**: Concentus (pure-C# Opus) — 48 kHz, mono, 20 ms frames (960 samples), selectable
  bitrate 16 / 32 / 64 / 96 / 128 kbps.
- **RTP (RFC 3550)**: 12-byte header — version 2 (`0x80`), payload type 111, big-endian
  sequence (+1 per packet), big-endian timestamp (+960 per packet at 48 kHz), random 32-bit
  big-endian SSRC — followed by the Opus frame bytes. A receiving sink parses the header and
  strips those 12 bytes; the layout is unit-tested byte for byte.
- **Loopback monitor**: optionally decodes your own encoded frames and plays them back when
  audio output is available; it disables itself with a status message when it isn't.
- **Stats**: packets sent, RTP/Opus bytes, elapsed time, current target, error count.
- The Scan tab's `udp://` result offers **"Use as voice target"**, which fills the Voice tab's
  host/port fields (and switches to it).

### History
Persisted as JSON at `~/.local/share/OpusVoice.Qr/history.json`
(`Environment.GetFolderPath(ApplicationData)` + `OpusVoice.Qr/history.json`), newest first.
Click an entry to see its routed details again, with a copy button and a clear-all button.

## Payload router parity

`QrPayload.Parse` mirrors `app/src/main/java/com/example/qr/QrPayload.kt`:

- `pinhole1:…` (or a bare base64url body, whitespace tolerated) → **PinholeTicket** — parsed by
  a line-by-line port of `pinhole/src/main/kotlin/pinhole/ConnectionString.kt`: payload version
  1/2/3, version-matched flags byte, u64-LE peer ID, NAT hint (0/1/2), up to 32 candidates
  (family 4/16 + address + big-endian u16 port + kind direct/reflexive/relay/iroh-relay with
  their extra fields), v2 ending with exactly a 32-byte static key, v3 with exactly 64 bytes of
  static + endpoint keys, v1 ending exactly after the last candidate. Strict base64url charset,
  8192-character cap, `TryParse` returns null instead of throwing for untrusted input.
- `udp://host:port` → **UdpEndpoint** — case-insensitive scheme, port (after the last colon)
  must be 1..65535 with Kotlin `toIntOrNull` semantics, empty host rejected, bracketed IPv6
  unwrapped, unbracketed IPv6 refused.
- anything else → **Unknown**, raw text preserved.

The exact ticket from the Android test suite
(`peerId 0x0123456789ABCDEF`, `NatHint.Cone`, v3, two candidates, static + endpoint keys) is
used as the shared reference vector.

## Theme

The Android palette, exactly: background `#0F111A`, surface `#161926`, surface-variant
`#202538`, highlight `#2C324B`, accent emerald `#10B981` (scan / ticket), accent blurple
`#5865F2` (udp / voice), text `#F1F5F9` / `#94A3B8` / `#64748B`, error `#EF4444`, warning
`#F59E0B`. Defined once in `App.axaml` and mirrored in `Services/Palette.cs`.

## Future work

- **Pinhole dialing**: there is currently no "Pinhole" / "Pinhole.Net" package on NuGet
  (`dotnet package search Pinhole` finds nothing), so scanning a pinhole ticket shows its full
  parsed details (peer ID, candidates, keys) but cannot dial it. Once a public Pinhole library
  is published, add a ticket mode to the Voice tab that dials via the parsed candidates and
  keys.
- An RTP receiver/sink in this app (a standalone C# sink already consumes the same framing).
- Camera selection UI (currently device 0) and torch/front/back switching.
