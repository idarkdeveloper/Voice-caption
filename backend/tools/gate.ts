/**
 * Slice 0 gate evaluator.
 *
 * Usage: npx tsx tools/gate.ts --summary <summary.json|results.jsonl> [more files ...]
 *          --billed-usd <amount> --metered-ms <ms>
 *
 * Each input is a replay summary (`--summary` output of replay.ts) or a raw results file (JSON
 * Lines, summarized on the fly). The vantage (us|eu|asia) comes from the label prefix. Exit code 0
 * = gate passed, 1 = failed, 2 = bad usage.
 */
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";
import { parseArgs } from "node:util";
import { costPerAudioHour } from "./cost-report.js";
import { parseResults, summarize, type Summary } from "./replay/metrics.js";

export { costPerAudioHour };

export type Vantage = "us" | "eu" | "asia";
export interface GateRun {
  label: string;
  vantage: Vantage;
  finalP95Ms: number;
  completeFalseShare: number;
}
export interface GateCheck {
  name: string;
  pass: boolean;
  value: number;
  limit: number;
  /** False for reported-only checks (asia). */
  gating: boolean;
}
export interface GateResult {
  pass: boolean;
  checks: GateCheck[];
  advice: string[];
  warnings: string[];
}

export const FINAL_P95_LIMIT_MS = 1300;
export const COST_LIMIT_USD = 0.46;
export const COMPLETE_FALSE_WARN = 0.01;

export function evaluateGate(input: { runs: GateRun[]; costPerAudioHourUsd: number }): GateResult {
  const checks: GateCheck[] = [];
  const advice: string[] = [];
  const warnings: string[] = [];

  for (const v of ["us", "eu"] as const) {
    if (!input.runs.some((r) => r.vantage === v)) {
      checks.push({ name: `runs present: ${v}`, pass: false, value: 0, limit: 1, gating: true });
    }
  }
  for (const r of input.runs) {
    checks.push({
      name: `final p95 ms: ${r.vantage} (${r.label})`,
      pass: r.finalP95Ms <= FINAL_P95_LIMIT_MS,
      value: r.finalP95Ms,
      limit: FINAL_P95_LIMIT_MS,
      gating: r.vantage !== "asia",
    });
    if (r.completeFalseShare > COMPLETE_FALSE_WARN) {
      warnings.push(
        `${r.label}: complete:false share ${(r.completeFalseShare * 100).toFixed(2)}% exceeds ${COMPLETE_FALSE_WARN * 100}%`,
      );
    }
  }
  checks.push({
    name: "cost per audio hour USD",
    pass: input.costPerAudioHourUsd <= COST_LIMIT_USD,
    value: input.costPerAudioHourUsd,
    limit: COST_LIMIT_USD,
    gating: true,
  });

  const failed = checks.filter((c) => !c.pass);
  if (failed.some((c) => c.name.startsWith("cost"))) {
    advice.push("lower paid_cap_ms (config), raise the Play price, or move to Deepgram Growth pricing");
  }
  if (failed.some((c) => c.gating && !c.name.startsWith("cost"))) {
    advice.push("check the proxy-to-Deepgram hop and endpointing before cutting regions");
  }
  if (failed.some((c) => !c.gating) && !failed.some((c) => c.gating && !c.name.startsWith("cost"))) {
    advice.push("apply the cut order in blueprint 1.4: cut asia-southeast1");
  }
  return { pass: checks.every((c) => c.pass || !c.gating), checks, advice, warnings };
}

export function vantageFromLabel(label: string): Vantage {
  const m = /^(us|eu|asia)(?![a-z])/i.exec(label.trim());
  if (!m) throw new Error(`label "${label}" must start with us, eu or asia to name its vantage`);
  return m[1]!.toLowerCase() as Vantage;
}

export function runFromSummary(s: Summary): GateRun {
  if (!s.final_after_finalize_ms) throw new Error(`summary "${s.label}" has no finalize measurements`);
  return {
    label: s.label,
    vantage: vantageFromLabel(s.label),
    finalP95Ms: s.final_after_finalize_ms.p95,
    completeFalseShare: s.complete_false_share,
  };
}

export function loadSummary(path: string): Summary {
  const text = readFileSync(path, "utf8");
  try {
    const v = JSON.parse(text) as Summary;
    if (v && typeof v === "object" && "final_after_finalize_ms" in v && "complete_false_share" in v) return v;
  } catch {
    // not a single JSON document: JSON Lines results
  }
  return summarize(parseResults(text));
}

function main(): void {
  const { values, positionals } = parseArgs({
    options: { summary: { type: "string" }, "billed-usd": { type: "string" }, "metered-ms": { type: "string" } },
    allowPositionals: true,
  });
  const files = [...(values.summary ? [values.summary] : []), ...positionals];
  const billed = Number(values["billed-usd"]);
  const metered = Number(values["metered-ms"]);
  if (files.length === 0 || !values["billed-usd"] || !values["metered-ms"] || !Number.isFinite(billed) || !Number.isFinite(metered)) {
    process.stderr.write("usage: gate.ts --summary <summary.json|results.jsonl> [more ...] --billed-usd <amount> --metered-ms <ms>\n");
    process.exit(2);
  }
  const result = evaluateGate({ runs: files.map((f) => runFromSummary(loadSummary(f))), costPerAudioHourUsd: costPerAudioHour(billed, metered) });
  const lines = [
    ...result.checks.map((c) => `${c.pass ? "PASS" : c.gating ? "FAIL" : "FAIL (not gating)"}  ${c.name}: ${c.value} (limit ${c.limit})`),
    ...result.warnings.map((w) => `WARN  ${w}`),
    ...result.advice.map((a) => `ADVICE  ${a}`),
    `GATE ${result.pass ? "PASSED" : "FAILED"}`,
  ];
  process.stdout.write(`${lines.join("\n")}\n`);
  process.exit(result.pass ? 0 : 1);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    main();
  } catch (e) {
    process.stderr.write(`gate failed: ${e instanceof Error ? e.message : "error"}\n`);
    process.exit(2);
  }
}
