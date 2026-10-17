# Benchmark clips

These seven clips are SYNTHETIC speech from a text-to-speech engine. They are NOT film audio:
no background music, no noise, no overlapping speakers, one flat voice. They make a smoke test
and a relative comparison between models and devices. Absolute accuracy and latency on real film
dialogue will differ. Replacing them with real film clips is a follow-up for the owner (steps below).

Each clip is at most 6 s, 16 kHz mono 16-bit PCM; all seven total about 0.8 MB.

## Provenance

Generated with eSpeak NG 1.51 (GPL-3.0-or-later; the synthesized audio is not a derivative work of
the program, same as `../hello_world_16k.wav`), then converted with ffmpeg and padded with 0.3 s of
silence at each end:

    espeak-ng -v <voice> -s 150 "<reference text>" -w raw.wav
    ffmpeg -i raw.wav -ar 16000 -ac 1 -c:a pcm_s16le \
      -af "apad=pad_dur=0.3,areverse,apad=pad_dur=0.3,areverse" <file>.wav

| file | eSpeak voice | language |
|------|--------------|----------|
| en_01.wav, en_02.wav | `en-us` | English |
| es_01.wav | `es` | Spanish |
| fr_01.wav | `fr` | French |
| de_01.wav | `de` | German |
| ja_01.wav | `ja` | Japanese (scored with character error rate) |
| hi_01.wav | `hi` | Hindi |

eSpeak NG's Japanese and Hindi voices are robotic and may be hard for Whisper; a high error rate
on them says more about the TTS than the model.

## manifest.json

A JSON array of `{"file", "lang", "reference"}`. `lang` is a Whisper/ML Kit language code,
`reference` is the exact text spoken. References without spaces (Japanese, Chinese) are scored with
character error rate, the rest with word error rate.

## Adding real film clips

1. Cut a clip of at most 6 s of clear dialogue (mind the licence; keep the repo small).
2. Convert it: `ffmpeg -i in.mp4 -t 6 -ar 16000 -ac 1 -c:a pcm_s16le app/src/androidTest/assets/bench/<lang>_NN.wav`
3. Write down the exact words spoken as the reference transcript.
4. Add `{"file": "<lang>_NN.wav", "lang": "<code>", "reference": "<transcript>"}` to `manifest.json`.
5. Run the benchmark (see `docs/benchmarks/free-tier.md`). No code change is needed.
