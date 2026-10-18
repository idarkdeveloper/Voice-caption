/**
 * Replay harness: streams speech clips in real time over the /v1/stream protocol and records
 * per-hop latency with the harness's own monotonic clock (no cross-machine clock comparison).
 *
 * Usage: npx tsx tools/replay/replay.ts --url <wss://host/v1/stream> --token <jwt> --clips <dir>
 *          --repeat <n> --label <region-or-vantage> --out <results.jsonl> [--summary <summary.json>] [--lang en]
 *
 * Only speech segments plus their 400 ms hangover are sent, never silence. Nothing about the audio,
 * transcripts or the token is printed.
 */
import { appendFileSync, readdirSync, writeFileSync } from "node:fs";
import { basename, join } from "node:path";
import { pathToFileURL } from "node:url";
import { parseArgs } from "node:util";
import { WebSocket } from "ws";
import { summarize, type ResultRecord, type SegmentRecord, type StreamRecord } from "./metrics.js";
import { segmentSpeech } from "./vad.js";
import { readWav } from "./wav.js";

export const BATCH_MS = 96;
const SAMPLE_RATE = 16000;
const BYTES_PER_MS = 32;
const BATCH_BYTES = BATCH_MS * BYTES_PER_MS;
const FINAL_TIMEOUT_MS = 10_000;

export interface ReplayOptions {
  url: string;
  token: string;
  clips: string[];
  repeat: number;
  label: string;
  lang?: string;
  /** Called with every record as soon as it is complete. */
  onRecord?: (r: ResultRecord) => void;
}

const now = () => performance.now();

/** Sleeps until the absolute monotonic deadline `t` (performance.now() scale). */
async function sleepUntil(t: number): Promise<void> {
  for (;;) {
    const d = t - now();
    if (d <= 0) return;
    await new Promise<void>((r) => setTimeout(r, d > 2 ? d - 1 : 0));
  }
}

interface Seen {
  firstPartialAt: Map<number, number>;
  finals: Map<number, { at: number; complete: boolean; audio_ms: number }>;
}

function toPcmBytes(samples: Int16Array, from: number, to: number): Buffer {
  const b = Buffer.alloc((to - from) * 2);
  for (let i = from; i < to; i++) b.writeInt16LE(samples[i]!, (i - from) * 2);
  return b;
}

async function replayClip(opts: ReplayOptions, clipPath: string, out: (r: ResultRecord) => void): Promise<void> {
  const wav = readWav(clipPath);
  if (wav.sampleRate !== SAMPLE_RATE) throw new Error(`${basename(clipPath)}: sample rate ${wav.sampleRate}, need 16000`);
  const spans = segmentSpeech(wav.samples, wav.sampleRate);
  const clip = basename(clipPath);

  const seen: Seen = { firstPartialAt: new Map(), finals: new Map() };
  let closed = false;
  let closeInfo = "";
  let wake: (() => void) | null = null;
  const notify = () => wake?.();
  const waitUntil = async (cond: () => boolean, ms: number, what: string): Promise<void> => {
    const end = now() + ms;
    while (!cond()) {
      if (closed) throw new Error(`stream closed while waiting for ${what} (${closeInfo})`);
      const left = end - now();
      if (left <= 0) throw new Error(`timed out waiting for ${what}`);
      await new Promise<void>((resolve) => {
        const t = setTimeout(resolve, left);
        wake = () => {
          clearTimeout(t);
          resolve();
        };
      });
      wake = null;
    }
  };

  const tOpen = now();
  const ws = new WebSocket(opts.url, { headers: { Authorization: `Bearer ${opts.token}` } });
  let readyAt = -1;
  ws.on("message", (data, isBinary) => {
    if (isBinary) return;
    const t = now();
    let m: { type?: string; seg?: number; complete?: boolean; audio_ms?: number };
    try {
      m = JSON.parse(data.toString()) as typeof m;
    } catch {
      return;
    }
    if (m.type === "ready") readyAt = t;
    else if (m.type === "partial" && typeof m.seg === "number" && !seen.firstPartialAt.has(m.seg)) {
      seen.firstPartialAt.set(m.seg, t);
    } else if (m.type === "final" && typeof m.seg === "number" && !seen.finals.has(m.seg)) {
      seen.finals.set(m.seg, { at: t, complete: m.complete === true, audio_ms: m.audio_ms ?? 0 });
    }
    notify();
  });
  ws.on("open", notify);
  ws.on("close", (code) => {
    closed = true;
    closeInfo = `code ${code}`;
    notify();
  });
  ws.on("unexpected-response", (_req, res) => {
    closed = true;
    closeInfo = `http ${res.statusCode}`;
    notify();
  });
  ws.on("error", (e) => {
    closeInfo ||= e.message.replace(/Bearer\s+\S+/g, "Bearer <redacted>");
  });

  try {
    await waitUntil(() => ws.readyState === WebSocket.OPEN, 15_000, "socket open");
    const segBase = 1;
    ws.send(
      JSON.stringify({
        type: "start",
        lang: opts.lang ?? "en",
        sample_rate: 16000,
        encoding: "linear16",
        channels: 1,
        seg_base: segBase,
        client: "replay-harness",
      }),
    );
    await waitUntil(() => readyAt >= 0, 15_000, "ready");

    let audioBytesSent = 0;
    const segRecords: SegmentRecord[] = [];
    for (let i = 0; i < spans.length; i++) {
      const span = spans[i]!;
      const seg = segBase + i;
      const pcm = toPcmBytes(wav.samples, span.startMs * 16, span.endMs * 16);
      // Absolute deadlines: batch n leaves at start + n * 96 ms, so timer drift never accumulates.
      const start = now();
      for (let n = 0; n * BATCH_BYTES < pcm.length; n++) {
        await sleepUntil(start + n * BATCH_MS);
        const frame = pcm.subarray(n * BATCH_BYTES, (n + 1) * BATCH_BYTES);
        ws.send(frame, { binary: true });
        audioBytesSent += frame.length;
      }
      const tFinalize = now();
      ws.send(JSON.stringify({ type: "finalize", seg }));
      await waitUntil(() => seen.finals.has(seg), FINAL_TIMEOUT_MS, `final ${seg}`);
      const fin = seen.finals.get(seg)!;
      const p = seen.firstPartialAt.get(seg);
      segRecords.push({
        label: opts.label,
        clip,
        seg,
        ttfp_ms: p === undefined ? null : p - start,
        final_after_finalize_ms: fin.at - tFinalize,
        complete: fin.complete,
        audio_ms: fin.audio_ms,
      });
    }
    ws.send(JSON.stringify({ type: "stop" }));
    await waitUntil(() => closed, 5_000, "close after stop");

    for (const r of segRecords) out(r);
    const stream: StreamRecord = {
      label: opts.label,
      type: "stream",
      open_to_ready_ms: readyAt - tOpen,
      audio_ms_sent: audioBytesSent / BYTES_PER_MS,
    };
    out(stream);
  } finally {
    ws.terminate();
  }
}

export async function runReplay(opts: ReplayOptions): Promise<ResultRecord[]> {
  const records: ResultRecord[] = [];
  const out = (r: ResultRecord) => {
    records.push(r);
    opts.onRecord?.(r);
  };
  for (let rep = 0; rep < opts.repeat; rep++) {
    for (const clip of opts.clips) await replayClip(opts, clip, out);
  }
  return records;
}

export function listWavs(dir: string): string[] {
  return readdirSync(dir)
    .filter((f) => f.toLowerCase().endsWith(".wav"))
    .sort()
    .map((f) => join(dir, f));
}

async function main(): Promise<void> {
  const { values } = parseArgs({
    options: {
      url: { type: "string" },
      token: { type: "string" },
      clips: { type: "string" },
      repeat: { type: "string", default: "1" },
      label: { type: "string" },
      out: { type: "string" },
      summary: { type: "string" },
      lang: { type: "string", default: "en" },
    },
  });
  const token = values.token ?? process.env.REPLAY_TOKEN;
  const repeat = Number(values.repeat);
  if (!values.url || !token || !values.clips || !values.label || !values.out || !Number.isInteger(repeat) || repeat < 1) {
    process.stderr.write(
      "usage: replay.ts --url <wss://host/v1/stream> --token <jwt> --clips <dir> --repeat <n> --label <region-or-vantage> --out <results.jsonl> [--summary <summary.json>] [--lang en]\n" +
        "       (the token may also be given in REPLAY_TOKEN)\n",
    );
    process.exit(2);
  }
  const clips = listWavs(values.clips);
  if (clips.length === 0) {
    process.stderr.write("no .wav files found in --clips\n");
    process.exit(2);
  }
  writeFileSync(values.out, "");
  const records = await runReplay({
    url: values.url,
    token,
    clips,
    repeat,
    label: values.label,
    lang: values.lang,
    onRecord: (r) => appendFileSync(values.out!, `${JSON.stringify(r)}\n`),
  });
  const summary = summarize(records);
  if (values.summary) writeFileSync(values.summary, `${JSON.stringify(summary, null, 2)}\n`);
  process.stdout.write(`${JSON.stringify(summary, null, 2)}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((e: unknown) => {
    process.stderr.write(`replay failed: ${e instanceof Error ? e.message.replace(/Bearer\s+\S+/g, "Bearer <redacted>") : "error"}\n`);
    process.exit(1);
  });
}
