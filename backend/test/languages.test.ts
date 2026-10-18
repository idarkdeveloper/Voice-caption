import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import {
  computeCoverage,
  ModelsShapeError,
  renderGapTable,
  type DeepgramModelsResponse,
} from "../tools/check-deepgram-languages.js";

const here = dirname(fileURLToPath(import.meta.url));
const fixture = JSON.parse(
  readFileSync(join(here, "fixtures", "deepgram-models.json"), "utf8"),
) as DeepgramModelsResponse;
const GAPS = ["cy", "gl", "ht", "is", "mt", "sq", "sw"];
const appCodes = [
  "en", "de", "zh", "no", "he", "tl", "ms", "es", "pt", "da", "sv", ...GAPS,
];

describe("computeCoverage", () => {
  it("maps app codes to a streaming model and reports exactly the non-streamable codes as gaps", () => {
    const c = computeCoverage(appCodes, fixture);
    expect(c.gaps).toEqual(GAPS);
    for (const code of ["en", "de", "zh", "no", "he", "tl", "ms", "es", "pt", "da", "sv"]) {
      expect(c.supported[code]).toBe("nova-3");
    }
    expect(Object.keys(c.supported).sort()).toEqual(
      appCodes.filter((x) => !GAPS.includes(x)).sort(),
    );
  });

  it("maps the app's zh to Deepgram zh (regional variants collapse to the base code)", () => {
    const models: DeepgramModelsResponse = {
      stt: [{ name: "nova-3-general", canonical_name: "nova-3", languages: ["zh-CN", "zh-TW"], streaming: true }],
    };
    expect(computeCoverage(["zh"], models).supported).toEqual({ zh: "nova-3" });
  });

  it("counts a code served only by a batch-only model as a gap", () => {
    const models: DeepgramModelsResponse = {
      stt: [
        { name: "nova-3-general", canonical_name: "nova-3", languages: ["en"], streaming: true },
        { name: "batch-model", canonical_name: "batch-model", languages: ["cy"], streaming: false, batch: true },
      ],
    };
    const c = computeCoverage(["en", "cy"], models);
    expect(c.supported).toEqual({ en: "nova-3" });
    expect(c.gaps).toEqual(["cy"]);
  });

  it("falls back to another streaming model when nova-3 lacks the language", () => {
    const models: DeepgramModelsResponse = {
      stt: [
        { name: "nova-3-general", canonical_name: "nova-3", languages: ["en"], streaming: true },
        { name: "nova-2-general", canonical_name: "2-general", languages: ["da"], streaming: true },
      ],
    };
    expect(computeCoverage(["en", "da"], models).supported).toEqual({ en: "nova-3", da: "nova-2" });
  });

  it("ignores multi and tts entries and is order independent", () => {
    const shuffled: DeepgramModelsResponse = { ...fixture, stt: [...(fixture.stt ?? [])].reverse() };
    expect(computeCoverage(appCodes, shuffled)).toEqual(computeCoverage(appCodes, fixture));
  });

  it("fails loudly with the raw keys when the response shape is unexpected", () => {
    const bad = { voices: [], total: 3 } as unknown as DeepgramModelsResponse;
    expect(() => computeCoverage(appCodes, bad)).toThrow(ModelsShapeError);
    expect(() => computeCoverage(appCodes, bad)).toThrow(/total.*voices/);
  });

  it("fails loudly naming the entry keys when a model lacks languages or streaming", () => {
    const bad = { stt: [{ name: "x", langs: ["en"], stream: true }] } as unknown as DeepgramModelsResponse;
    expect(() => computeCoverage(appCodes, bad)).toThrow(ModelsShapeError);
    expect(() => computeCoverage(appCodes, bad)).toThrow(/langs.*name.*stream|name.*langs.*stream|langs, name, stream/);
  });
});

describe("renderGapTable", () => {
  const coverage = computeCoverage(appCodes, fixture);

  it("is deterministic and lists the gaps with names, sorted", () => {
    const a = renderGapTable(coverage, "2026-10-17");
    const b = renderGapTable(computeCoverage([...appCodes].reverse(), fixture), "2026-10-17");
    expect(a).toBe(b);
    expect(a).toContain("2026-10-17");
    expect(a).toContain(`${appCodes.length - GAPS.length} of ${appCodes.length}`);
    const order = GAPS.map((g) => a.indexOf(`| \`${g}\``));
    expect(order.every((i) => i > 0)).toBe(true);
    expect([...order].sort((x, y) => x - y)).toEqual(order);
    expect(a).toContain("Haitian Creole");
  });

  it("carries the preliminary label only when asked", () => {
    expect(renderGapTable(coverage, "2026-10-17")).not.toContain("PRELIMINARY");
    expect(renderGapTable(coverage, "2026-10-17", { preliminary: true })).toContain(
      "PRELIMINARY: re-generate with a real key in Task 10",
    );
  });
});

describe("committed snapshot", () => {
  it("covers 50 of the app's 57 languages with the preliminary gaps", () => {
    const snap = JSON.parse(
      readFileSync(join(here, "..", "config", "deepgram-languages.json"), "utf8"),
    ) as { languages: Record<string, string>; preliminary?: boolean };
    expect(Object.keys(snap.languages)).toHaveLength(50);
    for (const g of GAPS) expect(snap.languages).not.toHaveProperty(g);
    expect(snap.preliminary).toBe(true);
  });
});

describe("CLI main with --models-file (no network)", () => {
  it("writes the config snapshot and the gap doc", async () => {
    const { mkdtempSync, writeFileSync } = await import("node:fs");
    const { tmpdir } = await import("node:os");
    const { main } = await import("../tools/check-deepgram-languages.js");
    const dir = mkdtempSync(join(tmpdir(), "langs-"));
    writeFileSync(join(dir, "app.json"), JSON.stringify(appCodes));
    await main(
      [
        "--app", join(dir, "app.json"),
        "--models-file", join(here, "fixtures", "deepgram-models.json"),
        "--out-config", join(dir, "cfg.json"),
        "--out-doc", join(dir, "gaps.md"),
      ],
      { FETCHED_AT: "2026-10-17" },
    );
    const cfg = JSON.parse(readFileSync(join(dir, "cfg.json"), "utf8")) as { languages: Record<string, string> };
    expect(Object.keys(cfg.languages)).toHaveLength(appCodes.length - GAPS.length);
    expect(readFileSync(join(dir, "gaps.md"), "utf8")).toContain("| `sw` | Swahili |");
  });
});
