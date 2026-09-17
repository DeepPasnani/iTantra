# iTantra — Offline Multilingual Voice Communication Network

An offline Android app for multi-hop voice communication with no internet and no
conventional wireless infrastructure. Speech-to-text and text-to-speech happen fully
on-device; only compact text (not audio) crosses the network, over an AODV-style
multi-hop Wi-Fi Direct mesh with ACKs, retries, deduplication, and store-and-forward.

## Status at a glance

| Layer | State |
|---|---|
| Message model, compact wire codec | Done, unit-tested |
| AODV-style routing (discovery, forwarding, TTL, route break/rediscovery) | Done, unit-tested |
| Reliability (ACK, retry, dedup, store-and-forward outbox) | Done, unit-tested, Room-backed |
| Wi-Fi Direct transport | Implemented, **not yet run on real hardware** |
| STT — Hindi, Gujarati, Telugu | Implemented via Vosk, **not yet benchmarked** |
| STT — Marathi, Kannada, Malayalam, Tamil, Odia, Bengali | **Not implemented** (no ready-made lightweight engine; see below) |
| TTS — all 9 languages | Implemented via eSpeak NG, **not yet benchmarked** |
| Higher-quality TTS (AI4Bharat Indic-TTS) | **Not implemented** (scaffold only) |
| Bluetooth fallback | Not implemented — future work per spec |

Nothing above should be read as a performance claim. "Implemented" means it compiles
and the logic is unit-tested where that's possible without a phone; it does not mean
WER, latency, throughput, or battery/RAM numbers have been measured. See
[Claims discipline](#claims-discipline).

## Why text, not audio

Raw audio is expensive over a constrained, multi-hop wireless link. Speech is
processed at the endpoints — recognized to text on the sender's phone, synthesized
back to speech on the receiver's — so the network only ever has to move a few hundred
bytes per message plus small control packets, not a continuous audio stream.

## Architecture

```
speech (on phone A)                          speech (on phone B)
   │ STT: audio -> sentence-level text            ▲ TTS: text -> audio
   ▼                                               │
Message (id, source, destination, language,   Message delivered, deduplicated
priority, text) ── compact binary codec ──►
   │
   ▼
ReliabilityLayer  (ACK, retry, dedup, outbox) ──► same layer on the receiving side
   │
   ▼
AodvRouter        (route discovery, forwarding, TTL, route break/rediscovery)
   │
   ▼
Transport         (one-hop neighbor send/receive)
   │
   ▼
WifiDirectTransport (Wi-Fi Direct group -> TCP sockets to each neighbor)
```

Each layer only talks to the one below it through a small interface, and every layer
except the Android-specific transport is plain Kotlin with no Android dependency —
that's what makes the routing and reliability logic unit-testable (18 tests, no
device or emulator needed) using an in-process simulated mesh
(`InMemoryTransport`/`InMemoryMeshFabric`) instead of real radios.

## Module layout

| Module | What it is | Depends on |
|---|---|---|
| `core-messaging` | `Message`, `NodeId`, `MessageId`, `Language`, `Priority`, compact binary codec | — |
| `transport-api` | `Transport` interface, `InMemoryTransport` (test double), `CompositeTransport` (models a relay/bridge node) | `core-messaging` |
| `core-routing` | `AodvRouter` — on-demand route discovery, forwarding, TTL/hop limit, dedup, route expiry | `transport-api` |
| `core-reliability` | `ReliabilityLayer` — ACK, retry, message-level dedup, store-and-forward outbox contract | `core-routing` |
| `transport-wifi` | `WifiDirectTransport` — real Wi-Fi Direct group + TCP socket implementation of `Transport` | `transport-api` |
| `app` | Compose UI, STT/TTS wiring, Room persistence, `MeshSession` composition | all of the above |

## Building

Requires JDK 17 and the Android SDK (platform 34, build-tools 34.0.0). This repo
includes a Gradle wrapper, but if `./gradlew` can't reach `services.gradle.org` from
your network, install Gradle 8.9 directly and use it instead:

```
./gradlew test assembleDebug
# or, with a standalone Gradle 8.9 install:
gradle test assembleDebug
```

`local.properties` must point `sdk.dir` at your Android SDK location (already set for
this checkout's environment — update it if you're building elsewhere).

Run just the pure-Kotlin unit tests (no Android build needed):
```
gradle :core-messaging:test :transport-api:test :core-routing:test :core-reliability:test
```

## Running it on a phone

Wi-Fi Direct needs real radios — an emulator won't exercise the mesh. See the
step-by-step two-phone walkthrough in this project's chat history (install the APK,
grant permissions, pair via Android's own Wi-Fi Direct settings, then use the app's
push-to-talk / destination-NodeId field / language picker). In short:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

then launch, tap **Start mesh**, grant the requested permissions, and pair two
phones via **Settings → Wi-Fi → Wi-Fi Direct** — the app's transport layer picks up
the resulting group automatically.

## Setting up STT (Vosk)

Vosk only has ready-made lightweight models for **Hindi, Gujarati, and Telugu** among
this project's languages. For each one, download the model, extract it, rename the
extracted top-level folder to just the language code, and place it here:

| Language | Download | Place at |
|---|---|---|
| Hindi | https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip | `app/src/main/assets/models/vosk/hi/` |
| Gujarati | https://alphacephei.com/vosk/models/vosk-model-small-gu-0.42.zip | `app/src/main/assets/models/vosk/gu/` |
| Telugu | https://alphacephei.com/vosk/models/vosk-model-small-te-0.42.zip | `app/src/main/assets/models/vosk/te/` |

Full catalog: https://alphacephei.com/vosk/models. Each bundled model adds roughly
its zip size to the APK — that's why only these three are wired up by default.

**Marathi, Kannada, Malayalam, Tamil, Odia, and Bengali have no Vosk model** (confirmed
against Vosk's own catalog and an open, unresolved upstream issue asking for exactly
these). `IndicConformerSpeechRecognizer` documents the AI4Bharat model
(`ai4bharat/indic-conformer-600m-multilingual`, MIT license) that would need to be
ONNX-exported, quantized, and given a Kotlin decoder to close this gap — that
conversion work is not done in this repo.

## Setting up TTS (eSpeak NG)

No model file to download — eSpeak NG bundles all its language data inside the
engine app itself. Install **"eSpeak NG"** (package `com.reecedunn.espeak`) from
F-Droid, or build it from https://github.com/espeak-ng/espeak-ng's `android/`
sources. The app targets that engine package directly, so it works without changing
the phone's system-default TTS engine. Voice quality is robotic (formant synthesis,
not neural) — `IndicTtsSpeechSynthesizer` documents the AI4Bharat Indic-TTS
(FastPitch + HiFi-GAN, MIT license) upgrade path, not implemented here for the same
reason as the STT gap above (no mobile export, needs a conversion pipeline).

## Claims discipline

This project follows a simple rule: don't claim more than what's actually been
verified.

- "AODV-style" / "AODV-inspired", never "a full RFC 3561 implementation" — only the
  true destination replies, there's no destination sequence-number freshness check,
  and route-error propagation is one hop at a time.
- Designed for roughly 20-50 simultaneous nodes per local communication domain, not
  unlimited scale — real limits come from Android hotspot/Wi-Fi Direct client
  capacity, RF range/interference, and battery/CPU, not the routing algorithm.
- `WifiDirectTransport` has not been run on real hardware from this repo. Whether a
  given phone can act as a relay/bridge (client in one Wi-Fi Direct group, owner of
  another, simultaneously) is chipset/OEM-dependent and must be verified on the
  target devices, not assumed — `CompositeTransport` models the concept without
  claiming the concurrency exists.
- No STT/TTS accuracy, latency, or resource-usage numbers are quoted anywhere in this
  repo, because none have been measured on a target phone yet.
- Bluetooth fallback is explicitly out of scope for now — a future-work item, not a
  missing feature.

## What's next

Roughly in priority order:

1. Validate `WifiDirectTransport` on two real phones (group formation, socket
   handshake, neighbor join/loss).
2. Verify Wi-Fi concurrency for relay/bridge nodes on the actual target hardware.
3. Wire a real "nearby peers" picker into the UI (destination is currently a raw hex
   `NodeId` text field).
4. Benchmark Vosk STT (WER, latency) and eSpeak NG TTS (RTF, intelligibility) on
   target phones for the languages already wired up.
5. Build the AI4Bharat ONNX conversion + on-device inference pipeline to close the
   six-language STT gap and upgrade TTS quality.
6. Run the full physical multi-phone test matrix: moving-node link stability, route
   discovery/break-recovery timing, relay failure/partition behavior, throughput and
   RSSI at distance, hotspot client-capacity limits.
