# Bundled assets

## silero_vad.onnx

- What: Silero VAD model, the version distributed for sherpa-onnx (Silero VAD v5 export).
- Source: https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
  (release tag `asr-models`, asset `silero_vad.onnx`, downloaded 2026-10-17).
- Size: 643854 bytes
- SHA-256: `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6`
- License: MIT, Copyright (c) 2020-present Silero Team (https://github.com/snakers4/silero-vad).
- Integrity: `SileroAssetTest` asserts the SHA-256 above. If you replace the model, update both.
- Used by `vad/SileroVad.kt` through the sherpa-onnx `Vad` API (16 kHz, 512-sample windows).
