import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { WebSocket } from "ws";
import { createDeepgramVendor } from "../src/asr/deepgram.js";
import { signDevToken, type SessionClaims } from "../src/auth.js";
import type { Config } from "../src/config.js";
import { createLogger } from "../src/log.js";
import { buildServer } from "../src/server.js";
import { startFakeDeepgram, type FakeDeepgram, type FakeOptions } from "./fake-deepgram/server.js";

const API_KEY = "dg-live-API-KEY-9f8e7d6c5b4a";
const JWT_SECRET = "integration-jwt-secret-abcdef0123";

const claims: SessionClaims = {
  sub: "user-1",
  sid: "sess-1",
  kind: "paid",
  allow_ms: 600_000,
  lang: "en",
  dev: "dev-1",
  asr: "us",
  cfgv: 7,
};

type Msg = Record<string, unknown> & { type: string };

interface Client {
  ws: WebSocket;
  msgs: Msg[];
  closed: { code: number; reason: string } | null;
  ofType(t: string): Msg[];
  send(o: unknown): void;
  frames(n: number, size?: number): void;
}

/** Polls with real setImmediate and a real monotonic deadline so it also works under fake timers. */
async function waitFor(cond: () => boolean, ms = 4000, what = "condition"): Promise<void> {
  const end = performance.now() + ms;
  while (!cond()) {
    if (performance.now() > end) throw new Error(`timed out waiting for ${what}`);
    await new Promise((r) => setImmediate(r));
  }
}
const settle = async (n = 30) => {
  for (let i = 0; i < n; i++) await new Promise((r) => setImmediate(r));
};

let fake: FakeDeepgram;
let server: Awaited<ReturnType<typeof buildServer>>;
let port: number;
let logLines: string[];
let clients: Client[];

async function boot(opts: { fake?: FakeOptions; now?: () => number; apiKey?: string; langs?: string[] } = {}) {
  fake = await startFakeDeepgram({ noAudioTimeoutMs: 3_600_000, ...opts.fake });
  logLines = [];
  const apiKey = opts.apiKey ?? API_KEY;
  const cfg: Config = {
    port: 0,
    region: "us-east4",
    deepgramApiKey: apiKey,
    deepgramHosts: { us: fake.url, eu: fake.url },
    jwtSecret: JWT_SECRET,
    supportedLanguages: new Set(opts.langs ?? ["en", "es"]),
    maxStreamMs: 3_300_000,
  };
  // strict logger and no redaction, so a leaked secret would be visible in logLines
  const log = createLogger((l) => logLines.push(l), { strict: true });
  server = await buildServer(cfg, {
    vendor: createDeepgramVendor({ apiKey, hosts: cfg.deepgramHosts }),
    log,
    now: opts.now,
  });
  port = (await server.listen()).port;
}

async function connect(token?: string, headers: Record<string, string> = {}): Promise<Client> {
  const t = token ?? (await signDevToken(claims, JWT_SECRET));
  const ws = new WebSocket(`ws://127.0.0.1:${port}/v1/stream`, { headers: { Authorization: `Bearer ${t}`, ...headers } });
  const c: Client = {
    ws,
    msgs: [],
    closed: null,
    ofType: (ty) => c.msgs.filter((m) => m.type === ty),
    send: (o) => ws.send(JSON.stringify(o)),
    frames: (n, size = 3200) => {
      for (let i = 0; i < n; i++) ws.send(Buffer.alloc(size, 1), { binary: true });
    },
  };
  ws.on("message", (d, bin) => {
    if (!bin) c.msgs.push(JSON.parse(d.toString()) as Msg);
  });
  ws.on("close", (code, reason) => (c.closed = { code, reason: reason.toString() }));
  ws.on("error", () => {});
  clients.push(c);
  await new Promise<void>((resolve, reject) => {
    ws.once("open", () => resolve());
    ws.once("unexpected-response", (_r, res) => reject(new Error(`http ${res.statusCode}`)));
    ws.once("error", reject);
  });
  return c;
}

const START = { type: "start", lang: "en", sample_rate: 16000, encoding: "linear16", channels: 1, seg_base: 1, client: "test" };
async function started(extra: Partial<typeof START> = {}): Promise<Client> {
  const c = await connect();
  c.send({ ...START, ...extra });
  await waitFor(() => c.ofType("ready").length === 1, 4000, "ready");
  return c;
}

beforeEach(() => {
  clients = [];
});
afterEach(async () => {
  vi.useRealTimers();
  for (const c of clients) c.ws.terminate();
  await server?.close();
  await fake?.stop();
});

describe("StreamSession integration", () => {
  it("happy path: ready, partials, exactly one final per finalize, clean stop", async () => {
    await boot();
    const c = await started();
    const ready = c.ofType("ready")[0]!;
    expect(ready).toMatchObject({
      region: "us-east4",
      asr_region: "us",
      model: "nova-3",
      lang: "en",
      remaining_ms: 600_000,
      flush_interval_ms: 10_000,
      max_stream_ms: 3_300_000,
      config_version: 7,
    });
    expect(typeof ready.stream_id).toBe("string");

    c.frames(20);
    await waitFor(() => c.ofType("partial").length >= 1, 4000, "partial");
    c.send({ type: "finalize", seg: 1 });
    await waitFor(() => c.ofType("final").length === 1, 4000, "final 1");
    expect(c.ofType("final")[0]).toMatchObject({ seg: 1, complete: true, audio_ms: 2000 });
    expect(String(c.ofType("final")[0]!.text).length).toBeGreaterThan(0);
    expect(c.ofType("partial").every((p) => p.seg === 1)).toBe(true);

    c.frames(10);
    await waitFor(() => c.ofType("partial").some((p) => p.seg === 2), 4000, "partial for segment 2");
    c.send({ type: "finalize", seg: 2 });
    await waitFor(() => c.ofType("final").length === 2, 4000, "final 2");
    expect(c.ofType("final")[1]).toMatchObject({ seg: 2, complete: true, audio_ms: 1000 });

    c.send({ type: "stop" });
    await waitFor(() => c.closed !== null, 4000, "close");
    expect(c.closed!.code).toBe(1000);
    expect(c.ofType("final")).toHaveLength(2);
    await waitFor(() => fake.openConnections() === 0, 2000, "vendor closed");
  });

  it("stop finalizes the open segment and waits for its final", async () => {
    await boot();
    const c = await started();
    c.frames(10);
    c.send({ type: "stop" });
    await waitFor(() => c.closed !== null, 4000, "close");
    expect(c.closed!.code).toBe(1000);
    expect(c.ofType("final")).toHaveLength(1);
    expect(c.ofType("final")[0]).toMatchObject({ seg: 1, complete: true, audio_ms: 1000 });
  });

  it("an invalid JWT gets HTTP 401 and never a socket", async () => {
    await boot();
    await expect(connect("not.a.jwt")).rejects.toThrow("http 401");
    const expired = await signDevToken(claims, JWT_SECRET, -30);
    await expect(connect(expired)).rejects.toThrow("http 401");
    const wrong = await signDevToken(claims, "some-other-secret-0123456789");
    await expect(connect(wrong)).rejects.toThrow("http 401");
    expect(fake.requests()).toHaveLength(0);
    // no Authorization header at all
    const raw = new WebSocket(`ws://127.0.0.1:${port}/v1/stream`);
    raw.on("error", () => {});
    const status = await new Promise<number>((resolve) => raw.once("unexpected-response", (_q, res) => resolve(res.statusCode ?? 0)));
    expect(status).toBe(401);
    const all = logLines.join("\n");
    expect(all).not.toContain("not.a.jwt");
  });

  it("serves /healthz and /readyz", async () => {
    await boot();
    expect((await fetch(`http://127.0.0.1:${port}/healthz`)).status).toBe(200);
    expect((await fetch(`http://127.0.0.1:${port}/readyz`)).status).toBe(200);
  });

  it("an unsupported language closes 4004 without ready", async () => {
    await boot();
    const c = await connect();
    c.send({ ...START, lang: "xx" });
    await waitFor(() => c.closed !== null);
    expect(c.closed!.code).toBe(4004);
    expect(c.ofType("ready")).toHaveLength(0);
    expect(fake.requests()).toHaveLength(0);
  });

  it("a first message that is not start closes 4400", async () => {
    await boot();
    const c = await connect();
    c.send({ type: "finalize", seg: 1 });
    await waitFor(() => c.closed !== null);
    expect(c.closed!.code).toBe(4400);
  });

  it("a second start closes 4400", async () => {
    await boot();
    const c = await started();
    c.send(START);
    await waitFor(() => c.closed !== null);
    expect(c.closed!.code).toBe(4400);
  });

  it("a frame of 65537 bytes closes 4400, 65536 is accepted", async () => {
    await boot();
    const ok = await started();
    ok.frames(1, 65536);
    await settle();
    expect(ok.closed).toBeNull();
    const c = await started();
    c.frames(1, 65537);
    await waitFor(() => c.closed !== null);
    expect(c.closed!.code).toBe(4400);
  });

  it("audio at 3x real time is cut off with 4400 before 15 s reach the vendor", async () => {
    let clock = 1_000_000;
    await boot({ now: () => clock });
    const c = await started();
    let sent = 0;
    for (let i = 0; i < 200 && c.closed === null; i++) {
      clock += 100 / 3; // each 100 ms frame arrives 33 ms later: 3x real time
      c.frames(1);
      sent += 3200;
      await waitFor(() => c.closed !== null || fake.receivedAudioBytes() >= sent, 2000, "frame processed");
    }
    await waitFor(() => c.closed !== null);
    expect(c.closed!.code).toBe(4400);
    expect(fake.receivedAudioBytes() / 32).toBeLessThan(15_000);
    expect(fake.receivedAudioBytes() / 32).toBeGreaterThan(4_000);
  });

  it("a bad vendor key closes 4503 and leaks neither the key nor the JWT", async () => {
    await boot({ fake: { rejectAuth: true } });
    const token = await signDevToken(claims, JWT_SECRET);
    const c = await connect(token);
    c.send(START);
    await waitFor(() => c.closed !== null);
    expect(c.closed!.code).toBe(4503);
    expect(c.ofType("ready")).toHaveLength(0);
    await waitFor(() => logLines.some((l) => l.includes("stream_close")));
    const everything = [...logLines, c.closed!.reason, JSON.stringify(c.msgs)].join("\n");
    expect(everything).not.toContain(API_KEY);
    expect(everything).not.toContain("API-KEY");
    expect(everything).not.toContain(token);
    expect(everything).not.toContain(token.split(".")[1]!);
    expect(everything).not.toContain(JWT_SECRET);
  });

  it("a vendor drop mid-stream closes 4503", async () => {
    await boot({ fake: { dropAfterMs: 300 } });
    const c = await started();
    c.frames(5);
    await waitFor(() => c.closed !== null);
    expect(c.closed!.code).toBe(4503);
    expect(c.closed!.reason).toBe("vendor_unavailable");
  });

  it("a client disconnect mid-stream closes the vendor connection promptly", async () => {
    await boot();
    const c = await started();
    c.frames(5);
    await waitFor(() => fake.receivedAudioBytes() >= 16_000);
    expect(fake.openConnections()).toBe(1);
    c.ws.terminate();
    await waitFor(() => fake.openConnections() === 0, 1000, "vendor connection closed");
  });

  describe("with fake timers", () => {
    const FAKE = { toFake: ["setTimeout", "clearTimeout", "setInterval", "clearInterval", "Date"] as ("setTimeout" | "clearTimeout" | "setInterval" | "clearInterval" | "Date")[] };

    it("a client disconnect leaves no timers behind", async () => {
      vi.useFakeTimers(FAKE);
      await boot();
      const baseline = vi.getTimerCount();
      const c = await started();
      c.frames(5);
      await waitFor(() => fake.receivedAudioBytes() >= 16_000);
      expect(vi.getTimerCount()).toBeGreaterThan(baseline); // the session owns timers while streaming
      c.ws.terminate();
      await waitFor(() => fake.openConnections() === 0, 1000, "vendor closed");
      await waitFor(() => vi.getTimerCount() <= baseline, 1000, "timers cleared");
      expect(vi.getTimerCount()).toBe(baseline);
    });

    it("a finalize the vendor never answers yields one incomplete final after 1500 ms", async () => {
      vi.useFakeTimers(FAKE);
      await boot({ fake: { finalizeResponseDelayMs: null } });
      const c = await started();
      c.frames(5);
      await waitFor(() => fake.receivedAudioBytes() >= 16_000);
      c.send({ type: "finalize", seg: 1 });
      await waitFor(() => fake.finalizeCount() === 1);
      await vi.advanceTimersByTimeAsync(1400);
      await settle();
      expect(c.ofType("final")).toHaveLength(0);
      await vi.advanceTimersByTimeAsync(400);
      await waitFor(() => c.ofType("final").length === 1, 2000, "expired final");
      expect(c.ofType("final")[0]).toMatchObject({ seg: 1, complete: false, audio_ms: 500 });
      await vi.advanceTimersByTimeAsync(10_000);
      await settle();
      expect(c.ofType("final")).toHaveLength(1);
      expect(c.closed).toBeNull();
    });

    it("an empty segment gets exactly one incomplete final", async () => {
      vi.useFakeTimers(FAKE);
      await boot({ fake: { finalizeResponseDelayMs: null } });
      const c = await started();
      c.send({ type: "finalize", seg: 1 });
      await vi.advanceTimersByTimeAsync(1800);
      await waitFor(() => c.ofType("final").length === 1);
      expect(c.ofType("final")[0]).toMatchObject({ seg: 1, text: "", audio_ms: 0, complete: false });
      await vi.advanceTimersByTimeAsync(5000);
      await settle();
      expect(c.ofType("final")).toHaveLength(1);
    });

    it("keeps the vendor alive with KeepAlive, closes it after 60 s idle and reopens lazily without loss", async () => {
      vi.useFakeTimers(FAKE);
      await boot();
      const c = await started();
      c.frames(10);
      await waitFor(() => fake.receivedAudioBytes() >= 32_000);
      await vi.advanceTimersByTimeAsync(30_000);
      await settle();
      expect(fake.keepAliveCount()).toBeGreaterThanOrEqual(5);
      expect(fake.openConnections()).toBe(1);
      await vi.advanceTimersByTimeAsync(31_000); // 61 s without audio
      await waitFor(() => fake.openConnections() === 0, 2000, "idle vendor closed");
      expect(c.closed).toBeNull();
      expect(fake.requests()).toHaveLength(1);

      const before = fake.receivedAudioBytes();
      c.frames(15); // 1.5 s, inside the 2 s reopen buffer
      await waitFor(() => fake.requests().length === 2, 2000, "vendor reopened");
      await waitFor(() => fake.receivedAudioBytes() === before + 15 * 3200, 2000, "buffered audio delivered");
      c.send({ type: "finalize", seg: 1 });
      await waitFor(() => c.ofType("final").length === 1, 2000, "final after reopen");
      expect(c.ofType("final")[0]).toMatchObject({ seg: 1, complete: true, audio_ms: 2500 });
      expect(c.closed).toBeNull();
    });
  });

  it("stream_close logs audio_ms = bytes / 32 and only allowlisted fields", async () => {
    await boot();
    const c = await started();
    c.frames(10);
    await waitFor(() => fake.receivedAudioBytes() >= 32_000);
    c.send({ type: "finalize", seg: 1 });
    await waitFor(() => c.ofType("final").length === 1);
    c.send({ type: "stop" });
    await waitFor(() => c.closed !== null);
    await waitFor(() => logLines.some((l) => l.includes('"stream_close"')));
    const close = JSON.parse(logLines.find((l) => l.includes('"stream_close"'))!);
    expect(close).toMatchObject({ event: "stream_close", audio_ms: 1000, bytes: 32_000, frames: 10, segments: 1, close_code: 1000 });
    expect(close.audio_ms).toBe(close.bytes / 32);
    const open = JSON.parse(logLines.find((l) => l.includes('"stream_open"'))!);
    expect(open).toMatchObject({ event: "stream_open", region: "us-east4", kind: "paid", asr_region: "us" });
    expect(logLines.filter((l) => l.includes('"stream_close"'))).toHaveLength(1);
  });

  it("logs the X-Client-Region header in stream_open", async () => {
    await boot();
    const c = await connect(undefined, { "X-Client-Region": "europe-west4" });
    c.send(START);
    await waitFor(() => c.ofType("ready").length === 1);
    const open = JSON.parse(logLines.find((l) => l.includes('"stream_open"'))!);
    expect(open.client_region).toBe("europe-west4");
  });
});
