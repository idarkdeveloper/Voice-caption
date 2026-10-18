import { SignJWT, jwtVerify, errors } from "jose";

export const AUDIENCE = "captions-ws";

export interface SessionClaims {
  sub: string;
  sid: string;
  kind: "paid" | "trial";
  allow_ms: number;
  lang: string;
  dev: string;
  asr: "us" | "eu";
  cfgv: number;
}

/** `reason` is a fixed machine string. The message never contains the token or the secret. */
export class AuthError extends Error {
  constructor(public readonly reason: "expired" | "invalid") {
    super(`auth failed: ${reason}`);
    this.name = "AuthError";
  }
}

const key = (secret: string) => new TextEncoder().encode(secret);

/** Signs a development HS256 session token. A negative `ttlS` yields an already expired token (tests). */
export async function signDevToken(claims: SessionClaims, secret: string, ttlS = 12 * 3600): Promise<string> {
  const iat = Math.floor(Date.now() / 1000);
  const { sub, ...rest } = claims;
  return new SignJWT({ ...rest })
    .setProtectedHeader({ alg: "HS256", typ: "JWT" })
    .setSubject(sub)
    .setAudience(AUDIENCE)
    .setIssuedAt(iat)
    .setExpirationTime(iat + ttlS)
    .sign(key(secret));
}

export async function verifySession(token: string, secret: string): Promise<SessionClaims> {
  let p: Record<string, unknown>;
  try {
    ({ payload: p } = await jwtVerify(token, key(secret), { algorithms: ["HS256"], audience: AUDIENCE }));
  } catch (e) {
    throw new AuthError(e instanceof errors.JWTExpired ? "expired" : "invalid");
  }
  const str = (v: unknown): v is string => typeof v === "string" && v.length > 0;
  const num = (v: unknown): v is number => typeof v === "number" && Number.isFinite(v) && v >= 0;
  if (
    !str(p.sub) ||
    !str(p.sid) ||
    (p.kind !== "paid" && p.kind !== "trial") ||
    !num(p.allow_ms) ||
    !str(p.lang) ||
    !str(p.dev) ||
    (p.asr !== "us" && p.asr !== "eu") ||
    !num(p.cfgv)
  ) {
    throw new AuthError("invalid");
  }
  return {
    sub: p.sub,
    sid: p.sid,
    kind: p.kind,
    allow_ms: p.allow_ms,
    lang: p.lang,
    dev: p.dev,
    asr: p.asr,
    cfgv: p.cfgv,
  };
}
