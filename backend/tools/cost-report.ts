/**
 * Cost per audio hour: sums the metered audio (bytes/32 ms, `audio_ms_sent` of the stream records)
 * from replay result files (JSON Lines) and divides a billed USD amount by the audio hours.
 *
 * Usage: npx tsx tools/cost-report.ts --billed-usd <amount> <results.jsonl> [more.jsonl ...]
 *        (also accepted: --results <file> before the list; shell globs expand into the list)
 */
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";
import { parseArgs } from "node:util";
import { isStreamRecord, parseResults, type ResultRecord } from "./replay/metrics.js";

export const MS_PER_HOUR = 3_600_000;

export function costPerAudioHour(billedUsd: number, meteredAudioMs: number): number {
  if (!Number.isFinite(billedUsd) || billedUsd < 0) throw new Error("billed USD must be a non-negative number");
  if (!Number.isFinite(meteredAudioMs) || meteredAudioMs <= 0) throw new Error("metered audio ms must be greater than zero");
  return billedUsd / (meteredAudioMs / MS_PER_HOUR);
}

export function sumMeteredMs(records: ResultRecord[]): number {
  return records.filter(isStreamRecord).reduce((a, r) => a + r.audio_ms_sent, 0);
}

export function loadMeteredMs(files: string[]): number {
  return files.reduce((a, f) => a + sumMeteredMs(parseResults(readFileSync(f, "utf8"))), 0);
}

function main(): void {
  const { values, positionals } = parseArgs({
    options: { "billed-usd": { type: "string" }, results: { type: "string" } },
    allowPositionals: true,
  });
  const files = [...(values.results ? [values.results] : []), ...positionals];
  const billed = Number(values["billed-usd"]);
  if (!values["billed-usd"] || !Number.isFinite(billed) || files.length === 0) {
    process.stderr.write("usage: cost-report.ts --billed-usd <amount> <results.jsonl> [more.jsonl ...]\n");
    process.exit(2);
  }
  const ms = loadMeteredMs(files);
  const cost = costPerAudioHour(billed, ms);
  process.stdout.write(
    `${JSON.stringify({ files: files.length, metered_ms: ms, audio_hours: ms / MS_PER_HOUR, billed_usd: billed, cost_per_audio_hour_usd: Number(cost.toFixed(4)) }, null, 2)}\n`,
  );
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    main();
  } catch (e) {
    process.stderr.write(`cost-report failed: ${e instanceof Error ? e.message : "error"}\n`);
    process.exit(1);
  }
}
