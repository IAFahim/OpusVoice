# OpusVoice QR Console (web)

The web twin of the Android app: **voice streaming + QR discovery**, no typing.
Deployed at **https://iafahim.github.io/OpusVoice/** and installable as a PWA.

## Voice

Stream live Opus voice to the receiver — the same 48 kHz mono RTP stream the
Android app sends (PT 111, 20 ms frames), over a WebSocket the browser is
allowed to open:

1. Start the receiver's bridge: `dotnet run --project src/OpusVoice.Receiver -- ws 8080`
   (it prints a `ws://…/stream` QR code next to the usual `udp://` one).
2. Scan or paste that target into the Voice tab, pick a source (microphone or
   the 440 Hz test tone), optionally enable the monitor to hear the
   encode→decode round-trip, and press **Start streaming**.

Encoding is WebCodecs `AudioEncoder` (opus), capture is an `AudioWorklet`
(960-sample frames), framing is a hand-rolled 12-byte RTP header byte-compatible
with the app's `AudioRecorder.kt` and the receiver's `RtpHeader.cs`. Verified
end-to-end: the test tone produced a valid Ogg Opus capture on the receiver at
the selected bitrate with zero loss.

Browser rule worth knowing: an https page may only open `ws://localhost`
(mixed-content). For LAN receiver IPs, run the console locally (`npm run dev`)
— or any http origin.

## QR discovery

Recognized payloads (same router as `app/src/main/java/com/example/qr/QrPayload.kt`):

- `pinhole1:…` — a Pinhole connection-string ticket (peer ID, NAT hint, candidates, pinned keys)
- `udp://host:port` — a plain RTP target
- `ws://host:port/stream` — a voice receiver for this console
- anything else — shown as raw text, nothing silently lost

Stack: Vite + TypeScript (no framework), `BarcodeDetector` (Shape Detection API)
with a `jsQR` fallback so scanning works in every browser, CameraX-equivalent
`getUserMedia` handling with torch + Wake Lock, WebCodecs Opus + AudioWorklets
for voice, `navigator.clipboard` / `navigator.share`, same-document View
Transitions for tab switches, `popover` for the about sheet, Workbox service
worker via `vite-plugin-pwa`.

The connection-string parser in `src/payload.ts` is a line-for-line port of
`pinhole/src/main/kotlin/pinhole/ConnectionString.kt`; `test/payload.test.ts`
mirrors the Android unit tests against the same ticket vector.

## Run locally

```bash
npm install
npm test        # payload router unit tests
npm run dev     # http://localhost:5173/OpusVoice/
npm run build   # typecheck + production bundle in dist/
```

Camera scanning needs a secure context (localhost counts). On machines without a
camera, use the "Paste the code instead" path — it runs through the same router.

PWA icons are committed under `public/icons/`; regenerate with `npm run icons`.
