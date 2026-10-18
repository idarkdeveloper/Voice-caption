/**
 * Allowlisted JSON logger. Privacy rule: no audio, transcript text, JWTs, API keys or purchase data
 * in logs. Only field names in ALLOWED_FIELDS are ever written.
 */
export const ALLOWED_FIELDS: ReadonlySet<string> = new Set([
  "region",
  "stream_id",
  "session_id",
  "uid",
  "kind",
  "lang",
  "asr_region",
  "model",
  "close_code",
  "reason",
  "audio_ms",
  "segments",
  "frames",
  "bytes",
  "duration_ms",
  "vendor_request_id",
  "client",
  "client_region",
  "error_kind",
  "attempt",
]);

export interface Logger {
  info(event: string, fields?: Record<string, unknown>): void;
  error(event: string, fields?: Record<string, unknown>): void;
}

export interface LoggerOptions {
  /** strict: a non-allowlisted field throws. Otherwise it is dropped and `dropped_field` is logged. */
  strict: boolean;
  /** Exact strings (for example the API key) that are replaced by [redacted] wherever they appear. */
  redact?: readonly string[];
  now?: () => number;
}

const JWT_LIKE = /[A-Za-z0-9_-]{2,}\.[A-Za-z0-9_-]{2,}\.[A-Za-z0-9_-]{2,}/g;

export function createLogger(write: (line: string) => void, opts: LoggerOptions = { strict: false }): Logger {
  const now = opts.now ?? Date.now;
  const secrets = (opts.redact ?? []).filter((s) => s.length > 0);

  function clean(value: unknown): string | number | boolean | null {
    if (typeof value === "number" || typeof value === "boolean" || value === null) return value;
    let s = typeof value === "string" ? value : String(value);
    for (const secret of secrets) s = s.split(secret).join("[redacted]");
    return s.replace(JWT_LIKE, "[redacted]");
  }

  function emit(level: "info" | "error", event: string, fields: Record<string, unknown> | undefined): void {
    const out: Record<string, unknown> = { ts: new Date(now()).toISOString(), level, event: clean(event) };
    const dropped: string[] = [];
    for (const [name, value] of Object.entries(fields ?? {})) {
      if (!ALLOWED_FIELDS.has(name) || name === "ts" || name === "level" || name === "event") {
        if (opts.strict) throw new Error(`log field not allowed: ${name}`);
        dropped.push(name);
        continue;
      }
      if (value === undefined) continue;
      if (typeof value === "object" && value !== null) {
        if (opts.strict) throw new Error(`log field must be a primitive: ${name}`);
        dropped.push(name);
        continue;
      }
      out[name] = clean(value);
    }
    write(JSON.stringify(out));
    for (const name of dropped) {
      // Field names themselves are not trusted (they could carry content): log only a fixed marker
      // plus the name if it is a short identifier.
      const safe = /^[a-z_]{1,32}$/.test(name) ? name : "invalid";
      write(JSON.stringify({ ts: out.ts, level: "error", event: "dropped_field", reason: safe }));
    }
  }

  return {
    info: (event, fields) => emit("info", event, fields),
    error: (event, fields) => emit("error", event, fields),
  };
}
