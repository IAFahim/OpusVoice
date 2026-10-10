# AGENTS.md — OpusVoice agent notes

**Read first:** the sibling Pinhole.Net repo carries the full machine/phone
environment guide — [IAFahim/Pinhole.Net → AGENTS.md](../Pinhole.Net/AGENTS.md)
(adb path, sandbox-vs-host network distinction, firewall port 53317, phone
and devicetest-harness facts, trial discipline, CI flake procedure). This
file only adds what is specific to OpusVoice; the two repos are used
together for physical phone trials.

## Repo-specific facts

- The Android app embeds the Pinhole Kotlin dialer from `pinhole/` (interop
  target: the C# `Pinhole` SDK in IAFahim/Pinhole.Net). Transport-parity
  work (authenticated TCP fallback, same-session network validation) lives
  on branch `codex/phone-transport-parity` → PR #4; **do not "fix" the
  dialer for tcpOnly failures before checking the host-side firewall** —
  the 2026-10-11 tcpOnly timeout was the C# trial host's UFW (ephemeral
  port dropped), and the dialer was exonerated on a permitted port
  (IAFahim/Pinhole.Net 0e08c76).
- Building the app needs `local.properties` with
  `sdk.dir=/home/i/.android-sdk`; debug signing uses the committed
  `debug.keystore` (storepass/keypass `android`, alias `androiddebugkey`).
  Uninstall before installing a differently-signed build.
- Known app defect (not transport): fake STREAMING state with zero packets
  out — issue #5. UI state changes do not imply socket traffic; verify with
  kernel counters per the Pinhole guide.
- Physical-trial results and reproduction harnesses are recorded in
  IAFahim/Pinhole.Net under `docs/evidence/phone-trial-2026-10-11/`;
  post cross-repo trial findings on PR #4.
