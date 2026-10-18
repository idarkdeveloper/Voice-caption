import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { costPerAudioHour, evaluateGate, runFromSummary, vantageFromLabel } from "../tools/gate.js";
import { sumMeteredMs, loadMeteredMs } from "../tools/cost-report.js";
import type { ResultRecord } from "../tools/replay/metrics.js";

const okRuns = [
  { label: "us-east", vantage: "us" as const, finalP95Ms: 900, completeFalseShare: 0 },
  { label: "eu-west", vantage: "eu" as const, finalP95Ms: 1000, completeFalseShare: 0 },
  { label: "asia-se", vantage: "asia" as const, finalP95Ms: 1200, completeFalseShare: 0 },
];

describe("costPerAudioHour", () => {
  it("divides billed USD by audio hours", () => {
    expect(costPerAudioHour(2.31, 18_000_000)).toBeCloseTo(0.462, 10);
  });
  it("throws on zero or negative metered ms and on negative billing", () => {
    expect(() => costPerAudioHour(1, 0)).toThrow();
    expect(() => costPerAudioHour(1, -5)).toThrow();
    expect(() => costPerAudioHour(-1, 1000)).toThrow();
  });
});

describe("evaluateGate", () => {
  it("passes when everything is within limits", () => {
    const r = evaluateGate({ runs: okRuns, costPerAudioHourUsd: 0.44 });
    expect(r.pass).toBe(true);
    expect(r.checks.every((c) => c.pass)).toBe(true);
    expect(r.advice).toEqual([]);
  });

  it("p95 of exactly 1300 ms passes, 1301 ms fails and names the vantage", () => {
    expect(evaluateGate({ runs: [{ ...okRuns[0]!, finalP95Ms: 1300 }, okRuns[1]!], costPerAudioHourUsd: 0.4 }).pass).toBe(true);
    const r = evaluateGate({ runs: [{ ...okRuns[0]!, finalP95Ms: 1301 }, okRuns[1]!], costPerAudioHourUsd: 0.4 });
    expect(r.pass).toBe(false);
    const failed = r.checks.filter((c) => !c.pass);
    expect(failed).toHaveLength(1);
    expect(failed[0]!.name).toContain("us");
    expect(failed[0]).toMatchObject({ value: 1301, limit: 1300 });
    expect(r.advice.join(" ")).toContain("check the proxy-to-Deepgram hop and endpointing before cutting regions");
  });

  it("cost 0.4601 fails with the cap/price advice, 0.46 passes", () => {
    expect(evaluateGate({ runs: okRuns, costPerAudioHourUsd: 0.46 }).pass).toBe(true);
    const r = evaluateGate({ runs: okRuns, costPerAudioHourUsd: 0.4601 });
    expect(r.pass).toBe(false);
    expect(r.checks.find((c) => c.name.includes("cost"))).toMatchObject({ pass: false, value: 0.4601, limit: 0.46 });
    expect(r.advice).toContain("lower paid_cap_ms (config), raise the Play price, or move to Deepgram Growth pricing");
  });

  it("asia failing alone is reported but not gating, with the cut-order advice", () => {
    const r = evaluateGate({ runs: [okRuns[0]!, okRuns[1]!, { ...okRuns[2]!, finalP95Ms: 2500 }], costPerAudioHourUsd: 0.4 });
    expect(r.pass).toBe(true);
    expect(r.checks.find((c) => c.name.includes("asia"))).toMatchObject({ pass: false, value: 2500 });
    expect(r.advice).toContain("apply the cut order in blueprint 1.4: cut asia-southeast1");
  });

  it("completeFalseShare above 1% is only a warning", () => {
    const r = evaluateGate({ runs: [{ ...okRuns[0]!, completeFalseShare: 0.02 }, okRuns[1]!], costPerAudioHourUsd: 0.4 });
    expect(r.pass).toBe(true);
    expect(r.warnings).toHaveLength(1);
    expect(r.warnings[0]).toContain("us-east");
  });

  it("fails when a gating vantage has no run at all", () => {
    const r = evaluateGate({ runs: [okRuns[0]!], costPerAudioHourUsd: 0.4 });
    expect(r.pass).toBe(false);
    expect(r.checks.find((c) => !c.pass)!.name).toContain("eu");
  });

  it("every run of a gating vantage must pass", () => {
    const r = evaluateGate({
      runs: [okRuns[0]!, { ...okRuns[0]!, label: "us-global", finalP95Ms: 1500 }, okRuns[1]!],
      costPerAudioHourUsd: 0.4,
    });
    expect(r.pass).toBe(false);
  });
});

describe("cost-report and CLI helpers", () => {
  const recs: ResultRecord[] = [
    { label: "a", type: "stream", open_to_ready_ms: 10, audio_ms_sent: 3000 },
    { label: "a", clip: "c", seg: 1, ttfp_ms: 1, final_after_finalize_ms: 2, complete: true, audio_ms: 3000 },
    { label: "a", type: "stream", open_to_ready_ms: 10, audio_ms_sent: 4500 },
  ];
  it("sums metered ms over stream records only", () => {
    expect(sumMeteredMs(recs)).toBe(7500);
  });
  it("reads JSON Lines files and sums across them", () => {
    const dir = mkdtempSync(join(tmpdir(), "cost-"));
    const f1 = join(dir, "a.jsonl");
    const f2 = join(dir, "b.jsonl");
    writeFileSync(f1, recs.map((r) => JSON.stringify(r)).join("\n") + "\n");
    writeFileSync(f2, JSON.stringify(recs[0]) + "\n\n");
    expect(loadMeteredMs([f1, f2])).toBe(10500);
  });
  it("derives the vantage from the label and builds a run from a summary", () => {
    expect(vantageFromLabel("us-east4-global")).toBe("us");
    expect(vantageFromLabel("EU_west")).toBe("eu");
    expect(vantageFromLabel("asia-se")).toBe("asia");
    expect(() => vantageFromLabel("mars")).toThrow();
    const run = runFromSummary({
      label: "eu-west",
      segments: 10,
      streams: 2,
      open_to_ready_ms: { p50: 1, p95: 2 },
      ttfp_ms: { p50: 1, p95: 2 },
      final_after_finalize_ms: { p50: 500, p95: 800 },
      complete_false_share: 0.1,
      audio_ms_sent_total: 1,
    });
    expect(run).toEqual({ label: "eu-west", vantage: "eu", finalP95Ms: 800, completeFalseShare: 0.1 });
  });
});
