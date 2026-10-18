/**
 * Prints a development session JWT (default 12 h) for JWT_SECRET.
 * Usage: JWT_SECRET=... npx tsx tools/mint-dev-token.ts [--sub u] [--asr us|eu] [--kind paid|trial] [--allow-ms n] [--ttl-s n]
 */
import { signDevToken, type SessionClaims } from "../src/auth.js";

function arg(name: string, dflt: string): string {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1]! : dflt;
}

const secret = process.env.JWT_SECRET;
if (!secret) {
  process.stderr.write("JWT_SECRET is required\n");
  process.exit(1);
}
const asr = arg("asr", "us");
const kind = arg("kind", "paid");
if ((asr !== "us" && asr !== "eu") || (kind !== "paid" && kind !== "trial")) {
  process.stderr.write("invalid --asr or --kind\n");
  process.exit(1);
}
const claims: SessionClaims = {
  sub: arg("sub", "dev-user"),
  sid: arg("sid", "dev-session"),
  kind,
  allow_ms: Number(arg("allow-ms", "36000000")),
  lang: arg("lang", "en"),
  dev: arg("dev", "dev-device"),
  asr,
  cfgv: Number(arg("cfgv", "1")),
};
process.stdout.write(`${await signDevToken(claims, secret, Number(arg("ttl-s", String(12 * 3600))))}\n`);
