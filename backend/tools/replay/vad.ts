/**
 * Energy VAD segmenter mirroring the app's VadSegmenter defaults (32 ms frames, 400 ms hangover,
 * 300 ms minimum speech); hangover frames are part of the segment. The app's 6 s forced cut is not
 * mirrored: the harness measures finalize latency per utterance.
 */
export const FRAME_MS = 32;
export const HANGOVER_MS = 400;
export const MIN_SPEECH_MS = 300;
/** Frame RMS (int16 scale) at or above which a frame counts as speech. */
export const ENERGY_THRESHOLD = 400;

export interface SpeechSpan {
  startMs: number;
  endMs: number;
}

export function segmentSpeech(samples: Int16Array, sampleRate: number): SpeechSpan[] {
  const frameLen = Math.round((sampleRate * FRAME_MS) / 1000);
  const frames = Math.floor(samples.length / frameLen);
  const out: SpeechSpan[] = [];
  let inSpeech = false;
  let startFrame = 0;
  let speechFrames = 0;
  let silenceFrames = 0;
  let lastFrame = 0;

  const close = () => {
    if (speechFrames * FRAME_MS >= MIN_SPEECH_MS) {
      out.push({ startMs: startFrame * FRAME_MS, endMs: (lastFrame + 1) * FRAME_MS });
    }
    inSpeech = false;
  };

  for (let f = 0; f < frames; f++) {
    let sum = 0;
    for (let i = f * frameLen; i < (f + 1) * frameLen; i++) sum += samples[i]! * samples[i]!;
    const speech = Math.sqrt(sum / frameLen) >= ENERGY_THRESHOLD;
    if (!inSpeech) {
      if (!speech) continue;
      inSpeech = true;
      startFrame = f;
      speechFrames = 0;
      silenceFrames = 0;
    }
    lastFrame = f;
    if (speech) {
      speechFrames++;
      silenceFrames = 0;
    } else {
      silenceFrames++;
    }
    if (silenceFrames * FRAME_MS >= HANGOVER_MS) close();
  }
  if (inSpeech) close();
  return out;
}
