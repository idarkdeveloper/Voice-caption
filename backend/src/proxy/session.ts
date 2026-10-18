import { randomUUID } from "node:crypto";
import type { RawData, WebSocket } from "ws";
import type { AsrVendor, VendorStream } from "../asr/vendor.js";
import type { SessionClaims } from "../auth.js";
import type { Config } from "../config.js";
import type { Logger } from "../log.js";
import { CLOSE, MAX_FRAME_BYTES, parseClientMessage, serialize, type ServerMessage } from "../protocol.js";
import { AudioTokenBucket } from "./ratelimit.js";
import { SegmentTracker, type TrackerEvent } from "./segments.js";

const MODEL = "nova-3";
const FLUSH_INTERVAL_MS = 10_000;
const TICK_MS = 250;
const KEEPALIVE_AFTER_MS = 5_000;
const VENDOR_IDLE_MS = 60_000;
const REOPEN_BUFFER_BYTES = 2 * 16_000 * 2; // 2 s of 16 kHz 16-bit mono
const STOP_WAIT_MS = 1_500;

export interface SessionDeps {
  cfg: Config;
  vendor: AsrVendor;
  log: Logger;
  now: () => number;
  clientRegion?: string;
}

type Op = { kind: "audio"; buf: Buffer } | { kind: "finalize"; seg: number };
type VendorState = "none" | "opening" | "open";

/** One client connection: protocol state machine between the client socket and one Deepgram stream. */
export class StreamSession {
  readonly streamId = randomUUID();
  private tracker: SegmentTracker | null = null;
  private readonly bucket: AudioTokenBucket;
  private readonly openedAt: number;

  private vendorState: VendorState = "none";
  private vendorStream: VendorStream | null = null;
  private vendorBase = 0;
  private readonly intentional = new WeakSet<VendorStream>();
  private ops: Op[] = [];
  private bufferedBytes = 0;

  private lastAudioAt = 0;
  private lastVendorTx = 0;
  private nextSeg = 0;
  private lang = "";
  private bytesSinceFinalize = 0;
  private forwardedBytes = 0;
  private frames = 0;
  private finalizes = 0;

  private tickTimer: NodeJS.Timeout | null = null;
  private stopTimer: NodeJS.Timeout | null = null;
  private stopping = false;
  private closing = false;
  private closeCode: number | null = null;
  private logged = false;

  constructor(
    private readonly ws: WebSocket,
    private readonly claims: SessionClaims,
    private readonly d: SessionDeps,
  ) {
    this.openedAt = d.now();
    this.bucket = new AudioTokenBucket({ ratePerS: 1.5, burstS: 5, now: d.now });
    d.log.info("stream_open", {
      stream_id: this.streamId,
      region: d.cfg.region,
      session_id: claims.sid,
      uid: claims.sub,
      kind: claims.kind,
      asr_region: claims.asr,
      client_region: d.clientRegion,
    });
    ws.on("message", (data, isBinary) => this.onMessage(data, isBinary));
    ws.on("error", () => {});
    ws.on("close", (code) => {
      if (this.closeCode === null) this.closeCode = code;
      this.closing = true;
      this.cleanup();
    });
  }

  /** Hard stop used when the server shuts down. */
  terminate(): void {
    this.closing = true;
    this.closeCode ??= 1001;
    this.ws.terminate();
    this.cleanup();
  }

  // ---- client side ----

  private onMessage(data: RawData, isBinary: boolean): void {
    if (this.closing) return;
    if (isBinary) {
      if (!this.tracker) return this.shutdown(CLOSE.PROTOCOL, "no_start");
      this.onAudio(toBuffer(data));
      return;
    }
    const parsed = parseClientMessage(toBuffer(data).toString("utf8"), { started: this.tracker !== null });
    if (!parsed.ok) return this.shutdown(CLOSE.PROTOCOL, parsed.reason);
    const msg = parsed.msg;
    if (msg.type === "start") return this.onStart(msg.lang, msg.seg_base);
    if (!this.tracker) return this.shutdown(CLOSE.PROTOCOL, "no_start");
    if (this.stopping) return;
    if (msg.type === "finalize") this.enqueueFinalize(msg.seg);
    else this.onStop();
  }

  private onStart(lang: string, segBase: number): void {
    if (!this.d.cfg.supportedLanguages.has(lang)) return this.shutdown(CLOSE.LANG_UNSUPPORTED, "lang_unsupported");
    this.tracker = new SegmentTracker({ segBase, now: this.d.now });
    this.nextSeg = segBase;
    this.lang = lang;
    this.tickTimer = setInterval(() => this.tick(), TICK_MS);
    this.openVendor(true);
  }
  private onAudio(buf: Buffer): void {
    if (this.stopping) return;
    if (buf.length > MAX_FRAME_BYTES) return this.shutdown(CLOSE.PROTOCOL, "frame_too_large");
    if (buf.length === 0) return;
    if (!this.bucket.take(buf.length / 32)) return this.shutdown(CLOSE.PROTOCOL, "audio_rate");
    this.ops.push({ kind: "audio", buf });
    this.bufferedBytes += buf.length;
    // While the vendor is not open, keep at most 2 s of audio and drop the oldest.
    while (this.vendorState !== "open" && this.bufferedBytes > REOPEN_BUFFER_BYTES) {
      const i = this.ops.findIndex((o) => o.kind === "audio");
      if (i < 0) break;
      this.bufferedBytes -= (this.ops[i] as { buf: Buffer }).buf.length;
      this.ops.splice(i, 1);
    }
    this.pump();
  }

  private enqueueFinalize(seg: number): void {
    if (this.vendorState === "none" && !this.ops.some((o) => o.kind === "audio")) {
      // Nothing was sent to a closed vendor: bookkeeping only; the tracker answers after 1500 ms.
      this.applyFinalize(seg, false);
      return;
    }
    this.ops.push({ kind: "finalize", seg });
    this.pump();
  }

  private onStop(): void {
    this.stopping = true;
    if (this.bytesSinceFinalize > 0 || this.ops.some((o) => o.kind === "audio")) {
      this.enqueueFinalize(this.nextSeg);
    }
    this.stopTimer = setTimeout(() => {
      this.stopTimer = null;
      this.emit(this.tracker!.expire(Number.POSITIVE_INFINITY));
      this.shutdown(CLOSE.NORMAL, "normal");
    }, STOP_WAIT_MS);
    this.checkStopDone();
  }

  private checkStopDone(): void {
    if (this.stopping && !this.closing && this.ops.length === 0 && this.tracker!.pendingFinalizes === 0) {
      this.shutdown(CLOSE.NORMAL, "normal");
    }
  }

  // ---- vendor side ----

  private openVendor(initial: boolean): void {
    this.vendorState = "opening";
    const base = this.tracker!.forwardedMs;
    this.d.vendor
      .open({ lang: this.lang, model: MODEL, asrRegion: this.claims.asr, traceId: this.streamId })
      .then(async (stream) => {
        if (this.closing) {
          this.intentional.add(stream);
          void stream.close().catch(() => {});
          return;
        }
        this.vendorStream = stream;
        this.vendorBase = base;
        stream.on("result", (r) => {
          if (stream !== this.vendorStream || this.closing) return;
          this.emit(
            this.tracker!.onVendorResult({ ...r, startMs: r.startMs + this.vendorBase, endMs: r.endMs + this.vendorBase }),
          );
        });
        const failed = () => {
          if (stream !== this.vendorStream || this.intentional.has(stream)) return;
          this.shutdown(CLOSE.VENDOR, "vendor_unavailable");
        };
        stream.on("error", failed);
        stream.on("closed", failed);
        // Vendor failures arrive as events on a later macrotask: give them a chance before `ready`.
        await new Promise<void>((r) => setImmediate(r));
        if (this.closing) return;
        this.vendorState = "open";
        this.lastAudioAt = this.lastVendorTx = this.d.now();
        if (initial) {
          this.send({
            type: "ready",
            stream_id: this.streamId,
            region: this.d.cfg.region,
            asr_region: this.claims.asr,
            model: MODEL,
            lang: this.lang,
            remaining_ms: this.claims.allow_ms,
            flush_interval_ms: FLUSH_INTERVAL_MS,
            max_stream_ms: this.d.cfg.maxStreamMs,
            config_version: this.claims.cfgv,
          });
        }
        this.pump();
      })
      .catch(() => this.shutdown(CLOSE.VENDOR, "vendor_unavailable"));
  }

  /** Runs queued operations in order once the vendor is open; opens it lazily when audio is waiting. */
  private pump(): void {
    if (this.closing) return;
    if (this.vendorState === "none") {
      if (this.ops.some((o) => o.kind === "audio")) this.openVendor(false);
      return;
    }
    if (this.vendorState !== "open") return;
    const ops = this.ops;
    this.ops = [];
    this.bufferedBytes = 0;
    for (const op of ops) {
      if (op.kind === "audio") {
        this.vendorStream!.sendAudio(op.buf);
        this.tracker!.onAudio(op.buf.length);
        this.forwardedBytes += op.buf.length;
        this.bytesSinceFinalize += op.buf.length;
        this.frames++;
        this.lastAudioAt = this.lastVendorTx = this.d.now();
      } else {
        this.applyFinalize(op.seg, true);
      }
    }
    this.checkStopDone();
  }

  private applyFinalize(seg: number, toVendor: boolean): void {
    this.tracker!.onFinalize(seg);
    if (toVendor) {
      this.vendorStream!.finalize();
      this.lastVendorTx = this.d.now();
    }
    this.finalizes++;
    this.nextSeg = seg + 1;
    this.bytesSinceFinalize = 0;
  }

  private tick(): void {
    if (this.closing || !this.tracker) return;
    const t = this.d.now();
    this.emit(this.tracker.expire(t));
    if (this.vendorState !== "open" || !this.vendorStream) return;
    if (t - this.lastAudioAt >= VENDOR_IDLE_MS) {
      const v = this.vendorStream;
      this.vendorStream = null;
      this.vendorState = "none";
      this.intentional.add(v);
      void v.close().catch(() => {});
    } else if (t - this.lastVendorTx >= KEEPALIVE_AFTER_MS) {
      this.vendorStream.keepAlive();
      this.lastVendorTx = t;
    }
  }

  // ---- output and teardown ----

  private emit(events: TrackerEvent[]): void {
    for (const e of events) {
      this.send(
        e.kind === "partial"
          ? { type: "partial", seg: e.seg, text: e.text }
          : { type: "final", seg: e.seg, text: e.text, audio_ms: e.audioMs, complete: e.complete },
      );
    }
    this.checkStopDone();
  }

  private send(msg: ServerMessage): void {
    if (this.ws.readyState === this.ws.OPEN) this.ws.send(serialize(msg));
  }

  private shutdown(code: number, reason: string): void {
    if (this.closing) return;
    this.closing = true;
    this.closeCode = code;
    if (this.ws.readyState === this.ws.OPEN || this.ws.readyState === this.ws.CONNECTING) this.ws.close(code, reason);
    this.cleanup();
  }

  private cleanup(): void {
    if (this.tickTimer) clearInterval(this.tickTimer);
    if (this.stopTimer) clearTimeout(this.stopTimer);
    this.tickTimer = this.stopTimer = null;
    const v = this.vendorStream;
    this.vendorStream = null;
    this.vendorState = "none";
    this.ops = [];
    if (v) {
      this.intentional.add(v);
      void v.close().catch(() => {});
    }
    if (this.logged) return;
    this.logged = true;
    this.d.log.info("stream_close", {
      stream_id: this.streamId,
      audio_ms: this.forwardedBytes / 32,
      segments: this.finalizes,
      frames: this.frames,
      bytes: this.forwardedBytes,
      close_code: this.closeCode ?? undefined,
      duration_ms: this.d.now() - this.openedAt,
    });
  }
}

function toBuffer(data: RawData): Buffer {
  if (Buffer.isBuffer(data)) return data;
  if (Array.isArray(data)) return Buffer.concat(data);
  return Buffer.from(data);
}
