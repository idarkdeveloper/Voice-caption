import { describe, expect, it } from "vitest";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { ConfigError, loadConfig } from "../src/config.js";

const base = { DEEPGRAM_API_KEY: "dg-key", JWT_SECRET: "s3cret-s3cret" };

describe("loadConfig", () => {
  it("reports every missing required variable in one error", () => {
    let err: unknown;
    try {
      loadConfig({});
    } catch (e) {
      err = e;
    }
    expect(err).toBeInstanceOf(ConfigError);
    const msg = (err as ConfigError).message;
    expect(msg).toContain("DEEPGRAM_API_KEY");
    expect(msg).toContain("JWT_SECRET");
  });

  it("rejects a non numeric PORT", () => {
    expect(() => loadConfig({ ...base, PORT: "abc" })).toThrow(/PORT/);
  });

  it("applies defaults", () => {
    const cfg = loadConfig({ ...base, LANGUAGES_FILE: undefined });
    expect(cfg.port).toBe(8080);
    expect(cfg.maxStreamMs).toBe(3_300_000);
    expect(cfg.deepgramHosts).toEqual({
      us: "wss://api.deepgram.com/v1/listen",
      eu: "wss://api.eu.deepgram.com/v1/listen",
    });
    expect(cfg.deepgramApiKey).toBe("dg-key");
  });

  it("parses SUPPORTED_LANGUAGES into a set", () => {
    const cfg = loadConfig({ ...base, SUPPORTED_LANGUAGES: "en, es,fr,," });
    expect([...cfg.supportedLanguages].sort()).toEqual(["en", "es", "fr"]);
  });

  it("reads the default languages from a file when present", () => {
    const dir = mkdtempSync(join(tmpdir(), "cfg-"));
    const file = join(dir, "langs.json");
    writeFileSync(file, JSON.stringify({ fetchedAt: "x", languages: { en: "nova-3", de: "nova-3" } }));
    const cfg = loadConfig({ ...base, LANGUAGES_FILE: file });
    expect([...cfg.supportedLanguages].sort()).toEqual(["de", "en"]);
  });

  it("env list wins over the file", () => {
    const dir = mkdtempSync(join(tmpdir(), "cfg-"));
    const file = join(dir, "langs.json");
    writeFileSync(file, JSON.stringify({ languages: { en: "nova-3" } }));
    const cfg = loadConfig({ ...base, LANGUAGES_FILE: file, SUPPORTED_LANGUAGES: "fr" });
    expect([...cfg.supportedLanguages]).toEqual(["fr"]);
  });

  it("does not echo secrets in errors", () => {
    try {
      loadConfig({ ...base, PORT: "abc" });
    } catch (e) {
      expect((e as Error).message).not.toContain("dg-key");
      expect((e as Error).message).not.toContain("s3cret");
    }
  });
});
