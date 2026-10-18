import { describe, expect, it } from "vitest";
import { AudioTokenBucket } from "../src/proxy/ratelimit.js";

describe("AudioTokenBucket", () => {
  it("allows a 5 s burst instantly and then refuses", () => {
    const b = new AudioTokenBucket({ ratePerS: 1.5, burstS: 5, now: () => 0 });
    for (let i = 0; i < 50; i++) expect(b.take(100)).toBe(true);
    expect(b.take(100)).toBe(false);
  });

  it("passes 1.4x real time for 60 s of fake clock", () => {
    let t = 0;
    const b = new AudioTokenBucket({ ratePerS: 1.5, burstS: 5, now: () => t });
    // 100 ms of audio every 100/1.4 ms
    let sentMs = 0;
    while (t < 60_000) {
      expect(b.take(100), `at t=${t}`).toBe(true);
      sentMs += 100;
      t += 100 / 1.4;
    }
    expect(sentMs).toBeGreaterThan(80_000);
  });

  it("fails 1.6x real time once the burst is spent", () => {
    let t = 0;
    const b = new AudioTokenBucket({ ratePerS: 1.5, burstS: 5, now: () => t });
    let failedAt = -1;
    while (t < 120_000) {
      if (!b.take(100)) {
        failedAt = t;
        break;
      }
      t += 100 / 1.6;
    }
    expect(failedAt).toBeGreaterThan(40_000); // burst lasts ~50 s at a 0.1 s/s deficit
    expect(failedAt).toBeLessThan(60_000);
  });

  it("refills with time and never exceeds the burst", () => {
    let t = 0;
    const b = new AudioTokenBucket({ ratePerS: 1.5, burstS: 5, now: () => t });
    expect(b.take(5000)).toBe(true);
    expect(b.take(1)).toBe(false);
    t += 1000;
    expect(b.take(1500)).toBe(true);
    t += 60_000;
    expect(b.take(5001)).toBe(false);
    expect(b.take(5000)).toBe(true);
  });
});
