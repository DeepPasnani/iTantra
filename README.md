# iTantra — Offline Multilingual Voice Communication Network

An offline Android app for multi-hop voice communication with no internet and no
conventional wireless infrastructure. Speech-to-text and text-to-speech happen fully
on-device; only compact text (not audio) crosses the network, over an AODV-style
multi-hop Wi-Fi Direct mesh with ACKs, retries, deduplication, and store-and-forward.

> **Current build only bundles Hindi, Gujarati, and English** (for a smaller demo-video
> APK, ~290MB instead of ~1.5GB). Telugu and the six IndicConformer languages are
> commented out, not removed — their models live in `models-disabled/` and the map
> entries in `VoskSpeechRecognizer`/`IndicConformerSpeechRecognizer` are one uncomment
> away. Restore steps: move the language's folder from `models-disabled/` back to
> `app/src/main/assets/models/`, uncomment its line in the relevant recognizer's
> `ASSET_FOLDER_BY_LANGUAGE` map, and add it back to `ACTIVE_DEMO_LANGUAGES` in
> `MainActivity.kt`.

## Status at a glance

| Layer | State |
|---|---|
| Message model, compact wire codec | Done, unit-tested |
| AODV-style routing (discovery, forwarding, TTL, route break/rediscovery) | Done, unit-tested |
| Reliability (ACK, retry, dedup, store-and-forward outbox) | Done, unit-tested, Room-backed |
| Wi-Fi Direct transport | Implemented, **not yet run on real hardware** |
| STT — Hindi, Gujarati, Telugu, English | Implemented via Vosk, **not yet benchmarked** |
| STT — Marathi, Kannada, Malayalam, Tamil, Odia, Bengali | Implemented via AI4Bharat IndicConformer (ONNX Runtime), **not yet benchmarked, feature pipeline unverified against reference — see below** |
| TTS — all 10 languages | Implemented via eSpeak NG, **not yet benchmarked** |
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

Wi-Fi Direct needs real radios — an emulator won't exercise the mesh. Before
installing the APK, install **eSpeak NG** (package `com.reecedunn.espeak`) from
F-Droid — https://f-droid.org/packages/com.reecedunn.espeak/ — since it ships as a
separate app that this one calls into for TTS (see
[Setting up TTS](#setting-up-tts-espeak-ng) below); without it, speech synthesis
silently does nothing. See the step-by-step two-phone walkthrough in this project's
chat history (install the APK, grant permissions, pair via Android's own Wi-Fi
Direct settings, then use the app's push-to-talk / destination-NodeId field /
language picker). In short:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

then launch, tap **Start mesh**, grant the requested permissions, and pair two
phones via **Settings → Wi-Fi → Wi-Fi Direct** — the app's transport layer picks up
the resulting group automatically.

## Setting up STT (Vosk)

Vosk only has ready-made lightweight models for **Hindi, Gujarati, Telugu, and
English** among this project's ten target languages. For each one, download the
model, extract it, rename the extracted top-level folder to just the language code,
and place it here:

| Language | Download | Place at |
|---|---|---|
| Hindi | https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip | `app/src/main/assets/models/vosk/hi/` |
| Gujarati | https://alphacephei.com/vosk/models/vosk-model-small-gu-0.42.zip | `app/src/main/assets/models/vosk/gu/` |
| Telugu | https://alphacephei.com/vosk/models/vosk-model-small-te-0.42.zip | `app/src/main/assets/models/vosk/te/` |
| English | https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip | `app/src/main/assets/models/vosk/en/` |

Full catalog: https://alphacephei.com/vosk/models. Each bundled model adds roughly
its zip size to the APK — that's why only these four are wired up by default.

**Marathi, Kannada, Malayalam, Tamil, Odia, and Bengali have no Vosk model** (confirmed
against Vosk's own catalog and an open, unresolved upstream issue asking for exactly
these) — see the next section for how they're covered instead.

## Setting up STT for the other six languages (AI4Bharat IndicConformer)

Marathi, Kannada, Malayalam, Tamil, Odia, and Bengali run on AI4Bharat's per-language
IndicConformer CTC checkpoints (`ai4bharat/indicconformer_stt_<code>_hybrid_ctc_rnnt_large`,
MIT license), exported to ONNX and int8-quantized by OpenVoiceOS and run on-device via
`onnxruntime-android` — see `IndicConformerSpeechRecognizer`,
`NemoLogMelFeatureExtractor`, and `CtcGreedyDecoder`. Each language's int8 ONNX export
is ~137MB, so — unlike the Vosk models — these are **not committed to git or the
release APK build inputs by hand**: download the three files per language from each
HuggingFace repo and place them here:

| Language | HuggingFace repo | Place at |
|---|---|---|
| Marathi | https://huggingface.co/OpenVoiceOS/ai4bharat-indicconformer-mr-onnx | `app/src/main/assets/models/indic-conformer/mr/` |
| Kannada | https://huggingface.co/OpenVoiceOS/ai4bharat-indicconformer-kn-onnx | `app/src/main/assets/models/indic-conformer/kn/` |
| Malayalam | https://huggingface.co/OpenVoiceOS/ai4bharat-indicconformer-ml-onnx | `app/src/main/assets/models/indic-conformer/ml/` |
| Tamil | https://huggingface.co/OpenVoiceOS/ai4bharat-indicconformer-ta-onnx | `app/src/main/assets/models/indic-conformer/ta/` |
| Odia | https://huggingface.co/OpenVoiceOS/ai4bharat-indicconformer-or-onnx | `app/src/main/assets/models/indic-conformer/or/` |
| Bengali | https://huggingface.co/OpenVoiceOS/ai4bharat-indicconformer-bn-onnx | `app/src/main/assets/models/indic-conformer/bn/` |

From each repo, download `model.int8.onnx`, `vocab.txt`, and `config.json` into the
folder shown (no renaming needed, the filenames must match exactly). All six added to
the APK is roughly +820MB — see [Claims discipline](#claims-discipline) below on why
that size and the accuracy of these six languages are both unverified, and
[What's next](#whats-next) for the download-on-demand alternative if that footprint
turns out to fail the Efficiency criterion on a real low/mid-range phone.

Two parts of this integration have no existing Kotlin/Java reference to check against
and were ported by hand from the Python `onnx-asr` project's source: the log-mel
feature extraction (`NemoLogMelFeatureExtractor`) and the fixed-threshold energy VAD
that segments speech into utterances (`IndicConformerSpeechRecognizer`, since — unlike
Vosk — the ONNX Runtime gives no built-in endpointing). Both compile, run without
crashing on synthetic input, and are unit-tested for internal consistency (FFT
correctness against a brute-force DFT, CTC-decode collapse rules, feature-tensor shape
and normalization), but **none of that is the same as verifying real speech from a real
microphone decodes to the right words** — that needs a real phone, real audio in each
of the six languages, and a diff against `onnx_asr`'s own Python output for the same
WAV file to catch any feature-pipeline mismatch before trusting the WER.

## Setting up TTS (eSpeak NG)

No model file to download — eSpeak NG bundles all its language data inside the
engine app itself. Install **"eSpeak NG"** (package `com.reecedunn.espeak`) from
F-Droid, or build it from https://github.com/espeak-ng/espeak-ng's `android/`
sources. The app targets that engine package directly, so it works without changing
the phone's system-default TTS engine. Voice quality is robotic (formant synthesis,
not neural) — `IndicTtsSpeechSynthesizer` documents the AI4Bharat Indic-TTS
(FastPitch + HiFi-GAN, MIT license) upgrade path, not implemented here for the same
reason as the STT gap above (no mobile export, needs a conversion pipeline).

## Releasing a signed APK

A release build must be signed or most devices refuse to install it. Generate a
keystore once (outside the repo, never commit it):

```
keytool -genkeypair -v -keystore itantra-release.keystore -alias itantra \
  -keyalg RSA -keysize 2048 -validity 10000
```

Then build with the keystore path and passwords passed as env vars — `app/build.gradle.kts`
reads `ITANTRA_KEYSTORE`, `ITANTRA_KEYSTORE_PASSWORD`, and `ITANTRA_KEY_PASSWORD` and
signs the `release` build type when they're set (falls back to an unsigned release
build otherwise, so plain `assembleRelease` without these still works for local
testing):

```
ITANTRA_KEYSTORE=/path/to/itantra-release.keystore \
ITANTRA_KEYSTORE_PASSWORD=... \
ITANTRA_KEY_PASSWORD=... \
./gradlew clean assembleRelease
```

The signed APK lands at `app/build/outputs/apk/release/app-release.apk`. Install it
on at least one real device and confirm mesh start, permission grants, and an STT
model loading before publishing it anywhere.

To publish it as a GitHub Release:

```
git tag v1.0.0
git push origin v1.0.0
gh release create v1.0.0 app/build/outputs/apk/release/app-release.apk \
  --title "v1.0.0" \
  --notes "Install eSpeak NG from F-Droid first: https://f-droid.org/packages/com.reecedunn.espeak/
Then install this APK, grant permissions, and pair two phones via Wi-Fi Direct settings."
```

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
- The IndicConformer STT path (Marathi, Kannada, Malayalam, Tamil, Odia, Bengali) adds
  a second, sharper caveat on top of "not benchmarked": its feature extraction
  (`NemoLogMelFeatureExtractor`) and CTC decoding (`CtcGreedyDecoder`) are a from-scratch
  Kotlin port of a Python reference (`onnx-asr`) with no existing Kotlin/Java
  implementation to lean on, and this environment has no way to run the Python
  reference to diff against. It compiles, runs on synthetic audio without crashing, and
  is unit-tested for internal consistency — that is evidence it is plumbed together
  correctly, not evidence it transcribes real speech correctly.
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
5. Verify the IndicConformer feature pipeline against `onnx_asr`'s own Python output
   for the same WAV file (see [Claims discipline](#claims-discipline)), then benchmark
   WER/latency/RAM for the six languages it covers, and calibrate the energy-VAD
   thresholds in `IndicConformerSpeechRecognizer` against a real microphone.
6. Decide bundle-in-APK vs. download-on-first-use for the IndicConformer models: all
   six add ~820MB to the APK today, which may fail the Efficiency criterion outright on
   a genuinely low-end phone — downloading each language's model into app-private
   storage the first time it's selected (same idea as offline translation packs) would
   keep the base APK small at the cost of a looser reading of "fully offline only" (one
   internet fetch per language before that language ever works offline).
7. Upgrade AI4Bharat Indic-TTS (FastPitch + HiFi-GAN, MIT license) over eSpeak NG for
   quality — `IndicTtsSpeechSynthesizer` documents the path, not implemented here.
8. Run the full physical multi-phone test matrix: moving-node link stability, route
   discovery/break-recovery timing, relay failure/partition behavior, throughput and
   RSSI at distance, hotspot client-capacity limits.
