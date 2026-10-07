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
  src/OpusVoice.Qr.Pinhole/      Pinhole.Net-backed voice transport: dial tickets, accept peers
                                 (needs a sibling Pinhole.Net checkout; override with -p:PinholeRoot=)
  src/OpusVoice.Qr/              the Avalonia 11.3 app (Scan / Generate / Voice / Device / History tabs)
  tests/OpusVoice.Qr.Core.Tests/ xUnit tests: the six Android parity tests plus parser, RTP,
                                 tone, codec and live pinhole transport coverage
```

## Run

```bash
dotnet run --project src/OpusVoice.Qr        # from this directory
dotnet run --project src/OpusVoice.Qr -c Release
```

Build and test:

```bash
dotnet build -c Release    # zero errors, zero warnings from the app's own code
dotnet test                # 55 tests, all green (three exercise a real Pinhole session)
```

The Voice tab's Pinhole transport needs a checkout of
[Pinhole.Net](https://github.com/IAFahim/Pinhole.Net) as a **sibling of this repo** (the
project references `../../../../Pinhole.Net` — override with `-p:PinholeRoot=/path/to/Pinhole.Net`),
exactly like OpusVoice.Receiver. Without it, build the solution minus the Pinhole project;
UDP streaming and every other tab do not depend on it.

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
Streams Opus audio to a target over either transport:

- **UDP** — plain RTP to `host:port`, as before.
- **Pinhole / iroh** — paste or scan a `pinhole1:` connection string (from OpusVoice.Receiver,
  the Android app's listener mode, or any Pinhole.Net node). Start dials it — direct punch
  first, iroh relay as the standing fallback — then the stats panel shows the live path
  (`direct <ip:port>` / `relay <host>`), round-trip time from a 2-second ping cadence, and
  dropped-datagram/rejected-frame counters while the same RTP+Opus bytes stream encrypted.
  Dial failures surface typed reasons (invalid string, self-connection, no relay fallback,
  timed out, incompatible peer).

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

#### Pinhole listener (receive side)

With Pinhole selected, a listener panel appears: Start binds a Pinhole node and shows this
machine's connection string (copy it, or render it as a QR on the Generate tab for the phone
to scan). When a peer dials and streams, its RTP/Opus frames are decoded and played through
the default audio output, with path/packet counters in the stats panel — the desktop
counterpart of the app's loopback monitor, pointed at the network.

### Device (USB control)

For a phone plugged in over USB (developer mode + adb): list devices, install an APK, launch
or force-stop the OpusVoice app, and stream its logcat filtered to the audio/network/pinhole
tags — the live evidence stream while the phone streams. Locates adb from `ANDROID_HOME`, the
default SDK paths, or PATH; the tab degrades to clear guidance when adb is absent.

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

- Camera selection UI (currently device 0) and torch/front/back switching.
- Disk capture for received streams (the Pinhole listener plays live; OpusVoice.Receiver
  remains the tool that writes .opus captures).
