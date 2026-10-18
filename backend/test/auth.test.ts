import { describe, expect, it } from "vitest";
import { SignJWT } from "jose";
import { AuthError, signDevToken, verifySession, type SessionClaims } from "../src/auth.js";

const SECRET = "unit-test-secret-0123456789";
const claims: SessionClaims = {
  sub: "u-1",
  sid: "s-1",
  kind: "paid",
  allow_ms: 600_000,
  lang: "en",
  dev: "d-1",
  asr: "us",
  cfgv: 3,
};
const key = (s: string) => new TextEncoder().encode(s);
const b64 = (o: unknown) => Buffer.from(JSON.stringify(o)).toString("base64url");

describe("session tokens", () => {
  it("a signed token verifies and returns the claims", async () => {
    const token = await signDevToken(claims, SECRET);
    expect(await verifySession(token, SECRET)).toEqual(claims);
  });

  it("rejects a wrong secret", async () => {
    const token = await signDevToken(claims, SECRET);
    await expect(verifySession(token, "another-secret-0123456789")).rejects.toBeInstanceOf(AuthError);
  });

  it("rejects an expired token", async () => {
    const token = await signDevToken(claims, SECRET, -60);
    await expect(verifySession(token, SECRET)).rejects.toBeInstanceOf(AuthError);
  });

  it("rejects the wrong audience", async () => {
    const now = Math.floor(Date.now() / 1000);
    const token = await new SignJWT({ ...claims, sub: undefined })
      .setProtectedHeader({ alg: "HS256" })
      .setSubject(claims.sub)
      .setAudience("other-service")
      .setIssuedAt(now)
      .setExpirationTime(now + 60)
      .sign(key(SECRET));
    await expect(verifySession(token, SECRET)).rejects.toBeInstanceOf(AuthError);
  });

  it("rejects alg none", async () => {
    const now = Math.floor(Date.now() / 1000);
    const token = `${b64({ alg: "none", typ: "JWT" })}.${b64({ ...claims, aud: "captions-ws", iat: now, exp: now + 60 })}.`;
    await expect(verifySession(token, SECRET)).rejects.toBeInstanceOf(AuthError);
  });

  it("rejects garbage and tokens missing claims", async () => {
    await expect(verifySession("not-a-jwt", SECRET)).rejects.toBeInstanceOf(AuthError);
    const now = Math.floor(Date.now() / 1000);
    const token = await new SignJWT({ sid: "x" })
      .setProtectedHeader({ alg: "HS256" })
      .setSubject("u")
      .setAudience("captions-ws")
      .setIssuedAt(now)
      .setExpirationTime(now + 60)
      .sign(key(SECRET));
    await expect(verifySession(token, SECRET)).rejects.toBeInstanceOf(AuthError);
  });

  it("AuthError messages never contain the token", async () => {
    const token = await signDevToken(claims, SECRET);
    try {
      await verifySession(token, "another-secret-0123456789");
    } catch (e) {
      expect((e as Error).message).not.toContain(token);
      expect((e as Error).message).not.toContain(SECRET);
    }
  });
});
