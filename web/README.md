# OpusVoice QR Console (web)

The web twin of the Android app's QR system: scan a code with the camera (or paste
it), get typed data out; turn any text into a QR the phone can scan. Deployed at
**https://iafahim.github.io/OpusVoice/** and installable as a PWA.

Recognized payloads (same router as `app/src/main/java/com/example/qr/QrPayload.kt`):

- `pinhole1:…` — a Pinhole connection-string ticket (peer ID, NAT hint, candidates, pinned keys)
- `udp://host:port` — a plain RTP target
- anything else — shown as raw text, nothing silently lost

Stack: Vite + TypeScript (no framework), `BarcodeDetector` (Shape Detection API)
with a `jsQR` fallback so scanning works in every browser, CameraX-equivalent
`getUserMedia` handling with torch + Wake Lock, `navigator.clipboard` / `navigator.share`,
same-document View Transitions for tab switches, `popover` for the about sheet,
Workbox service worker via `vite-plugin-pwa`.

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
