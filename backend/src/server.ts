import Fastify from "fastify";
import type { AddressInfo } from "node:net";
import { WebSocketServer } from "ws";
import type { AsrVendor } from "./asr/vendor.js";
import { AuthError, verifySession } from "./auth.js";
import type { Config } from "./config.js";
import type { Logger } from "./log.js";
import { MAX_FRAME_BYTES } from "./protocol.js";
import { StreamSession } from "./proxy/session.js";

export interface ServerDeps {
  vendor: AsrVendor;
  log: Logger;
  now?: () => number;
}

export interface Server {
  listen(): Promise<{ port: number }>;
  close(): Promise<void>;
}

/**
 * ws drops a connection with 1009 above maxPayload before we can answer. Frames between the 64 KB
 * protocol limit and this ceiling still get close code 4400 from the session.
 */
const WS_CEILING_BYTES = 8 * MAX_FRAME_BYTES;

export async function buildServer(cfg: Config, deps: ServerDeps): Promise<Server> {
  const now = deps.now ?? Date.now;
  const app = Fastify({ logger: false });
  app.get("/healthz", async () => ({ ok: true }));
  app.get("/readyz", async () => ({ ready: true }));

  const wss = new WebSocketServer({ noServer: true, maxPayload: WS_CEILING_BYTES });
  const sessions = new Set<StreamSession>();

  const reject = (socket: import("node:stream").Duplex, status: string) => {
    if (socket.destroyed) return;
    socket.end(`HTTP/1.1 ${status}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
  };

  app.server.on("upgrade", (req, socket, head) => {
    socket.on("error", () => {});
    const path = (req.url ?? "").split("?")[0];
    if (path !== "/v1/stream") return reject(socket, "404 Not Found");
    const auth = req.headers.authorization ?? "";
    const m = /^Bearer (\S+)$/.exec(auth);
    if (!m) {
      deps.log.info("auth_failed", { reason: "missing_token" });
      return reject(socket, "401 Unauthorized");
    }
    verifySession(m[1]!, cfg.jwtSecret).then(
      (claims) => {
        if (socket.destroyed) return;
        const region = req.headers["x-client-region"];
        wss.handleUpgrade(req, socket, head, (ws) => {
          const session = new StreamSession(ws, claims, {
            cfg,
            vendor: deps.vendor,
            log: deps.log,
            now,
            clientRegion: typeof region === "string" ? region.slice(0, 64) : undefined,
          });
          sessions.add(session);
          ws.on("close", () => sessions.delete(session));
        });
      },
      (e: unknown) => {
        deps.log.info("auth_failed", { reason: e instanceof AuthError ? e.reason : "invalid" });
        reject(socket, "401 Unauthorized");
      },
    );
  });

  return {
    async listen() {
      await app.listen({ port: cfg.port, host: "0.0.0.0" });
      return { port: (app.server.address() as AddressInfo).port };
    },
    async close() {
      for (const s of sessions) s.terminate();
      sessions.clear();
      wss.close();
      app.server.closeAllConnections();
      await app.close();
    },
  };
}
