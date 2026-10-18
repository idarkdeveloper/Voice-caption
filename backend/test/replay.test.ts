import { readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it } from "vitest";
import { createDeepgramVendor } from "../src/asr/deepgram.js";
import { signDevToken, type SessionClaims } from "../src/auth.js";
import type { Config } from "../src/config.js";
import { createLogger } from "../src/log.js";
import { buildServer } from "../src/server.js";
import { percentile, summarize, type ResultRecord } from "../tools/replay/metrics.js";
import { runReplay } from "../tools/replay/replay.js";
import { segmentSpeech } from "../tools/replay/vad.js";
import { encodeWav, parseWav, readWav } from "../tools/replay/wav.js";
import { startFakeDeepgram, type FakeDeepgram } from "./fake-deepgram/server.js";

const here = dirname(fileURLToPath(import.meta.url));
const BENCH = join(here, "../../app/src/androidTest/assets/bench");
const wavs = readdirSync(BENCH).filter((f) => f.endsWith(".wav")).sort();

function tone(ms: number, amp = 8000): Int16Array {
  const n = ms * 16;
  const a = new Int16Array(n);
  for (let i = 0; i < n; i++) a[i] = Math.round(amp * Math.sin((2 * Math.PI * 440 * i) / 16000));
  return a;
}
const silence = (ms: number) => new Int16Array(ms * 16);
const concat = (...parts: Int16Array[]) => {
  const out = new Int16Array(parts.reduce((a, p) => a + p.length, 0));
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
};

describe("wav reader", () => {
  it("parses the repo bench clips as 16 kHz mono", () => {
    expect(wavs.length).toBeGreaterThanOrEqual(7);
    for (const f of wavs) {
      const w = readWav(join(BENCH, f));
      expect(w.sampleRate).toBe(16000);
      expect(w.samples.length).toBeGreaterThan(16000);
      expect(w.samples.some((s) => s !== 0)).toBe(true);
    }
  });

  it("round-trips and rejects stereo and 8-bit", () => {
    const good = encodeWav(Int16Array.from([1, -2, 3]), 16000);
    expect(Array.from(parseWav(good).samples)).toEqual([1, -2, 3]);
    const stereo = Buffer.from(good);
    stereo.writeUInt16LE(2, 22);
    expect(() => parseWav(stereo)).toThrow(/mono/);
    const eight = Buffer.from(good);
    eight.writeUInt16LE(8, 34);
    expect(() => parseWav(eight)).toThrow(/16-bit/);
    expect(() => parseWav(Buffer.from("not a wav file at all"))).toThrow();
  });
});

describe("segmentSpeech", () => {
  it("finds two segments in tone/silence/tone, ends including the 400 ms hangover", () => {
    const segs = segmentSpeech(concat(tone(1000), silence(1000), tone(1000)), 16000);
    expect(segs).toHaveLength(2);
    const [a, b] = segs as [{ startMs: number; endMs: number }, { startMs: number; endMs: number }];
    expect(a.startMs).toBe(0);
    expect(a.endMs).toBeGreaterThanOrEqual(1400);
    expect(a.endMs).toBeLessThanOrEqual(1400 + 64);
    expect(b.startMs).toBeGreaterThanOrEqual(1950);
    expect(b.startMs).toBeLessThanOrEqual(2000);
  });

  it("drops bursts shorter than 300 ms and keeps one segment across a pause shorter than the hangover", () => {
    expect(segmentSpeech(concat(silence(500), tone(200), silence(800)), 16000)).toHaveLength(0);
    expect(segmentSpeech(concat(tone(500), silence(300), tone(500)), 16000)).toHaveLength(1);
  });

  it("finds speech in every bench clip", () => {
    for (const f of wavs) {
      const w = readWav(join(BENCH, f));
      const segs = segmentSpeech(w.samples, w.sampleRate);
      expect(segs.length, f).toBeGreaterThanOrEqual(1);
      const total = segs.reduce((a, s) => a + (s.endMs - s.startMs), 0);
      expect(total, f).toBeLessThanOrEqual((w.samples.length / 16) + 1);
    }
  });
});

describe("percentile / summarize", () => {
  it("nearest rank on 1..20, order independent, empty throws", () => {
    const v = Array.from({ length: 20 }, (_, i) => i + 1);
    expect(percentile(v, 95)).toBe(19);
    expect(percentile(v, 50)).toBe(10);
    expect(percentile([...v].reverse(), 95)).toBe(19);
    expect(percentile([7], 95)).toBe(7);
    expect(() => percentile([], 50)).toThrow();
  });

  it("summarizes segments and streams", () => {
    const recs: ResultRecord[] = [
      { label: "x", clip: "a", seg: 1, ttfp_ms: 100, final_after_finalize_ms: 200, complete: true, audio_ms: 1000 },
      { label: "x", clip: "a", seg: 2, ttfp_ms: null, final_after_finalize_ms: 300, complete: false, audio_ms: 1000 },
      { label: "x", type: "stream", open_to_ready_ms: 50, audio_ms_sent: 2000 },
    ];
    const s = summarize(recs);
    expect(s).toMatchObject({ label: "x", segments: 2, streams: 1, complete_false_share: 0.5, audio_ms_sent_total: 2000 });
    expect(s.ttfp_ms).toEqual({ p50: 100, p95: 100 });
    expect(s.final_after_finalize_ms).toEqual({ p50: 200, p95: 300 });
    expect(s.open_to_ready_ms).toEqual({ p50: 50, p95: 50 });
  });
});

describe("harness self-test against the real proxy and the fake vendor", () => {
  const JWT_SECRET = "replay-selftest-secret-0123456789";
  const claims: SessionClaims = { sub: "u", sid: "s", kind: "paid", allow_ms: 600_000, lang: "en", dev: "d", asr: "us", cfgv: 1 };
  let fake: FakeDeepgram | undefined;
  let server: Awaited<ReturnType<typeof buildServer>> | undefined;

  afterEach(async () => {
    await server?.close();
    await fake?.stop();
  });

  it("measures finalize latency 250 ms, ttfp 400 ms, sends no silence and meters bytes/32", async () => {
    fake = await startFakeDeepgram({
      finalizeResponseDelayMs: 250,
      resultDelayMs: 400,
      interimEveryBytes: 3072, // one interim per 96 ms batch, so the first partial follows the first batch
      noAudioTimeoutMs: 3_600_000,
    });
    const cfg: Config = {
      port: 0,
      region: "local",
      deepgramApiKey: "dg-selftest-key-never-logged",
      deepgramHosts: { us: fake.url, eu: fake.url },
      jwtSecret: JWT_SECRET,
      supportedLanguages: new Set(["en"]),
      maxStreamMs: 3_300_000,
    };
    server = await buildServer(cfg, {
      vendor: createDeepgramVendor({ apiKey: cfg.deepgramApiKey, hosts: cfg.deepgramHosts }),
      log: createLogger(() => {}, { strict: true }),
    });
    const { port } = await server.listen();
    const token = await signDevToken(claims, JWT_SECRET);

    const clipNames = ["de_01.wav", "fr_01.wav", "hi_01.wav"].map((f) => join(BENCH, f));
    const records = await runReplay({ url: `ws://127.0.0.1:${port}/v1/stream`, token, clips: clipNames, repeat: 1, label: "selftest" });

    const sum = summarize(records);
    expect(sum.segments).toBeGreaterThanOrEqual(3);
    expect(sum.complete_false_share).toBe(0);
    expect(Math.abs(sum.final_after_finalize_ms!.p50 - 250)).toBeLessThanOrEqual(40);
    expect(sum.ttfp_ms).not.toBeNull();
    expect(Math.abs(sum.ttfp_ms!.p50 - 400)).toBeLessThanOrEqual(60);
    expect(sum.open_to_ready_ms!.p50).toBeLessThan(500);

    // no silence beyond the hangover: the vendor received exactly the speech segments (incl. hangover)
    let expectedBytes = 0;
    for (const p of clipNames) {
      const w = readWav(p);
      for (const s of segmentSpeech(w.samples, w.sampleRate)) expectedBytes += (s.endMs - s.startMs) * 32;
    }
    expect(fake.receivedAudioBytes()).toBeLessThanOrEqual(expectedBytes);
    expect(fake.receivedAudioBytes()).toBe(expectedBytes);

    // metering unit: audio_ms_sent == fake bytes / 32
    expect(sum.audio_ms_sent_total).toBe(fake.receivedAudioBytes() / 32);
  }, 40_000);
});
