# Free-tier latency and accuracy benchmark

Status: **NOT RUN.** No device or emulator was available when the harness was written, so this
file contains no measurements. The spec's open item "Whisper model size vs. phone speed" is
**NOT closed** until someone runs the benchmark on a real mid-range phone and fills in the table.

## What it measures

`LatencyBenchmarkTest.endToEndLatencyPerModel` (androidTest, package `com.voicecaption.bench`):
for each model and each clip in `app/src/androidTest/assets/bench/manifest.json` it loads the clip
as one `SpeechSegment`, runs one untimed warm-up, then times 3 runs of
`WhisperLocal.transcribe` and (non-English clips, target `en`) `MlKitTranslator.translate`.
Total = transcribe + translate, i.e. segment end to translated caption. English clips have no
translation step; clips whose language pack cannot be prepared are recorded as
`translation: skipped`. Accuracy is the word error rate (character error rate for references
without spaces, e.g. Japanese) against the manifest reference. The test asserts only that every
transcript is non-empty. It does not assert latency or error thresholds.

## How to run

Use a mid-range phone with network access (first run downloads the ~160 MB BASE model and ML Kit
language packs) and keep it unplugged-idle but awake, not in battery saver.

    export ANDROID_HOME=/opt/android-sdk
    ./gradlew connectedDebugAndroidTest --tests '*LatencyBenchmarkTest*'

To include SMALL (a larger download):

    ./gradlew connectedDebugAndroidTest --tests '*LatencyBenchmarkTest*' \
      -Pandroid.testInstrumentationRunnerArguments.bench.small=true

Results appear in logcat under tag `CaptionlyBench` (`adb logcat -s CaptionlyBench`) and in
`bench-results.json` in the app's files directory. Pull it with:

    adb shell run-as com.voicecaption cat files/bench-results.json > bench-results.json

If the debug app id differs from `com.voicecaption`, substitute it (the test app's target context
is the app under test, so the file lives in the app's data directory).

## Clips and their limits

Seven synthetic eSpeak NG clips (English x2, Spanish, French, German, Japanese, Hindi), each at
most 6 s, about 0.8 MB total. They are not film audio: no music, noise or overlapping speech, so
they are a smoke and relative benchmark only and probably flatter real-world accuracy. Japanese
and Hindi eSpeak voices are robotic. Provenance and the steps to add real film clips are in
`app/src/androidTest/assets/bench/README.md`.

## Results

| device | Android version | model | clip count | median ms | p95 ms | mean error rate |
|--------|-----------------|-------|------------|-----------|--------|-----------------|
| NOT RUN: no device | NOT RUN: no device | BASE | NOT RUN: no device | NOT RUN: no device | NOT RUN: no device | NOT RUN: no device |
| NOT RUN: no device | NOT RUN: no device | SMALL | NOT RUN: no device | NOT RUN: no device | NOT RUN: no device | NOT RUN: no device |

Median and p95 are over every timed run (clips x 3) of total ms. Mean error rate averages
per-clip WER/CER, so it mixes the two metrics.

## Decision

Default stays `ModelSize.BASE` unless BASE misses the 2-4 s target on a mid-range device (median
total > 4000 ms); if so, evaluate shipping a smaller/quantized model or reducing `maxSegmentMs`.

Decision recorded: none yet. This is pending a device run; the code default remains `BASE`.
The spec's open item "Whisper model size vs. phone speed" is NOT closed.
