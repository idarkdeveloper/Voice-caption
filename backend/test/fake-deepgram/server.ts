import { createServer, type IncomingHttpHeaders } from "node:http";
import type { AddressInfo } from "node:net";
import { WebSocketServer, type WebSocket } from "ws";

export interface FakeOptions {
  /** Delay before each interim result is sent (ms). Default 0. */
  resultDelayMs?: number;
  /** An interim result is produced every this many received audio bytes. Default 6400 (200 ms). */
  interimEveryBytes?: number;
  /** Text of the n-th segment (n counts Finalize messages, from 0). */
  transcriptForSegment?: (n: number) => string;
  /** Terminate each connection this long after it opened. */
  dropAfterMs?: number;
  /** Answer the upgrade with HTTP 401. */
  rejectAuth?: boolean;
  /** Close a connection (1011 NET-0001) after this long without audio or KeepAlive. Default 10000. */
  noAudioTimeoutMs?: number;
  /** Delay before the from_finalize result (ms); null = never answer Finalize. Default 0. */
  finalizeResponseDelayMs?: number | null;
}

export interface FakeRequest {
  url: string;
  query: Record<string, string>;
  headers: IncomingHttpHeaders;
}

export interface FakeDeepgram {
  url: string;
  stop(): Promise<void>;
  openConnections(): number;
  receivedAudioBytes(): number;
  /** Every upgrade request seen (including rejected ones), in order. */
  requests(): FakeRequest[];
  /** Number of Finalize messages received. */
  finalizeCount(): number;
  /** Number of KeepAlive messages received. */
  keepAliveCount(): number;
}

const defaultTranscript = (n: number) => `fake segment ${n} says hello world again`;

export async function startFakeDeepgram(opts: FakeOptions = {}): Promise<FakeDeepgram> {
  const interimEvery = opts.interimEveryBytes ?? 6400;
  const transcriptFor = opts.transcriptForSegment ?? defaultTranscript;
  const noAudioTimeout = opts.noAudioTimeoutMs ?? 10_000;
  const finalizeDelay = opts.finalizeResponseDelayMs === undefined ? 0 : opts.finalizeResponseDelayMs;
  const resultDelay = opts.resultDelayMs ?? 0;

  const requests: FakeRequest[] = [];
  const sockets = new Set<WebSocket>();
  const timers = new Set<NodeJS.Timeout>();
  let audioBytes = 0;
  let finalizes = 0;
  let keepAlives = 0;

  const later = (ms: number, fn: () => void) => {
    const t = setTimeout(() => {
      timers.delete(t);
      fn();
    }, ms);
    timers.add(t);
  };

  const http = createServer((_req, res) => {
    res.statusCode = 404;
    res.end();
  });
  const wss = new WebSocketServer({ noServer: true });

  http.on("upgrade", (req, socket, head) => {
    const u = new URL(req.url ?? "/", "http://fake");
    requests.push({ url: req.url ?? "", query: Object.fromEntries(u.searchParams), headers: req.headers });
    if (opts.rejectAuth) {
      socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => {
      sockets.add(ws);
      ws.on("close", () => sockets.delete(ws));
      serve(ws);
    });
  });

  function serve(ws: WebSocket): void {
    let connBytes = 0; // bytes received on this connection: its timeline, 32 bytes per ms
    let segStartBytes = 0;
    let segment = 0; // counts Finalize messages
    let bytesSinceInterim = 0;
    let interimCount = 0;
    let idle: NodeJS.Timeout | undefined;

    const armIdle = () => {
      if (idle) {
        clearTimeout(idle);
        timers.delete(idle);
      }
      idle = setTimeout(() => ws.close(1011, "NET-0001"), noAudioTimeout);
      timers.add(idle);
    };
    ws.on("close", () => {
      if (idle) {
        clearTimeout(idle);
        timers.delete(idle);
      }
    });
    armIdle();
    if (opts.dropAfterMs !== undefined) later(opts.dropAfterMs, () => ws.terminate());

    const send = (text: string, isFinal: boolean, fromFinalize: boolean, startBytes: number, endBytes: number) => {
      if (ws.readyState !== ws.OPEN) return;
      ws.send(
        JSON.stringify({
          type: "Results",
          channel_index: [0, 1],
          duration: (endBytes - startBytes) / 32000,
          start: startBytes / 32000,
          is_final: isFinal,
          speech_final: false,
          from_finalize: fromFinalize,
          channel: { alternatives: [{ transcript: text, confidence: 0.99 }] },
        }),
      );
    };

    ws.on("message", (data, isBinary) => {
      armIdle();
      if (isBinary) {
        const len = (data as Buffer).length;
        audioBytes += len;
        connBytes += len;
        bytesSinceInterim += len;
        while (bytesSinceInterim >= interimEvery) {
          bytesSinceInterim -= interimEvery;
          interimCount++;
          const words = transcriptFor(segment).split(" ");
          const text = words.slice(0, Math.min(words.length, interimCount)).join(" ");
          const start = segStartBytes;
          const end = connBytes;
          if (resultDelay > 0) later(resultDelay, () => send(text, false, false, start, end));
          else send(text, false, false, start, end);
        }
        return;
      }
      let msg: { type?: string };
      try {
        msg = JSON.parse(data.toString()) as { type?: string };
      } catch {
        return;
      }
      if (msg.type === "Finalize") {
        finalizes++;
        const start = segStartBytes;
        const end = connBytes;
        const text = end > start ? transcriptFor(segment) : "";
        segment++;
        segStartBytes = connBytes;
        interimCount = 0;
        bytesSinceInterim = 0;
        if (finalizeDelay === null) return;
        const respond = () => send(text, true, true, start, end);
        if (finalizeDelay > 0) later(finalizeDelay, respond);
        else respond();
      } else if (msg.type === "KeepAlive") {
        keepAlives++;
      } else if (msg.type === "CloseStream") {
        if (ws.readyState === ws.OPEN) {
          ws.send(JSON.stringify({ type: "Metadata", request_id: "fake-request" }));
          ws.close(1000);
        }
      }
    });
  }

  await new Promise<void>((resolve) => http.listen(0, "127.0.0.1", resolve));
  const port = (http.address() as AddressInfo).port;

  return {
    url: `ws://127.0.0.1:${port}/v1/listen`,
    openConnections: () => [...sockets].filter((s) => s.readyState === s.OPEN || s.readyState === s.CONNECTING).length,
    receivedAudioBytes: () => audioBytes,
    requests: () => requests,
    finalizeCount: () => finalizes,
    keepAliveCount: () => keepAlives,
    async stop() {
      for (const t of timers) clearTimeout(t);
      timers.clear();
      for (const s of sockets) s.terminate();
      wss.close();
      http.closeAllConnections();
      await new Promise<void>((resolve) => http.close(() => resolve()));
    },
  };
}
