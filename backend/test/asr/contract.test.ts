import { afterEach, describe, expect, it } from "vitest";
import { createDeepgramVendor } from "../../src/asr/deepgram.js";
import type { AsrVendor, VendorError, VendorResult, VendorStream } from "../../src/asr/vendor.js";
import { startFakeDeepgram, type FakeDeepgram, type FakeOptions } from "../fake-deepgram/server.js";

export interface Scenario {
  fake?: FakeOptions;
  badKey?: boolean;
}
export interface Harness {
  vendor: AsrVendor;
  /** Present only for the fake; cases that need to observe or script the vendor use it. */
  fake?: FakeDeepgram;
  apiKey: string;
  cleanup(): Promise<void>;
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** 100 ms of a 440 Hz tone, 16 kHz 16-bit LE mono. */
function toneChunk(index: number): Buffer {
  const n = 1600;
  const buf = Buffer.alloc(n * 2);
  for (let i = 0; i < n; i++) {
    const t = (index * n + i) / 16000;
    buf.writeInt16LE(Math.round(Math.sin(2 * Math.PI * 440 * t) * 8000), i * 2);
  }
  return buf;
}

interface Watched {
  stream: VendorStream;
  results: VendorResult[];
  errors: (VendorError | undefined)[];
  closed: (VendorError | undefined)[];
  isClosed(): boolean;
}

async function openWatched(vendor: AsrVendor): Promise<Watched> {
  const stream = await vendor.open({ lang: "en", model: "nova-3", asrRegion: "us", traceId: "t-1" });
  const w: Watched = {
    stream,
    results: [],
    errors: [],
    closed: [],
    isClosed: () => w.closed.length > 0,
  };
  stream.on("result", (r) => w.results.push(r));
  stream.on("error", (e) => w.errors.push(e));
  stream.on("closed", (e) => w.closed.push(e));
  return w;
}

async function waitFor(cond: () => boolean, ms = 3000): Promise<void> {
  const end = Date.now() + ms;
  while (!cond()) {
    if (Date.now() > end) throw new Error("timeout waiting for condition");
    await sleep(10);
  }
}

async function sendTone(w: Watched, chunks: number, paceMs: number): Promise<void> {
  for (let i = 0; i < chunks; i++) {
    w.stream.sendAudio(toneChunk(i));
    if (paceMs > 0) await sleep(paceMs);
  }
}

export function runContract(
  name: string,
  makeHarness: (s: Scenario) => Promise<Harness>,
  opts: { real: boolean },
): void {
  const pace = opts.real ? 100 : 0;
  const cleanups: (() => Promise<void>)[] = [];
  const harness = async (s: Scenario = {}) => {
    const h = await makeHarness(s);
    cleanups.push(h.cleanup);
    return h;
  };
  afterEach(async () => {
    while (cleanups.length) await cleanups.pop()!();
  });
  const fakeOnly = opts.real ? it.skip : it;

  describe(`AsrVendor contract: ${name}`, () => {
    it("connects and returns interim then final results for 2 s of audio", async () => {
      const h = await harness();
      const w = await openWatched(h.vendor);
      await sendTone(w, 20, pace);
      await waitFor(() => w.results.some((r) => !r.isFinal));
      w.stream.finalize();
      await waitFor(() => w.results.some((r) => r.isFinal && r.fromFinalize));
      const firstInterim = w.results.findIndex((r) => !r.isFinal);
      const firstFinal = w.results.findIndex((r) => r.isFinal);
      expect(firstInterim).toBeGreaterThanOrEqual(0);
      expect(firstInterim).toBeLessThan(firstFinal);
      for (const r of w.results) expect(r.endMs).toBeGreaterThanOrEqual(r.startMs);
      await w.stream.close();
    });

    it("finalize() yields exactly one result with fromFinalize:true", async () => {
      const h = await harness();
      const w = await openWatched(h.vendor);
      await sendTone(w, 10, pace);
      w.stream.finalize();
      await waitFor(() => w.results.some((r) => r.fromFinalize));
      await sleep(opts.real ? 1500 : 100);
      expect(w.results.filter((r) => r.fromFinalize)).toHaveLength(1);
      await w.stream.close();
    });

    fakeOnly("keepAlive() keeps the stream open past noAudioTimeoutMs", async () => {
      const h = await harness({ fake: { noAudioTimeoutMs: 300 } });
      const w = await openWatched(h.vendor);
      for (let i = 0; i < 8; i++) {
        w.stream.keepAlive();
        await sleep(100);
      }
      expect(w.isClosed()).toBe(false);
      expect(h.fake!.openConnections()).toBe(1);
      // and without keep alives the vendor times the stream out
      await waitFor(() => w.isClosed());
      expect(w.closed[0]?.kind).toBe("timeout");
    });

    it("close() ends the connection", async () => {
      const h = await harness();
      const w = await openWatched(h.vendor);
      await sendTone(w, 3, pace);
      await w.stream.close();
      expect(w.closed).toHaveLength(1);
      expect(w.closed[0]).toBeUndefined();
      if (h.fake) {
        await waitFor(() => h.fake!.openConnections() === 0);
        expect(h.fake.openConnections()).toBe(0);
      }
    });

    it("a bad key yields an auth error event", async () => {
      const h = await harness({ badKey: true, fake: { rejectAuth: true } });
      const w = await openWatched(h.vendor);
      await waitFor(() => w.errors.length > 0);
      expect(w.errors[0]?.kind).toBe("auth");
      await waitFor(() => w.isClosed());
      w.stream.sendAudio(toneChunk(0)); // must not throw on a dead stream
      await w.stream.close();
    });

    fakeOnly("a mid-stream drop yields closed with kind dropped", async () => {
      const h = await harness({ fake: { dropAfterMs: 150 } });
      const w = await openWatched(h.vendor);
      await waitFor(() => w.isClosed());
      expect(w.closed[0]?.kind).toBe("dropped");
    });

    it("an unreachable vendor yields a connect error", async () => {
      const h = await harness();
      await h.fake?.stop();
      const w = await openWatched(h.vendor);
      if (h.fake) {
        await waitFor(() => w.errors.length > 0);
        expect(w.errors[0]?.kind).toBe("connect");
      } else {
        await w.stream.close();
      }
    });

    it("the API key never appears in error messages or in the request URL", async () => {
      const h = await harness({ badKey: true, fake: { rejectAuth: true } });
      const w = await openWatched(h.vendor);
      await waitFor(() => w.errors.length > 0 && w.isClosed());
      const all = JSON.stringify([...w.errors, ...w.closed]);
      expect(all).not.toContain(h.apiKey);
      for (const req of h.fake?.requests() ?? []) expect(req.url).not.toContain(h.apiKey);
    });

    fakeOnly("sends exactly the planned request parameters and Token authorization", async () => {
      const h = await harness();
      const w = await openWatched(h.vendor);
      await sendTone(w, 1, pace);
      const req = h.fake!.requests()[0]!;
      expect(req.query).toEqual({
        model: "nova-3",
        language: "en",
        encoding: "linear16",
        sample_rate: "16000",
        channels: "1",
        interim_results: "true",
        smart_format: "true",
        endpointing: "300",
        mip_opt_out: "true",
      });
      expect(req.headers.authorization).toBe(`Token ${h.apiKey}`);
      await w.stream.close();
    });
  });
}

const API_KEY = "dg-test-key-SECRET-0123456789";

runContract(
  "fake",
  async (s) => {
    const fake = await startFakeDeepgram(s.fake);
    const apiKey = s.badKey ? "dg-invalid-key-SECRET" : API_KEY;
    const vendor = createDeepgramVendor({ apiKey, hosts: { us: fake.url, eu: fake.url } });
    return { vendor, fake, apiKey, cleanup: () => fake.stop() };
  },
  { real: false },
);

if (process.env.RUN_REAL_DEEPGRAM === "1" && process.env.DEEPGRAM_API_KEY) {
  runContract(
    "real Deepgram",
    async (s) => {
      const apiKey = s.badKey ? "invalid-key-for-contract-test" : process.env.DEEPGRAM_API_KEY!;
      const vendor = createDeepgramVendor({
        apiKey,
        hosts: { us: "wss://api.deepgram.com/v1/listen", eu: "wss://api.eu.deepgram.com/v1/listen" },
      });
      return { vendor, apiKey, cleanup: async () => {} };
    },
    { real: true },
  );
}
