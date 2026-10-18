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
language packs) and keep it awake, not in battery saver.

    export ANDROID_HOME=/opt/android-sdk
    ./gradlew connectedDebugAndroidTest --tests '*LatencyBenchmarkTest*' \
      -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true

By default Gradle uninstalls the app and test APK after the run, which deletes the app's files
(the downloaded models and `bench-results.jsonl`). The `leaveApksInstalledAfterRun` property keeps
them installed so the model download survives between runs. The property name exists in AGP 8.7.3
(this repo's version; found in its `BooleanOption` class), but it has not been exercised here:
verify it on your device and AGP version.

To include SMALL (a larger download), add
`-Pandroid.testInstrumentationRunnerArguments.bench.small=true`.

### Getting the results

Results are JSON Lines: one self-contained JSON object per line, so no joining or chunk
reassembly is needed. Record types: one `meta` (device, Android release, sdkInt, runs per clip),
one `clip` per (model, clip) (transcript, error metric and rate, transcribe/translate/total ms,
`translation` `ok` or `skipped`), and one `summary` per model (clip count, median ms, p95 ms,
mean error rate). Every line is under 3000 characters; transcripts are cut at 400 characters
(`transcriptTruncated: true`).

Primary: logcat tag `TransCapBench`, one entry per record between `BENCH_JSON_BEGIN` and
`BENCH_JSON_END` entries. Extract it right after the run (the logcat buffer is finite):

    adb logcat -d -v raw -s TransCapBench | sed -n '/BENCH_JSON_BEGIN/,/BENCH_JSON_END/{//!p}' > bench-results.jsonl

`-v raw` removes the timestamp/pid/level prefix; `{//!p}` drops the two marker lines. Check it
parses:

    python3 -c "import sys,json; [json.loads(l) for l in open('bench-results.jsonl')]; print('ok')"

Per-clip description lines and a `SUMMARY` line per model are also logged under the same tag.

Secondary: the same JSON Lines are written to `bench-results.jsonl` in the app's files directory,
available only if the app stayed installed (see above):

    adb shell run-as com.voicecaption cat files/bench-results.jsonl > bench-results.jsonl

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
