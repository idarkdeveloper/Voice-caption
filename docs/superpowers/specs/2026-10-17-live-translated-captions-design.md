# Live Translated Captions — Design Spec

Date: 2026-10-17
Status: Draft for review

## 1. Goal

An Android app that listens to the audio of any film playing on the phone and
shows live, translated captions in a floating overlay, similar to Android's
built-in Live Caption but with translation.

Success: while a film plays in another app, translated captions appear in near
real time (free tier about 2–4 s delay, paid tier about 1 s), readable and
roughly in sync with the speech.

## 2. Scope and assumptions

- Android only (API 29+ / Android 10+, required by `AudioPlaybackCapture`).
- Audio source: system-wide playback capture (any app). An in-app video player
  is out of scope for v1 and may be added later.
- Source and target languages are chosen by the user; source language
  auto-detection is a nice-to-have.
- Apps that opt out of capture (DRM streaming apps such as Netflix) cannot be
  captioned. The app detects this and tells the user.
- Freemium: free tier runs fully on-device; paid tier uses cloud services.

## 3. Architecture

```
Android app (Kotlin)                          Backend (paid only)
┌──────────────────────────────────┐          ┌──────────────────────────┐
│ CaptureService (foreground)      │          │ Auth + entitlement check │
│  AudioPlaybackCapture → 16k PCM  │          │ (Play Billing receipt)   │
│        ↓                         │          │ WebSocket ASR proxy      │
│  VAD + ring buffer               │          │ → Deepgram/Google STT    │
│        ↓                         │  paid    │ Translation proxy        │
│  Pipeline (strategy interface)   │ ───────► │ → DeepL/Google           │
│   ├ LocalPipeline (free)         │          │ Usage metering + limits  │
│   │   Whisper → ML Kit Translate │          └──────────────────────────┘
│   └ CloudPipeline (paid)         │
│        ↓                         │
│  CaptionStabilizer               │
│        ↓                         │
│  OverlayRenderer (floating view) │
└──────────────────────────────────┘
```

## 4. App components

| Component | Responsibility | Depends on |
|---|---|---|
| `AudioCapture` | MediaProjection + AudioPlaybackCapture, emits 16 kHz mono PCM frames | Android APIs |
| `VadSegmenter` | Silero VAD cuts speech into 2–6 s segments with overlap | PCM frames |
| `SpeechRecognizer` (interface) | `WhisperLocal` and `CloudStreamingAsr` implementations | segments |
| `Translator` (interface) | `MlKitTranslator` and `CloudTranslator` implementations | text |
| `CaptionStabilizer` | Shows a partial caption fast, replaces it with the final one, avoids flicker | ASR + translation output |
| `OverlayRenderer` | Draggable overlay: font size, opacity, bilingual or translation-only | caption stream |
| `EntitlementManager` | Play Billing state; selects which pipeline to use | Play Billing, backend |
| `ModelManager` | Downloads and updates Whisper models and ML Kit language packs | storage |

Stack: Kotlin, Jetpack Compose (settings UI), foreground service of type
`mediaProjection`, `SYSTEM_ALERT_WINDOW` overlay, whisper.cpp or sherpa-onnx
for on-device ASR, ML Kit Translate for on-device translation.

## 5. Free vs. paid tier

| | Free | Paid |
|---|---|---|
| Speech recognition | On-device Whisper (`base`/`small`, quantized) | Cloud streaming ASR |
| Translation | ML Kit (offline) | DeepL or Google Cloud Translation |
| Delay | about 2–4 s | about 1 s |
| Internet needed | No | Yes |
| Limits | None | Fair-use cap, e.g. 30 h/month |

Free users may get a few trial minutes of the cloud pipeline so they can
compare quality before subscribing. Both tiers sit behind the same
`SpeechRecognizer` and `Translator` interfaces.

## 6. Backend (paid features only)

- Stack: small FastAPI or Node service on Cloud Run or Railway; Postgres
  (Neon) for users and usage counters.
- Auth: Firebase anonymous auth plus Google Play subscription token
  verification via the Play Developer API. The backend issues short-lived
  session tokens.
- ASR proxy: the app opens a WebSocket to the backend, which forwards audio
  to the ASR vendor. Vendor API keys never ship in the APK.
- Metering: seconds per user per month are counted; requests over the cap are
  rejected. This bounds cost and is the main margin risk.
- Privacy: audio is not stored, only usage counters. The app asks for explicit
  consent before sending audio to the cloud.

## 7. Data flow and error handling

- Normal flow: capture → VAD → ASR (partial, then final) → translate →
  stabilize → overlay.
- Cloud failure or no internet on paid: fall back automatically to the local
  pipeline and show an "offline mode" badge.
- App blocks capture (DRM): detect sustained silence while media is playing
  and show "This app doesn't allow audio capture".
- Overheating or low battery on free: switch to a smaller Whisper model or
  process fewer segments.
- Permissions: MediaProjection consent is requested each session; overlay
  needs `SYSTEM_ALERT_WINDOW`; the foreground service declares the
  `mediaProjection` type.

## 8. Testing

- Unit tests: VAD segmentation, caption stabilizer, entitlement logic.
- Accuracy: fixed set of film clips in several languages; WER for ASR and a
  translation quality score, compared across free and paid pipelines.
- Latency: end-to-end delay measured per device tier.
- Backend: contract tests with a mock ASR vendor; billing receipt
  verification tests.

## 9. Build order

1. Capture + overlay showing raw (untranslated) captions.
2. Local pipeline: Whisper + ML Kit (complete free tier).
3. Stabilizer and settings UI.
4. Backend + cloud pipeline.
5. Play Billing, paywall, metering.
6. Play Store compliance (MediaProjection and overlay policy declarations).

## 10. Open items

- Whisper model size vs. phone speed: benchmark on a mid-range device before
  locking the free-tier model.
- Final monthly cap and price for the paid tier, set after measuring real
  vendor cost per hour.
- Play Store review for overlay and audio capture permissions is strict;
  allow time for it.
- Source language auto-detection: decide whether it ships in v1.
