import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, writeFileSync, chmodSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const deployDir = join(dirname(fileURLToPath(import.meta.url)), "..", "deploy");
const hasBash = spawnSync("bash", ["--version"]).status === 0;
const hasShellcheck = spawnSync("shellcheck", ["--version"]).status === 0;

const CANARY_KEY = "CANARY-DEEPGRAM-KEY-0123456789";
const CANARY_JWT = "CANARY-JWT-SECRET-9876543210";

/** Runs a script with a stub `gcloud` first on PATH that records any invocation. */
function runDry(script: string, args: string[]) {
  const dir = mkdtempSync(join(tmpdir(), "dryrun-"));
  const marker = join(dir, "gcloud-was-called");
  const stub = join(dir, "gcloud");
  writeFileSync(stub, `#!/bin/sh\necho called > "${marker}"\nexit 1\n`);
  chmodSync(stub, 0o755);
  const r = spawnSync("bash", [join(deployDir, script), ...args, "--dry-run"], {
    encoding: "utf8",
    env: {
      PATH: `${dir}:${process.env.PATH ?? ""}`,
      DEEPGRAM_API_KEY: CANARY_KEY,
      JWT_SECRET: CANARY_JWT,
    },
  });
  return { ...r, gcloudCalled: existsSync(marker), all: `${r.stdout}${r.stderr}` };
}

describe.skipIf(!hasBash)("deploy scripts --dry-run (skipped: bash not installed)", () => {
  it("deploy-region.sh prints the required Cloud Run settings and nothing secret", () => {
    const r = runDry("deploy-region.sh", ["demo-project", "us-east4"]);
    expect(r.status).toBe(0);
    for (const flag of [
      "--timeout=3600",
      "--min-instances=1",
      "--max-instances=20",
      "--no-cpu-throttling",
      "--concurrency=250",
      "--cpu-boost",
    ]) {
      expect(r.stdout).toContain(flag);
    }
    expect(r.stdout).toContain("--set-secrets=DEEPGRAM_API_KEY=deepgram-api-key:latest,JWT_SECRET=jwt-secret:latest");
    expect(r.all).not.toContain(CANARY_KEY);
    expect(r.all).not.toContain(CANARY_JWT);
    expect(r.gcloudCalled).toBe(false);
  });

  it("setup-global-lb.sh prints the backend timeout, region header and throttle, nothing secret", () => {
    const r = runDry("setup-global-lb.sh", ["demo-project", "example.com"]);
    expect(r.status).toBe(0);
    expect(r.stdout).toContain("--timeout=3600");
    expect(r.stdout).toContain("X-Client-Region:{client_region}");
    expect(r.stdout).toContain("--outlier-detection=");
    expect(r.stdout).toContain("--action=throttle");
    expect(r.stdout).toContain("--domains=example.com");
    for (const region of ["us-east4", "europe-west4", "asia-southeast1"]) {
      expect(r.stdout).toContain(`captions-neg-${region}`);
    }
    expect(r.all).not.toContain(CANARY_KEY);
    expect(r.all).not.toContain(CANARY_JWT);
    expect(r.gcloudCalled).toBe(false);
  });

  it("rejects an unsupported region and missing arguments without calling gcloud", () => {
    expect(runDry("deploy-region.sh", ["demo-project", "mars-1"]).status).toBe(2);
    expect(runDry("deploy-region.sh", []).status).toBe(2);
    expect(runDry("setup-global-lb.sh", ["only-one"]).status).toBe(2);
  });

  it("scripts take no secret values and use strict mode", () => {
    for (const f of ["deploy-region.sh", "setup-global-lb.sh", "lib.sh"]) {
      const text = readFileSync(join(deployDir, f), "utf8");
      if (f !== "lib.sh") expect(text).toContain("set -euo pipefail");
      expect(text).not.toMatch(/\$\{?(DEEPGRAM_API_KEY|JWT_SECRET)\b/);
    }
  });

  it("syntax check: bash -n", () => {
    for (const f of ["deploy-region.sh", "setup-global-lb.sh", "lib.sh"]) {
      expect(spawnSync("bash", ["-n", join(deployDir, f)]).status).toBe(0);
    }
  });

  it.skipIf(!hasShellcheck)("shellcheck is clean (skipped: shellcheck not installed)", () => {
    const r = spawnSync("shellcheck", ["-x", "deploy-region.sh", "setup-global-lb.sh", "lib.sh"], {
      cwd: deployDir,
      encoding: "utf8",
    });
    expect(r.stdout).toBe("");
    expect(r.status).toBe(0);
  });
});
