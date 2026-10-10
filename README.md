<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/72a50948-fead-45ec-bfa7-75673af1c55a

## Run Locally

**Prerequisites:**  [Android Studio](https://developer.android.com/studio)


1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Create a file named `.env` in the project directory and set `GEMINI_API_KEY` in that file to your Gemini API key (see `.env.example` for an example)
5. Remove this line from the app's `build.gradle.kts` file: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or physical device
7. If you have already published your app in AI Studio, please [request upload key reset](https://support.google.com/googleplay/android-developer/answer/9842756#zippy=%2Crequest-an-upload-key-reset) in Google Play Console.

## CI/CD & Releases (GitHub Actions)

Free CI/CD via [GitHub Actions](https://github.com/features/actions) (`.github/workflows/android.yml`):

- **Every push / PR:** builds a debug APK and runs unit tests.
- **Push a tag `v*`:** builds a **signed release APK + AAB**, self-contained **desktop
  console builds** (linux-x64 tar.gz; win-x64, osx-x64, osx-arm64 zips — each with its
  native OpenCV runtime for the Scan tab), and the **web console bundle**, and attaches them
  all to one GitHub Release.

```bash
git tag v1.0.0
git push origin v1.0.0   # release jobs run and attach artifacts
```

Signing uses the repo secrets `KEYSTORE_BASE64`, `STORE_PASSWORD`, and `KEY_PASSWORD`. The key/credentials live locally in `my-upload-key.jks` / `signing-credentials.txt` (git-ignored) — back them up, they are required to ship updates under the same identity.

## Stack notes

- **Security:** `GEMINI_API_KEY` is *not* embedded in release artifacts (it stays commented out in `.env.example`). If you ever enable it, treat anything packaged into an APK as public — prefer fetching keys at runtime from your own backend.
- **R8 + resource shrinking** are enabled on release builds, and CI runs a signed `assembleRelease` on every push so minification breakage surfaces immediately.
- **Baseline profile:** `app/src/main/baseline-prof.txt` pre-compiles the startup/streaming hot path via `androidx.profileinstaller`. Replacing the hand-written rules with device-measured ones from a Macrobenchmark module is future work.
- **No DI / navigation framework (deliberate):** single-screen app; Hilt and Navigation 3 become worthwhile with a second screen or injected repositories.

## QR discovery (scan, don't type)

The Android app scans connection-string QR codes straight into its transport fields (camera permission is optional; a paste path always exists). The [C# receiver](https://github.com/YouAnd-I/OpusVoice.Receiver) prints its ticket — and its `udp://` LAN target — as a terminal QR code for the phone to scan.

The same system runs in the browser at **https://iafahim.github.io/OpusVoice/** (the `web/` app, deployed by `.github/workflows/web.yml`): scan with the camera or paste, get the typed payload — peer ID, NAT hint, candidates, pinned keys — and turn any string into a scannable QR. It's an installable PWA and all scanning happens on-device.

The web console also **streams voice**: mic (or a test tone) → WebCodecs Opus → the same RTP framing the app uses → a WebSocket bridge. Browsers can't send raw UDP, so run the receiver's new `ws` mode (`dotnet run --project src/OpusVoice.Receiver -- ws 8080`) — it prints a `ws://` QR the console recognizes and can stream to directly. Note the browser mixed-content rule: from the https Pages app only `ws://localhost` targets are reachable; serve the console locally (`npm run dev`) for LAN receivers.

The **Avalonia desktop console** (`desktop/`) pairs with both: it scans, generates and
streams like the web console, dials **Pinhole / iroh tickets directly** (encrypted session,
live path + RTT in the stats), listens for the phone to dial in and plays its stream, and
controls a USB-connected phone over adb (install APK, launch, logcat). Release tags build it
self-contained for Linux, Windows and macOS.

## Pinhole transport (NAT traversal + E2E encryption)

Next to plain UDP, the app can stream its RTP through a [Pinhole](https://github.com/IAFahim/Pinhole.Net) session:

1. Run [OpusVoice.Receiver](https://github.com/YouAnd-I/OpusVoice.Receiver) (or any Pinhole.Net node) and copy the printed `pinhole1:…` connection string.
2. In the app's network card, switch to **Pinhole / iroh** and paste or scan the string.
3. Start streaming — the phone punches your NAT, completes the encrypted handshake, and sends the same RTP datagrams end-to-end encrypted.

The `pinhole/` module implements direct UDP, native iroh endpoint tickets/IDs,
signed HTTP pkarr discovery, and authenticated iroh relay v1/v2 framing in Kotlin.
The encrypted Pinhole session runs above those routes: triple-DH X25519,
HKDF-SHA256, and AES-GCM with replay protection. Incoming RTP is connected to
playback, handshake flights are retried, and validated direct paths can replace
relay routes while the relay remains available for fallback. TURN is unsupported.

The dialer discovers public UDP mappings through STUN on the same socket used for audio
and announces its host/public candidates inside the encrypted session, allowing the PC
to punch back. Candidate discovery runs in the background, refreshes after interface
changes and once a minute, and does not delay relay connection setup. Wi-Fi/Ethernet
link-local candidates are tried on each local LAN interface and omitted on mobile-only
connections. Established peers' reverse punches are answered without trusting an
unsealed probe to select the active path.

The phone also requests an explicit mapping for its audio UDP port through PCP,
NAT-PMP, and UPnP, in that order. Android supplies gateways from the active OS network
routes. Discovery runs in the background, renews leases, withdraws expired or stale
addresses, releases mappings on close, and publishes successful mappings through
authenticated candidate announcements. `PortMappingOptions(enabled = false)` is an
opt-out. Unsupported or refusing routers contribute no mapping; direct/STUN/relay
operation continues. Router control uses local HTTP for UPnP, with responder-pinned
endpoints, bounded XML/HTTP, and redirects disabled. Public iroh URLs require HTTPS.

For a PC with a restricted inbound firewall, run the receiver with `--port N` and permit
that application-owned UDP port. Carrier NAT does not by itself prove a direct path is
unavailable; check the receiver's actual path and firewall logs.

For a native iroh ID/ticket, run the receiver in `iroh` mode:

```bash
dotnet run --project src/OpusVoice.Receiver -- iroh
```

Normal desktop listening and both receiver modes enable LAN announcements, signed
endpoint publication, and direct-address publication by default. The `iroh` mode
selects the native ID/QR display; the ordinary Pinhole ticket flow remains available.
The Android dialer already resolves signed records and probes their direct candidates;
it does not announce a LAN service or publish a listener record itself.

The Android app also browses nearby `_pinhole._udp` receivers by default while it is
in the foreground. In the Pinhole connection settings, select a nearby receiver to
fill its encrypted-key ticket, then press Start to stream. The nearby-discovery switch
disables browsing without affecting manual tickets or an existing stream. Android 14+
tracks all resolved IPv4/IPv6 addresses continuously; older NSD APIs resolve one
address at a time and refresh in the background. IPv6 link-local routing uses the
phone's interface scope. Lost services are withdrawn and browsing is released when
the app leaves the foreground. First-discovery LAN metadata is unsigned: the session
proves key possession, while a trusted QR/ticket establishes device identity.

Pinhole.Net's signed native discovery
record binds the receiver's Ed25519 endpoint ID to its public Pinhole X25519 key
using `user-data=pinhole-v1:<hex-key>`. The Android client verifies that signature
before dialing, preserving peer authentication when the native ticket itself
does not carry the Pinhole key. Persist the receiver's `IdentityKeySeed` to keep
the same ID across restarts. Ordinary `pinhole1:` tickets still work without this
discovery lookup.

The app uses iroh-compatible discovery and relay packets with Pinhole's session
protocol above them. An unchanged native iroh application endpoint needs its own
compatible packet protocol engine; sharing a relay or endpoint ID does not change
the application's session protocol.

The repeatable C# ↔ Kotlin test matrix covers twelve cases: direct Pinhole, an IPv6-only
LAN receiver ticket generated in Kotlin, router mapping, an edited first PACK token, lost
PACK/HSCK flights, native IDs/tickets with direct and forced relay routes,
relay-to-direct upgrade, and tampered discovery rejection. The mapping case verifies
that the .NET peer receives the authenticated candidate for the phone's actual audio
socket port. It runs in CI.

```bash
# Requires JDK 21, .NET 10, and the external reference iroh-relay 1.3.0 binary.
# The relay is a test process; it is not bundled into the Android app.
IROH_RELAY_BIN=/path/to/iroh-relay dotnet run --project interop/InteropRunner \
  -p:PinholeRoot=/path/to/Pinhole.Net
```

These network interop tests run on the JVM. A physical Android phone's
complete Wi-Fi/cellular/background/audio matrix remains separate validation.

### Physical device transport validation

`deviceTest` is a separate test APK containing the shipping Kotlin transport. It
does not replace the installed voice app or request microphone/camera permission.
Start a .NET echo peer on the desktop's actual network:

```bash
dotnet run --project interop/EchoPeer -p:PinholeRoot=/path/to/Pinhole.Net -- --physical-network
```

With an authorized Android phone on cellular and Wi-Fi off, supply its printed
ticket to the instrumented test. Keep tickets in local test logs; they advertise
the peer's addresses and public identity.

```bash
./gradlew :deviceTest:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.pinholeTicket="$PINHOLE_TICKET" \
  -Pandroid.testInstrumentationRunnerArguments.requireCellular=true
```

Add `-Pandroid.testInstrumentationRunnerArguments.relayOnly=true` to measure a
forced relay separately. The test verifies the active network, completes the
encrypted handshake, checks 48 byte-exact echoes at sizes 1–1200, records retries
and latency percentiles, and checks that close retires the connection. These are
transport checks; audio, background/suspend, Wi-Fi handoff and VPN require their
own recorded device scenarios.

The [2026-10-10 OPPO LTE report](docs/validation/2026-10-10-oppo-cellular.json)
records passing automatic-routing and forced-relay cases on Android 16. Both
used the Singapore iroh relay and returned all 48 datagrams without retries.
The report includes APK/core assembly hashes and states the untested scenarios.
