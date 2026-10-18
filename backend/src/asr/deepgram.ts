import { WebSocket } from "ws";
import type { AsrVendor, VendorError, VendorErrorKind, VendorResult, VendorStream } from "./vendor.js";

export interface DeepgramConfig {
  apiKey: string;
  hosts: { us: string; eu: string };
}

const MESSAGES: Record<VendorErrorKind, string> = {
  connect: "vendor connection failed",
  auth: "vendor rejected credentials",
  rate_limited: "vendor rate limited",
  protocol: "vendor protocol error",
  timeout: "vendor timed out",
  dropped: "vendor connection dropped",
};

const err = (kind: VendorErrorKind): VendorError => ({ kind, message: MESSAGES[kind] });

type Listener = (arg?: never) => void;

class DeepgramStream implements VendorStream {
  requestId?: string;
  private listeners = new Map<string, Listener[]>();
  private ws: WebSocket | null = null;
  private closedByUs = false;
  private ended = false;

  on(ev: "result", f: (r: VendorResult) => void): void;
  on(ev: "error" | "closed", f: (e?: VendorError) => void): void;
  on(ev: string, f: (...args: never[]) => void): void {
    const list = this.listeners.get(ev) ?? [];
    list.push(f as Listener);
    this.listeners.set(ev, list);
  }

  private emit(ev: string, arg?: unknown): void {
    for (const f of this.listeners.get(ev) ?? []) (f as (a?: unknown) => void)(arg);
  }

  attach(ws: WebSocket): void {
    this.ws = ws;
    ws.on("message", (data, isBinary) => {
      if (isBinary) return;
      this.onText(data.toString());
    });
    ws.on("error", () => {
      if (!this.closedByUs && !this.ended) this.emit("error", err("dropped"));
    });
    ws.on("close", (code, reason) => {
      if (this.ended) return;
      this.ended = true;
      if (this.closedByUs) {
        this.emit("closed");
      } else {
        const timeout = code === 1011 && /net-?0001/i.test(reason.toString());
        this.emit("closed", err(timeout ? "timeout" : "dropped"));
      }
    });
  }

  /** Marks a stream that never connected: error then closed, on a later macrotask. */
  failLater(kind: VendorErrorKind): void {
    this.ended = true;
    setImmediate(() => {
      this.emit("error", err(kind));
      this.emit("closed", err(kind));
    });
  }

  private onText(raw: string): void {
    let m: unknown;
    try {
      m = JSON.parse(raw);
    } catch {
      this.emit("error", err("protocol"));
      return;
    }
    if (typeof m !== "object" || m === null) return;
    const o = m as Record<string, unknown>;
    if (o.type === "Results") {
      const channel = o.channel as { alternatives?: { transcript?: unknown }[] } | undefined;
      const transcript = channel?.alternatives?.[0]?.transcript;
      const start = typeof o.start === "number" ? o.start : 0;
      const duration = typeof o.duration === "number" ? o.duration : 0;
      this.emit("result", {
        text: typeof transcript === "string" ? transcript : "",
        startMs: Math.round(start * 1000),
        endMs: Math.round((start + duration) * 1000),
        isFinal: o.is_final === true,
        fromFinalize: o.from_finalize === true,
      } satisfies VendorResult);
    } else if (o.type === "Error" || o.type === "Warning") {
      if (o.type === "Error") this.emit("error", err("protocol"));
    }
  }

  private sendJson(type: string): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify({ type }));
  }

  sendAudio(pcm: Buffer): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) this.ws.send(pcm, { binary: true });
  }
  finalize(): void {
    this.sendJson("Finalize");
  }
  keepAlive(): void {
    this.sendJson("KeepAlive");
  }

  close(): Promise<void> {
    const ws = this.ws;
    if (!ws || this.closedByUs || ws.readyState === WebSocket.CLOSED) return Promise.resolve();
    this.closedByUs = true;
    return new Promise<void>((resolve) => {
      const timer = setTimeout(() => ws.terminate(), 1500);
      ws.once("close", () => {
        clearTimeout(timer);
        resolve();
      });
      if (ws.readyState === WebSocket.OPEN) {
        this.sendJson("CloseStream");
        ws.close(1000);
      } else {
        ws.terminate();
      }
    });
  }
}

export function createDeepgramVendor(cfg: DeepgramConfig): AsrVendor {
  return {
    open(o) {
      const params = new URLSearchParams({
        model: o.model,
        language: o.lang,
        encoding: "linear16",
        sample_rate: "16000",
        channels: "1",
        interim_results: "true",
        smart_format: "true",
        endpointing: "300",
        mip_opt_out: "true",
      });
      const url = `${cfg.hosts[o.asrRegion]}?${params.toString()}`;
      return new Promise<VendorStream>((resolve) => {
        const stream = new DeepgramStream();
        let settled = false;
        const ws = new WebSocket(url, {
          headers: { Authorization: `Token ${cfg.apiKey}` },
          handshakeTimeout: 10_000,
        });
        ws.on("upgrade", (res) => {
          const id = res.headers["dg-request-id"];
          if (typeof id === "string") stream.requestId = id;
        });
        ws.on("open", () => {
          settled = true;
          stream.attach(ws);
          resolve(stream);
        });
        ws.on("unexpected-response", (req, res) => {
          if (settled) return;
          settled = true;
          const s = res.statusCode ?? 0;
          res.resume();
          req.destroy();
          stream.failLater(s === 401 || s === 403 ? "auth" : s === 429 ? "rate_limited" : "connect");
          resolve(stream);
        });
        ws.on("error", (e) => {
          if (settled) return;
          settled = true;
          stream.failLater(/timed out/i.test(e.message) ? "timeout" : "connect");
          resolve(stream);
        });
      });
    },
  };
}
