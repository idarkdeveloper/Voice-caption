import { describe, expect, it } from "vitest";
import { ALLOWED_FIELDS, createLogger } from "../src/log.js";

function capture(strict: boolean, redact?: string[]) {
  const lines: string[] = [];
  const log = createLogger((l) => lines.push(l), { strict, redact, now: () => 0 });
  return { lines, log };
}

describe("logger", () => {
  it("writes one JSON object per line with ts, level and event", () => {
    const { lines, log } = capture(true);
    log.info("stream_open", { region: "us-east4", audio_ms: 5 });
    log.error("boom", { error_kind: "auth" });
    expect(lines).toHaveLength(2);
    const a = JSON.parse(lines[0]!);
    expect(a).toMatchObject({ level: "info", event: "stream_open", region: "us-east4", audio_ms: 5 });
    expect(typeof a.ts).toBe("string");
    expect(JSON.parse(lines[1]!).level).toBe("error");
  });

  it.each(["text", "transcript", "token", "authorization", "api_key", "whatever"])(
    "strict mode throws on field %s",
    (name) => {
      const { log } = capture(true);
      expect(() => log.info("x", { [name]: "hello" })).toThrow();
    },
  );

  it("non strict mode drops the field and emits dropped_field", () => {
    const { lines, log } = capture(false);
    log.info("x", { text: "secret words", region: "r" });
    const all = lines.join("\n");
    expect(all).not.toContain("secret words");
    const events = lines.map((l) => JSON.parse(l).event);
    expect(events).toEqual(["x", "dropped_field"]);
    expect(JSON.parse(lines[0]!).region).toBe("r");
  });

  it("redacts JWT looking values in allowed fields", () => {
    const { lines, log } = capture(true);
    log.info("x", { reason: "bad eyJhbGciOi.eyJzdWIiOi.abcDEF123 token" });
    expect(lines[0]).not.toContain("eyJhbGciOi");
    expect(lines[0]).toContain("[redacted]");
  });

  it("redacts configured secrets", () => {
    const { lines, log } = capture(true, ["dg-key-123"]);
    log.info("x", { reason: "failed with dg-key-123 here" });
    expect(lines[0]).not.toContain("dg-key-123");
  });

  it("allowlist contains the planned names", () => {
    for (const n of ["region", "stream_id", "audio_ms", "close_code", "client_region", "attempt"]) {
      expect(ALLOWED_FIELDS.has(n)).toBe(true);
    }
    expect(ALLOWED_FIELDS.has("text")).toBe(false);
  });
});
