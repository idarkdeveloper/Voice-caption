/**
 * Deepgram language coverage check (Task 7).
 *
 *   DEEPGRAM_API_KEY=... npx tsx tools/check-deepgram-languages.ts \
 *     --app ../app/build/outputs/supported-languages.json
 *
 * Fetches GET https://api.deepgram.com/v1/models, intersects it with the app's language codes and
 * writes config/deepgram-languages.json plus docs/design/deepgram-language-gaps.md.
 *
 * The /v1/models response shape is UNVERIFIED. This tool parses it defensively and fails loudly,
 * printing the raw keys it saw, when the expected fields are missing. Fallback: see README
 * ("Language coverage check").
 */
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export interface DeepgramModel {
  name?: string;
  canonical_name?: string;
  languages?: string[];
  streaming?: boolean;
  batch?: boolean;
  [key: string]: unknown;
}

/** Expected: `{ stt: DeepgramModel[] }`. Other shapes are tried, then rejected. */
export type DeepgramModelsResponse = { stt?: DeepgramModel[]; [key: string]: unknown };

export interface Coverage {
  /** app language code -> Deepgram model id used for streaming */
  supported: Record<string, string>;
  /** app language codes that cannot be streamed, sorted */
  gaps: string[];
}

export class ModelsShapeError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ModelsShapeError";
  }
}

const LIST_KEYS = ["stt", "models", "data"] as const;
/** Deepgram language tags that differ from the app's two-letter codes. */
const ALIASES: Readonly<Record<string, string>> = { nb: "no", nn: "no", iw: "he", fil: "tl" };

function keysOf(value: unknown): string {
  if (value && typeof value === "object" && !Array.isArray(value)) {
    return `[${Object.keys(value).sort().join(", ")}]`;
  }
  return Array.isArray(value) ? "[array]" : `[${typeof value}]`;
}

function modelList(models: unknown): unknown[] {
  if (Array.isArray(models)) return models;
  if (models && typeof models === "object") {
    for (const key of LIST_KEYS) {
      const v = (models as Record<string, unknown>)[key];
      if (Array.isArray(v)) return v;
    }
  }
  throw new ModelsShapeError(
    `unexpected /v1/models response: no model list found under ${LIST_KEYS.join("/")}; ` +
      `top-level keys are ${keysOf(models)}. Use the fallback in backend/README.md.`,
  );
}

function baseLanguage(tag: string): string | null {
  const base = (tag.split(/[-_]/)[0] ?? "").toLowerCase();
  if (base === "" || base === "multi") return null;
  return ALIASES[base] ?? base;
}

/** Lower is better. nova-3 general first, then nova-2 general, then everything else by name. */
function modelRank(id: string): number {
  if (id === "nova-3") return 0;
  if (id === "nova-2") return 1;
  return id.startsWith("nova-3") ? 2 : 3;
}

function modelId(m: DeepgramModel): string {
  const raw = (m.canonical_name || m.name || "").toLowerCase();
  if (raw === "nova-3" || raw === "nova-3-general") return "nova-3";
  if (raw === "nova-2" || raw === "nova-2-general" || raw === "2-general") return "nova-2";
  return raw;
}

export function computeCoverage(appCodes: string[], models: DeepgramModelsResponse): Coverage {
  const best = new Map<string, string>();
  for (const entry of modelList(models)) {
    const m = entry as DeepgramModel;
    const ok =
      entry !== null &&
      typeof entry === "object" &&
      Array.isArray(m.languages) &&
      m.languages.every((l) => typeof l === "string") &&
      typeof m.streaming === "boolean" &&
      (typeof m.name === "string" || typeof m.canonical_name === "string");
    if (!ok) {
      throw new ModelsShapeError(
        `unexpected /v1/models entry: expected name, languages[] and streaming(boolean); ` +
          `entry keys are ${keysOf(entry)}. Use the fallback in backend/README.md.`,
      );
    }
    if (!m.streaming) continue;
    const id = modelId(m);
    if (id === "") continue;
    for (const tag of m.languages ?? []) {
      const code = baseLanguage(tag);
      if (code === null) continue;
      const current = best.get(code);
      if (
        current === undefined ||
        modelRank(id) < modelRank(current) ||
        (modelRank(id) === modelRank(current) && id < current)
      ) {
        best.set(code, id);
      }
    }
  }
  const supported: Record<string, string> = {};
  const gaps: string[] = [];
  for (const code of [...new Set(appCodes)].sort()) {
    const model = best.get(code);
    if (model === undefined) gaps.push(code);
    else supported[code] = model;
  }
  return { supported, gaps };
}

function languageName(code: string): string {
  try {
    return new Intl.DisplayNames(["en"], { type: "language" }).of(code) ?? code;
  } catch {
    return code;
  }
}

export function renderGapTable(
  c: Coverage,
  fetchedAt: string,
  opts: { preliminary?: boolean } = {},
): string {
  const covered = Object.keys(c.supported).length;
  const total = covered + c.gaps.length;
  const lines: string[] = ["# Deepgram language coverage", ""];
  if (opts.preliminary) {
    lines.push(
      "> **PRELIMINARY: re-generate with a real key in Task 10.** Seeded by hand from the language",
      "> list on Deepgram's models page (see section 1.9 of the paid-tier backend design). It was not",
      "> produced by `tools/check-deepgram-languages.ts`.",
      "",
    );
  } else {
    lines.push(
      "Generated by `backend/tools/check-deepgram-languages.ts`. Do not edit by hand.",
      "",
    );
  }
  lines.push(
    `Date: ${fetchedAt}`,
    "",
    `Streaming coverage: **${covered} of ${total}** app languages.`,
    "",
    "| Gap (on-device only) | Language |",
    "|---|---|",
  );
  for (const code of [...c.gaps].sort()) lines.push(`| \`${code}\` | ${languageName(code)} |`);
  if (c.gaps.length === 0) lines.push("| none | |");
  lines.push("");
  return lines.join("\n");
}

// ---------------------------------------------------------------------------------------------
// CLI

function arg(args: string[], name: string): string | undefined {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : undefined;
}

async function fetchModels(apiKey: string): Promise<unknown> {
  const res = await fetch("https://api.deepgram.com/v1/models", {
    headers: { Authorization: `Token ${apiKey}` },
  });
  if (!res.ok) throw new Error(`GET /v1/models failed with HTTP ${res.status}`);
  return res.json();
}

export async function main(args: string[], env: NodeJS.ProcessEnv): Promise<void> {
  const root = join(dirname(fileURLToPath(import.meta.url)), "..");
  const appFile = arg(args, "--app");
  if (!appFile) throw new Error("usage: --app <supported-languages.json> [--models-file <file>] [--out-config <file>] [--out-doc <file>]");
  const appCodes: unknown = JSON.parse(readFileSync(resolve(appFile), "utf8"));
  if (!Array.isArray(appCodes) || !appCodes.every((c): c is string => typeof c === "string")) {
    throw new Error("--app file must be a JSON array of language codes");
  }
  const modelsFile = arg(args, "--models-file");
  let models: unknown;
  if (modelsFile) {
    models = JSON.parse(readFileSync(resolve(modelsFile), "utf8"));
  } else {
    const apiKey = env.DEEPGRAM_API_KEY;
    if (!apiKey) throw new Error("DEEPGRAM_API_KEY is required (or pass --models-file)");
    models = await fetchModels(apiKey);
  }
  const coverage = computeCoverage(appCodes, models as DeepgramModelsResponse);
  const fetchedAt = (env.FETCHED_AT || new Date().toISOString()).slice(0, 10);
  const outConfig = resolve(arg(args, "--out-config") ?? join(root, "config", "deepgram-languages.json"));
  const outDoc = resolve(arg(args, "--out-doc") ?? join(root, "..", "docs", "design", "deepgram-language-gaps.md"));
  writeFileSync(
    outConfig,
    `${JSON.stringify({ fetchedAt, source: "GET /v1/models", languages: coverage.supported }, null, 2)}\n`,
  );
  writeFileSync(outDoc, renderGapTable(coverage, fetchedAt));
  process.stdout.write(
    `${Object.keys(coverage.supported).length} of ${appCodes.length} covered; gaps: ${coverage.gaps.join(", ") || "none"}\n`,
  );
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2), process.env).catch((e: unknown) => {
    process.stderr.write(`${e instanceof Error ? e.message : "failed"}\n`);
    process.exit(1);
  });
}
