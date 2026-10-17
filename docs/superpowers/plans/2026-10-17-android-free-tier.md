# Android App, Free Tier (On-Device) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An Android app that captures system audio from any app and shows live, on-device translated captions in a floating overlay.

**Architecture:** A foreground `CaptureService` feeds 16 kHz PCM frames into a pure-Kotlin `CaptionPipeline` (VAD segmenter → `SpeechRecognizer` → `Translator` → `CaptionStabilizer`) whose output is drawn by `OverlayRenderer`. ASR and translation sit behind interfaces so the later cloud plan adds implementations without touching the pipeline.

**Tech Stack:** Kotlin, Jetpack Compose, DataStore, JUnit4 + kotlinx-coroutines-test, sherpa-onnx (Whisper int8) for ASR, Silero VAD via onnxruntime-android, ML Kit Translate, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-17-live-translated-captions-design.md`

**Out of scope for this plan (later plans):** backend, cloud pipeline, Play Billing, metering, Play Store policy declarations, source-language auto-detection (spec open item; v1 uses a user-chosen source language).

## Global Constraints

- minSdk 29 (Android 10, required by `AudioPlaybackCapture`); package `com.voicecaption`.
- Audio: 16 kHz, mono, PCM 16-bit capture; VAD frames are 512 samples (32 ms) as `FloatArray` in [-1, 1].
- Foreground service declares type `mediaProjection`; overlay uses `SYSTEM_ALERT_WINDOW` (`TYPE_APPLICATION_OVERLAY`).
- Free tier is fully on-device: audio never leaves the phone. `INTERNET` is used only to download Whisper models and ML Kit language packs.
- Whisper models: `base` and `small`, quantized (int8). Target delay 2–4 s.
- Segments are 2–6 s typical; concretely: `hangoverMs=400`, `minSpeechMs=300`, `maxSegmentMs=6000`, `overlapMs=500` (overlap carried only across forced cuts).
- MediaProjection consent is requested every session; never persisted.
- Core logic (`core/`, `vad/`, `caption/`, `pipeline/`) is pure Kotlin with no Android imports so it runs as JVM unit tests.

## Review Focus

1. Source language equals target language: skip translation and show the source text (test in Task 8).
2. Music or noise only, so ASR returns blank or a bracket tag such as `[Music]`: show nothing, don't flash an empty caption (Task 8).
3. User stops the projection from the system notification or revokes the overlay permission mid-film: service stops cleanly and the overlay is removed (Task 10).
4. Language pack or model not yet downloaded and device offline: show a clear message, don't crash (Tasks 5, 7, 11).
5. Continuous speech with no pause (singing, loud scene): segment is force-cut at 6 s with 0.5 s overlap, not buffered forever (Task 2).

---

## File Structure

```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml
.github/workflows/ci.yml
app/build.gradle.kts, app/src/main/AndroidManifest.xml
app/src/main/java/com/voicecaption/
  core/Models.kt                 PcmFrame, SpeechSegment, Caption, CaptionSettings
  vad/VoiceActivityDetector.kt   interface + SileroVad
  vad/VadSegmenter.kt            frames -> SpeechSegment (pure)
  caption/CaptionStabilizer.kt   partial/final replacement, expiry (pure)
  caption/CaptionFormatter.kt    captions -> display lines (pure)
  audio/CaptureBlockedDetector.kt DRM/silence detection (pure)
  asr/SpeechRecognizer.kt        interface
  asr/WhisperLocal.kt            sherpa-onnx impl
  asr/ModelSelector.kt           thermal/battery model choice (pure)
  asr/ModelManager.kt            download + checksum + storage
  translate/Translator.kt        interface
  translate/MlKitTranslator.kt   ML Kit impl + pack download
  pipeline/CaptionPipeline.kt    wiring + filters (pure)
  overlay/OverlayRenderer.kt     WindowManager view
  audio/AudioCapture.kt          AudioPlaybackCapture
  service/CaptureService.kt      foreground service, lifecycle
  settings/SettingsRepository.kt DataStore
  ui/MainActivity.kt, ui/SettingsScreen.kt
app/src/test/java/com/voicecaption/...    JVM tests mirroring the above
app/src/androidTest/...                    device tests (ASR, translator)
```

---

### Task 1: Project scaffold, core models, CI

**Files:**
- Create: Gradle files, manifest, `.github/workflows/ci.yml`, `core/Models.kt`, `app/src/test/.../core/ModelsTest.kt`

**Interfaces:**
- Produces (in `core/Models.kt`):
  - `class PcmFrame(val samples: FloatArray, val timestampMs: Long)`
  - `class SpeechSegment(val samples: FloatArray, val startMs: Long, val endMs: Long)`
  - `data class Caption(val id: Long, val sourceText: String, val translatedText: String?, val isFinal: Boolean, val updatedAtMs: Long)`
  - `data class CaptionSettings(val sourceLang: String = "en", val targetLang: String = "en", val fontSizeSp: Int = 18, val opacity: Float = 0.8f, val bilingual: Boolean = false, val modelSize: ModelSize = ModelSize.BASE)`
  - `enum class ModelSize { BASE, SMALL }`

- [ ] **Step 1: Write the failing test** `ModelsTest.defaultSettingsAreReadable` asserting `CaptionSettings()` has `fontSizeSp == 18`, `opacity == 0.8f`, `bilingual == false`, `modelSize == ModelSize.BASE`.
- [ ] **Step 2: Create the Gradle project** (Kotlin DSL, version catalog, one `app` module, Compose enabled, minSdk 29, JUnit4 + coroutines-test as test deps). Add the manifest with `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `SYSTEM_ALERT_WINDOW`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `INTERNET` and an empty `MainActivity`.
- [ ] **Step 3: Run** `./gradlew testDebugUnitTest --tests '*ModelsTest*'`. Expected: FAIL (types missing).
- [ ] **Step 4: Implement the types** in `core/Models.kt` exactly as listed above.
- [ ] **Step 5: Add CI**: `ci.yml` runs on pull requests with JDK 17 and `./gradlew testDebugUnitTest lintDebug assembleDebug`. If the Android SDK is missing in the working container, CI is the source of truth for this and every later task.
- [ ] **Step 6: Run** the test again. Expected: PASS.
- [ ] **Step 7: Commit** `feat: android scaffold, core models, CI`.

---

### Task 2: VadSegmenter

**Files:**
- Create: `vad/VoiceActivityDetector.kt`, `vad/VadSegmenter.kt`
- Test: `app/src/test/.../vad/VadSegmenterTest.kt`

**Interfaces:**
- Consumes: `PcmFrame`, `SpeechSegment`
- Produces:
  - `fun interface VoiceActivityDetector { fun isSpeech(frame: FloatArray): Boolean }`
  - `data class VadConfig(val frameMs: Int = 32, val hangoverMs: Int = 400, val minSpeechMs: Int = 300, val maxSegmentMs: Int = 6000, val overlapMs: Int = 500)`
  - `class VadSegmenter(vad: VoiceActivityDetector, config: VadConfig = VadConfig())` with `fun push(frame: PcmFrame): List<SpeechSegment>` and `fun flush(): SpeechSegment?`

- [ ] **Step 1: Write failing tests** using a `FakeVad` driven by a `BooleanArray` (one entry per 32 ms frame):
  - `silenceProducesNoSegments`: 100 silent frames → `push` never returns a segment.
  - `speechFollowedByHangoverClosesOneSegment`: 50 speech frames then 13 silent frames (≥ 400 ms) → exactly one segment, `endMs - startMs` within one frame of 1600 ms plus hangover.
  - `blipShorterThanMinSpeechIsDropped`: 5 speech frames (160 ms) then silence → no segment.
  - **(Review Focus 5)** `continuousSpeechIsForceCutAt6sWithOverlap`: 250 speech frames (8 s) → first segment is 6000 ms; the next segment's `startMs` equals the first's `endMs - 500`.
  - `flushEmitsPendingSpeech`: 30 speech frames then `flush()` → one segment; a second `flush()` returns null.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*VadSegmenterTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement** `VadSegmenter` as a small state machine (IDLE / IN_SPEECH) holding a growing `FloatArray` buffer; on a forced cut, keep the last `overlapMs` of samples as the start of the next segment.
- [ ] **Step 4: Run** the tests. Expected: PASS.
- [ ] **Step 5: Commit** `feat: vad segmenter with forced cuts`.

---

### Task 3: CaptionStabilizer and CaptionFormatter

**Files:**
- Create: `caption/CaptionStabilizer.kt`, `caption/CaptionFormatter.kt`
- Test: `.../caption/CaptionStabilizerTest.kt`, `.../caption/CaptionFormatterTest.kt`

**Interfaces:**
- Consumes: `Caption`
- Produces:
  - `class CaptionStabilizer(maxLines: Int = 2, displayMs: Long = 4000)` with `fun onSourceText(id: Long, text: String, nowMs: Long): List<Caption>`, `fun onTranslated(id: Long, text: String, nowMs: Long): List<Caption>`, `fun expire(nowMs: Long): List<Caption>` (each returns the current visible list)
  - `object CaptionFormatter { fun lines(captions: List<Caption>, bilingual: Boolean): List<String> }`

- [ ] **Step 1: Write failing tests:**
  - `translatedReplacesSourceForSameId`: `onSourceText(1,"hola")` then `onTranslated(1,"hello")` → one caption, `isFinal == true`, `translatedText == "hello"`.
  - `blankTextIsIgnored`: `onSourceText(1,"  ", 0)` → empty list.
  - `onlyLastTwoCaptionsKept`: three ids → the oldest is gone.
  - `expireRemovesCaptionsOlderThanDisplayMs`: caption updated at 0, `expire(4001)` → empty; `expire(3999)` → still present.
  - Formatter: `lines` returns `translatedText` per caption when final and non-bilingual; with `bilingual = true` returns source line then translated line; when not yet translated, returns the source text.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement** both classes (stabilizer keeps an insertion-ordered map keyed by id).
- [ ] **Step 4: Run** the tests. Expected: PASS.
- [ ] **Step 5: Commit** `feat: caption stabilizer and formatter`.

---

### Task 4: CaptureBlockedDetector

**Files:**
- Create: `audio/CaptureBlockedDetector.kt`
- Test: `.../audio/CaptureBlockedDetectorTest.kt`

**Interfaces:**
- Produces: `class CaptureBlockedDetector(isMediaPlaying: () -> Boolean, silenceRms: Float = 0.001f, windowMs: Long = 5000)` with `fun onFrame(frame: PcmFrame): Boolean` (returns true while capture is judged blocked)

- [ ] **Step 1: Write failing tests:**
  - `silenceWhileMediaPlayingForWindowIsBlocked`: all-zero frames spanning 5000 ms with `isMediaPlaying = { true }` → true on the final frame, false before 5000 ms.
  - `silenceWhileNothingPlayingIsNotBlocked`: same frames, `isMediaPlaying = { false }` → never true.
  - `anySoundResetsTheWindow`: silence for 4000 ms, one frame with RMS 0.1, silence for 4000 ms → never true.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement** by tracking the timestamp of the last non-silent frame and whether media is playing.
- [ ] **Step 4: Run** the tests. Expected: PASS.
- [ ] **Step 5: Commit** `feat: detect blocked audio capture`.

---

### Task 5: ModelSelector and ModelManager

**Files:**
- Create: `asr/ModelSelector.kt`, `asr/ModelManager.kt`, `asr/ModelCatalog.kt`
- Test: `.../asr/ModelSelectorTest.kt`; `.../asr/ModelManagerTest.kt`

**Interfaces:**
- Consumes: `ModelSize`
- Produces:
  - `object ModelSelector { fun choose(preferred: ModelSize, thermalStatus: Int, batteryPct: Int): ModelSize }`: returns `BASE` when `thermalStatus >= 2` (`THERMAL_STATUS_MODERATE`) or `batteryPct < 15`, else `preferred`.
  - `class ModelManager(modelsDir: File, downloader: Downloader)` with `fun isInstalled(size: ModelSize): Boolean`, `suspend fun install(size: ModelSize, onProgress: (Float) -> Unit): Result<File>`
  - `fun interface Downloader { suspend fun download(url: String, dest: File, onProgress: (Float) -> Unit) }`
  - `ModelCatalog`: per `ModelSize`, the download URL(s) and SHA-256 of the sherpa-onnx Whisper int8 files. The implementer takes URLs and hashes from the official sherpa-onnx pre-converted Whisper release and records the source link in a comment.

- [ ] **Step 1: Write failing tests:**
  - Selector: `(SMALL, thermal=2, battery=80) -> BASE`; `(SMALL, 0, 10) -> BASE`; `(SMALL, 0, 80) -> SMALL`; `(BASE, 0, 80) -> BASE`.
  - **(Review Focus 4)** Manager with a fake `Downloader` that throws `IOException`: `install` returns a failure, leaves no partial file, and `isInstalled` stays false.
  - Manager with a fake downloader writing wrong bytes: `install` fails on checksum mismatch and deletes the file.
  - Manager with correct bytes (fake catalog entry): `install` succeeds, `isInstalled` is true, progress reaches 1.0.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement** download-to-temp-file, verify SHA-256, then atomic rename into `modelsDir`.
- [ ] **Step 4: Run** the tests. Expected: PASS.
- [ ] **Step 5: Commit** `feat: whisper model selection and download manager`.

---

### Task 6: SpeechRecognizer interface and WhisperLocal

**Files:**
- Create: `asr/SpeechRecognizer.kt`, `asr/WhisperLocal.kt`
- Test: `app/src/androidTest/.../asr/WhisperLocalTest.kt`; test asset `app/src/androidTest/assets/hello_world_16k.wav` (a short clear English "hello world" recording; commit it with a note that it is a self-made recording)

**Interfaces:**
- Consumes: `SpeechSegment`, `ModelSize`, `ModelManager`
- Produces:
  - `interface SpeechRecognizer { suspend fun transcribe(segment: SpeechSegment, language: String): String; fun close() }`
  - `class WhisperLocal(modelDir: File, size: ModelSize) : SpeechRecognizer`

- [ ] **Step 1: Write the failing instrumented test** `transcribesHelloWorld`: install the `BASE` model, load the wav asset as a `SpeechSegment`, assert `transcribe(segment, "en").lowercase()` contains `"hello"`.
- [ ] **Step 2: Run** `./gradlew connectedDebugAndroidTest --tests '*WhisperLocalTest*'` on a device or emulator. Expected: FAIL (class missing).
- [ ] **Step 3: Implement `WhisperLocal`** with the sherpa-onnx `OfflineRecognizer` configured for Whisper (encoder/decoder/tokens from `modelDir`, given `language`); run inference on `Dispatchers.Default`; return trimmed text. `close()` releases the native recognizer.
- [ ] **Step 4: Run** the test. Expected: PASS.
- [ ] **Step 5: Commit** `feat: on-device whisper recognizer`.

---

### Task 7: Translator interface and MlKitTranslator

**Files:**
- Create: `translate/Translator.kt`, `translate/MlKitTranslator.kt`
- Test: `app/src/androidTest/.../translate/MlKitTranslatorTest.kt`

**Interfaces:**
- Produces:
  - `interface Translator { suspend fun translate(text: String, sourceLang: String, targetLang: String): String; suspend fun isReady(sourceLang: String, targetLang: String): Boolean; suspend fun prepare(sourceLang: String, targetLang: String, onProgress: (Boolean) -> Unit): Result<Unit> }`
  - `class MlKitTranslator(context: Context) : Translator`

- [ ] **Step 1: Write the failing instrumented test** `translatesEnglishToSpanish`: `prepare("en","es")`, then assert `translate("good morning","en","es")` is non-blank and differs from the input. Also **(Review Focus 4)** `translateFailsCleanlyWhenPackMissingOffline`: with no pack and no network, `isReady` is false and `translate` throws `TranslatorNotReadyException` (define this exception in `Translator.kt`).
- [ ] **Step 2: Run** `./gradlew connectedDebugAndroidTest --tests '*MlKitTranslatorTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement** with ML Kit `Translation.getClient` and `RemoteModelManager`; cache one client per language pair; map ML Kit failures to `Result.failure`.
- [ ] **Step 4: Run** the test. Expected: PASS.
- [ ] **Step 5: Commit** `feat: ml kit on-device translator`.

---

### Task 8: CaptionPipeline

**Files:**
- Create: `pipeline/CaptionPipeline.kt`
- Test: `.../pipeline/CaptionPipelineTest.kt` (JVM, fakes for VAD, recognizer, translator)

**Interfaces:**
- Consumes: `VadSegmenter`, `SpeechRecognizer`, `Translator`, `CaptionStabilizer`, `CaptionSettings`
- Produces: `class CaptionPipeline(segmenter: VadSegmenter, recognizer: SpeechRecognizer, translator: Translator, stabilizer: CaptionStabilizer, settings: () -> CaptionSettings, scope: CoroutineScope, clock: () -> Long)` with `fun onFrame(frame: PcmFrame)`, `val captions: StateFlow<List<Caption>>`, `val errors: SharedFlow<PipelineError>`, `fun stop()`; and `sealed interface PipelineError { object TranslatorNotReady; data class Recognizer(val cause: Throwable) }`

- [ ] **Step 1: Write failing tests** (coroutines-test, fakes):
  - `speechBecomesTranslatedCaption`: fake VAD marks frames as speech then silence; fake recognizer returns `"hola"`, fake translator returns `"hello"`; `captions` ends with one final caption `translatedText == "hello"`; a source-only (non-final) caption was emitted first.
  - **(Review Focus 1)** `sameSourceAndTargetSkipsTranslation`: settings `en`→`en`; translator is never called and the caption shows the source text.
  - **(Review Focus 2)** `blankAndBracketTagTranscriptsShowNothing`: recognizer returns `""`, `" "`, `"[Music]"`, `"(applause)"` in turn → `captions` stays empty.
  - **(Review Focus 4)** `translatorNotReadyEmitsErrorAndKeepsSourceCaption`: translator throws `TranslatorNotReadyException` → `errors` emits `TranslatorNotReady` and the source caption is still shown.
  - `recognizerFailureDoesNotKillThePipeline`: first segment throws, second succeeds → second caption appears and `errors` emitted once.
  - Segments are processed in order even when the recognizer is slower than the audio (one worker coroutine reading from a `Channel`).
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*CaptionPipelineTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement** `CaptionPipeline`: `onFrame` pushes into `VadSegmenter` and sends segments to a bounded `Channel` (capacity 4, drop-oldest so latency stays bounded); one worker transcribes, filters blank/bracket-only text, emits the source caption, then translates unless `sourceLang == targetLang`.
- [ ] **Step 4: Run** the tests. Expected: PASS.
- [ ] **Step 5: Commit** `feat: caption pipeline wiring and filters`.

---

### Task 9: OverlayRenderer

**Files:**
- Create: `overlay/OverlayRenderer.kt`
- Test: manual checklist below (WindowManager UI; the text logic is already covered by `CaptionFormatterTest`)

**Interfaces:**
- Consumes: `Caption`, `CaptionFormatter`, `CaptionSettings`
- Produces: `class OverlayRenderer(context: Context)` with `fun canDrawOverlays(): Boolean`, `fun show()`, `fun render(captions: List<Caption>, settings: CaptionSettings)`, `fun showMessage(text: String)`, `fun hide()`; `hide()` is idempotent.

- [ ] **Step 1: Implement** a `TextView` in a `TYPE_APPLICATION_OVERLAY` window with `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`, bottom-center by default, draggable vertically, background alpha from `settings.opacity`, text size from `settings.fontSizeSp`, lines from `CaptionFormatter.lines`. `render` with an empty list hides the text but keeps the window.
- [ ] **Step 2: Manual check on a device** (record results in the PR): overlay stays above YouTube; dragging works; touches outside the caption reach the video app; `hide()` twice does not crash; changing opacity/font size applies on the next `render`.
- [ ] **Step 3: Commit** `feat: floating caption overlay`.

---

### Task 10: AudioCapture and CaptureService

**Files:**
- Create: `audio/AudioCapture.kt`, `service/CaptureService.kt`, `service/ServiceState.kt`
- Modify: `AndroidManifest.xml` (declare service with `foregroundServiceType="mediaProjection"`)
- Test: `.../service/ServiceStateTest.kt` (JVM) + manual checklist

**Interfaces:**
- Consumes: `CaptionPipeline`, `OverlayRenderer`, `CaptureBlockedDetector`, `ModelManager`, `ModelSelector`, `SettingsRepository` (Task 11 provides the settings flow; this task takes `settings: () -> CaptionSettings` so it does not block on it)
- Produces:
  - `class AudioCapture(projection: MediaProjection)` with `fun frames(): Flow<PcmFrame>` (16 kHz mono, 512-sample `FloatArray` frames, `AudioPlaybackCaptureConfiguration` matching media/game/unknown usages), `fun stop()`
  - `enum class ServiceState { IDLE, STARTING, RUNNING, BLOCKED, STOPPED }` and `object ServiceStateMachine { fun next(current: ServiceState, event: ServiceEvent): ServiceState }` with `ServiceEvent { Start, Ready, CaptureBlocked, CaptureAudible, ProjectionStopped, OverlayPermissionLost, UserStop }`
  - `CaptureService` companion `fun start(context, resultCode, data)` / `fun stop(context)`

- [ ] **Step 1: Write failing tests** for `ServiceStateMachine`: `IDLE+Start→STARTING`; `STARTING+Ready→RUNNING`; `RUNNING+CaptureBlocked→BLOCKED`; `BLOCKED+CaptureAudible→RUNNING`; **(Review Focus 3)** `ProjectionStopped`, `OverlayPermissionLost` and `UserStop` from any state → `STOPPED`; events after `STOPPED` stay `STOPPED`.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*ServiceStateTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement the state machine, then `AudioCapture` and `CaptureService`.** The service starts as a foreground service with a persistent notification (with a Stop action) *before* calling `getMediaProjection`; it registers `MediaProjection.Callback.onStop` → `ProjectionStopped`; checks `canDrawOverlays()` on start and on each frame batch; on `STOPPED` it cancels the pipeline scope, calls `OverlayRenderer.hide()` and `stopSelf()`. On `BLOCKED` it calls `showMessage("This app doesn't allow audio capture")`. Before starting, it picks the model with `ModelSelector.choose(...)` using `PowerManager.currentThermalStatus` and battery level.
- [ ] **Step 4: Run** the unit tests. Expected: PASS.
- [ ] **Step 5: Manual check on a device** (record in the PR): start with YouTube playing, captions appear; swipe away the projection via the system "stop sharing" control → overlay disappears and notification goes; revoke overlay permission while running → service stops without crashing; start while Netflix plays → "doesn't allow audio capture" message after about 5 s.
- [ ] **Step 6: Commit** `feat: playback audio capture and foreground service`.

> Milestone: with the translator set to source == target, this is spec build step 1 (raw captions in an overlay).

---

### Task 11: Settings and UI

**Files:**
- Create: `settings/SettingsRepository.kt`, `ui/MainActivity.kt`, `ui/SettingsScreen.kt`
- Test: `.../settings/SettingsRepositoryTest.kt`

**Interfaces:**
- Consumes: `CaptionSettings`, `ModelManager`, `Translator.prepare`, `CaptureService.start/stop`
- Produces: `class SettingsRepository(store: DataStore<Preferences>)` with `val settings: Flow<CaptionSettings>` and `suspend fun update(transform: (CaptionSettings) -> CaptionSettings)`

- [ ] **Step 1: Write failing tests** with an in-memory/temp-file DataStore: defaults equal `CaptionSettings()`; after `update { it.copy(targetLang = "es", bilingual = true) }` the flow emits those values; values survive creating a new repository over the same file.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement `SettingsRepository`**, then the Compose UI: source/target language pickers, font size, opacity, bilingual toggle, model size (BASE/SMALL), Start/Stop button. First-run flow: explain and request overlay permission and notification permission; download the chosen Whisper model and the ML Kit pair with visible progress. **(Review Focus 4)** If the download fails or the device is offline, show the error with a Retry button and keep Start disabled; Start launches `MediaProjectionManager.createScreenCaptureIntent()` and passes the result to `CaptureService.start`.
- [ ] **Step 4: Run** the tests. Expected: PASS.
- [ ] **Step 5: Commit** `feat: settings and onboarding UI`.

---

### Task 12: Benchmarks and default model choice

**Files:**
- Create: `app/src/androidTest/.../bench/LatencyBenchmarkTest.kt`, `docs/benchmarks/free-tier.md`
- Test: the benchmark itself

**Interfaces:**
- Consumes: `WhisperLocal`, `MlKitTranslator`, `CaptionPipeline`

- [ ] **Step 1: Write the instrumented benchmark** `endToEndLatencyPerModel`: for each of a fixed set of at least 5 short film-style clips (committed under `androidTest/assets`, with reference transcripts) and each `ModelSize`, measure segment-end → translated-caption time and compute word error rate against the reference. Log median and p95.
- [ ] **Step 2: Run** on a mid-range device and record results in `docs/benchmarks/free-tier.md` (device model, Android version, numbers).
- [ ] **Step 3: Decide the default.** Keep `ModelSize.BASE` as default unless `BASE` misses the 2–4 s target on the mid-range device; record the decision and reason in `docs/benchmarks/free-tier.md`. This closes the spec's "Whisper model size vs. phone speed" open item.
- [ ] **Step 4: Commit** `test: free-tier latency and accuracy benchmark`.
