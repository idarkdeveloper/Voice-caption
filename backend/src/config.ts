import { readFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

export interface Config {
  port: number;
  region: string;
  deepgramApiKey: string;
  deepgramHosts: { us: string; eu: string };
  jwtSecret: string;
  supportedLanguages: Set<string>;
  maxStreamMs: number;
}

export class ConfigError extends Error {
  constructor(public readonly problems: string[]) {
    super(`invalid configuration: ${problems.join("; ")}`);
    this.name = "ConfigError";
  }
}

const DEFAULT_HOST_US = "wss://api.deepgram.com/v1/listen";
const DEFAULT_HOST_EU = "wss://api.eu.deepgram.com/v1/listen";

function defaultLanguagesFile(): string {
  return join(dirname(fileURLToPath(import.meta.url)), "..", "config", "deepgram-languages.json");
}

/** Accepts `{ languages: { code: model } }`, `{ languages: [codes] }` or a bare array of codes. */
function readLanguagesFile(path: string, problems: string[]): Set<string> {
  try {
    const parsed: unknown = JSON.parse(readFileSync(path, "utf8"));
    const list =
      Array.isArray(parsed) ? parsed
      : parsed && typeof parsed === "object" && "languages" in parsed ?
        Array.isArray(parsed.languages) ? parsed.languages
        : parsed.languages && typeof parsed.languages === "object" ? Object.keys(parsed.languages)
        : null
      : null;
    if (!list || !list.every((c): c is string => typeof c === "string")) {
      problems.push("LANGUAGES_FILE has an unexpected shape");
      return new Set();
    }
    return new Set(list);
  } catch {
    problems.push("LANGUAGES_FILE is not readable JSON");
    return new Set();
  }
}

function parseInteger(env: NodeJS.ProcessEnv, name: string, dflt: number, problems: string[]): number {
  const raw = env[name];
  if (raw === undefined || raw === "") return dflt;
  if (!/^\d+$/.test(raw)) {
    problems.push(`${name} must be a positive integer`);
    return dflt;
  }
  const n = Number(raw);
  if (n <= 0) problems.push(`${name} must be a positive integer`);
  return n;
}

export function loadConfig(env: NodeJS.ProcessEnv): Config {
  const problems: string[] = [];
  const deepgramApiKey = env.DEEPGRAM_API_KEY ?? "";
  if (!deepgramApiKey) problems.push("DEEPGRAM_API_KEY is required");
  const jwtSecret = env.JWT_SECRET ?? "";
  if (!jwtSecret) problems.push("JWT_SECRET is required");
  const port = parseInteger(env, "PORT", 8080, problems);
  const maxStreamMs = parseInteger(env, "MAX_STREAM_MS", 3_300_000, problems);

  let supportedLanguages = new Set<string>();
  if (env.SUPPORTED_LANGUAGES !== undefined) {
    supportedLanguages = new Set(
      env.SUPPORTED_LANGUAGES.split(",").map((s) => s.trim()).filter((s) => s.length > 0),
    );
  } else {
    const file = env.LANGUAGES_FILE ?? defaultLanguagesFile();
    if (existsSync(file)) supportedLanguages = readLanguagesFile(file, problems);
    else if (env.LANGUAGES_FILE) problems.push("LANGUAGES_FILE does not exist");
  }

  if (problems.length > 0) throw new ConfigError(problems);
  return {
    port,
    region: env.REGION || "local",
    deepgramApiKey,
    deepgramHosts: {
      us: env.DEEPGRAM_HOST_US || DEFAULT_HOST_US,
      eu: env.DEEPGRAM_HOST_EU || DEFAULT_HOST_EU,
    },
    jwtSecret,
    supportedLanguages,
    maxStreamMs,
  };
}
