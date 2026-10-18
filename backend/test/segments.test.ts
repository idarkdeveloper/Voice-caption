import { describe, expect, it } from "vitest";
import type { VendorResult } from "../src/asr/vendor.js";
import { SegmentTracker, type TrackerEvent } from "../src/proxy/segments.js";

const BYTES_PER_100MS = 3200;

function make(segBase = 1) {
  let t = 1_000;
  const tracker = new SegmentTracker({ segBase, now: () => t });
  return {
    tracker,
    advance: (ms: number) => (t += ms),
    time: () => t,
  };
}

const res = (text: string, startMs: number, o: Partial<VendorResult> = {}): VendorResult => ({
  text,
  startMs,
  endMs: startMs + 100,
  isFinal: false,
  fromFinalize: false,
  ...o,
});

const finals = (evs: TrackerEvent[]) => evs.filter((e) => e.kind === "final");

describe("SegmentTracker", () => {
  it("(a) joins interim and final chunks into the partial text in order", () => {
    const { tracker } = make();
    tracker.onAudio(BYTES_PER_100MS * 10);
    const e1 = tracker.onVendorResult(res("hello", 0));
    const e2 = tracker.onVendorResult(res("hello there", 0, { isFinal: true }));
    const e3 = tracker.onVendorResult(res("how", 300));
    const e4 = tracker.onVendorResult(res("how are", 300));
    const e5 = tracker.onVendorResult(res("how are you", 300, { isFinal: true }));
    const e6 = tracker.onVendorResult(res("fine", 600));
    expect(e1).toEqual([{ kind: "partial", seg: 1, text: "hello" }]);
    expect(e2).toEqual([{ kind: "partial", seg: 1, text: "hello there" }]);
    expect(e3).toEqual([{ kind: "partial", seg: 1, text: "hello there how" }]);
    expect(e4).toEqual([{ kind: "partial", seg: 1, text: "hello there how are" }]);
    expect(e5).toEqual([{ kind: "partial", seg: 1, text: "hello there how are you" }]);
    expect(e6).toEqual([{ kind: "partial", seg: 1, text: "hello there how are you fine" }]);
  });

  it("(b) a late result for segment 1 delivered after finalize 2 is assigned to segment 1", () => {
    const { tracker } = make();
    tracker.onAudio(BYTES_PER_100MS * 5); // 0..500 ms
    tracker.onFinalize(1);
    tracker.onAudio(BYTES_PER_100MS * 5); // 500..1000 ms
    tracker.onFinalize(2);
    const late = tracker.onVendorResult(res("belongs to one", 200, { isFinal: true }));
    expect(late).toEqual([{ kind: "partial", seg: 1, text: "belongs to one" }]);
    const f1 = tracker.onVendorResult(res("", 0, { isFinal: true, fromFinalize: true }));
    const f2 = tracker.onVendorResult(res("segment two", 600, { isFinal: true, fromFinalize: true }));
    expect(f1).toEqual([{ kind: "final", seg: 1, text: "belongs to one", audioMs: 500, complete: true }]);
    expect(f2).toEqual([{ kind: "final", seg: 2, text: "segment two", audioMs: 500, complete: true }]);
  });

  it("(c) a fromFinalize result yields one complete final with the segment audio ms", () => {
    const { tracker } = make();
    tracker.onAudio(BYTES_PER_100MS * 12);
    tracker.onVendorResult(res("good", 100, { isFinal: true }));
    tracker.onFinalize(1);
    expect(tracker.pendingFinalizes).toBe(1);
    const evs = tracker.onVendorResult(res("morning", 800, { isFinal: true, fromFinalize: true }));
    expect(evs).toEqual([{ kind: "final", seg: 1, text: "good morning", audioMs: 1200, complete: true }]);
    expect(tracker.pendingFinalizes).toBe(0);
  });

  it("(d) a finalize with no audio and no result expires into exactly one empty incomplete final", () => {
    const { tracker, advance, time } = make();
    tracker.onFinalize(1);
    expect(tracker.expire(time())).toEqual([]);
    advance(1499);
    expect(tracker.expire(time())).toEqual([]);
    advance(1);
    expect(tracker.expire(time())).toEqual([{ kind: "final", seg: 1, text: "", audioMs: 0, complete: false }]);
    advance(5000);
    expect(tracker.expire(time())).toEqual([]);
    expect(tracker.pendingFinalizes).toBe(0);
  });

  it("(e) a late fromFinalize result after expiry is dropped", () => {
    const { tracker, advance, time } = make();
    tracker.onAudio(BYTES_PER_100MS * 3);
    tracker.onFinalize(1);
    advance(1500);
    expect(finals(tracker.expire(time()))).toHaveLength(1);
    const late = tracker.onVendorResult(res("too late", 0, { isFinal: true, fromFinalize: true }));
    expect(late).toEqual([]);
    expect(tracker.expire(time() + 10_000)).toEqual([]);
  });

  it("(e2) an expired final keeps the text gathered so far", () => {
    const { tracker, advance, time } = make();
    tracker.onAudio(BYTES_PER_100MS * 3);
    tracker.onVendorResult(res("partial words", 0));
    tracker.onFinalize(1);
    advance(1500);
    expect(tracker.expire(time())).toEqual([
      { kind: "final", seg: 1, text: "partial words", audioMs: 300, complete: false },
    ]);
  });

  it("(e3) a late result of an expired segment does not complete the next finalize", () => {
    const { tracker, advance, time } = make();
    tracker.onAudio(BYTES_PER_100MS * 5); // seg 1: 0..500
    tracker.onFinalize(1);
    advance(1400);
    tracker.onAudio(BYTES_PER_100MS * 5); // seg 2: 500..1000
    tracker.onFinalize(2);
    advance(100); // finalize 1 is now 1500 ms old
    expect(finals(tracker.expire(time())).map((e) => e.seg)).toEqual([1]);
    const late = tracker.onVendorResult(res("late one", 100, { isFinal: true, fromFinalize: true }));
    expect(late).toEqual([]);
    expect(tracker.pendingFinalizes).toBe(1);
    const f2 = tracker.onVendorResult(res("two", 600, { isFinal: true, fromFinalize: true }));
    expect(f2).toEqual([{ kind: "final", seg: 2, text: "two", audioMs: 500, complete: true }]);
  });

  it("(f) fromFinalize results complete pending finalizes in FIFO order", () => {
    const { tracker } = make();
    tracker.onAudio(BYTES_PER_100MS * 2);
    tracker.onFinalize(1);
    tracker.onAudio(BYTES_PER_100MS * 3);
    tracker.onFinalize(2);
    expect(tracker.pendingFinalizes).toBe(2);
    const a = tracker.onVendorResult(res("first", 0, { isFinal: true, fromFinalize: true }));
    const b = tracker.onVendorResult(res("second", 200, { isFinal: true, fromFinalize: true }));
    expect(a).toEqual([{ kind: "final", seg: 1, text: "first", audioMs: 200, complete: true }]);
    expect(b).toEqual([{ kind: "final", seg: 2, text: "second", audioMs: 300, complete: true }]);
  });

  it("tracks forwarded ms as bytes / 32", () => {
    const { tracker } = make();
    tracker.onAudio(6400);
    tracker.onAudio(3200);
    expect(tracker.forwardedMs).toBe(300);
  });

  it("uses segBase for the first open segment and continues after each finalize", () => {
    const { tracker } = make(40);
    tracker.onAudio(BYTES_PER_100MS);
    expect(tracker.onVendorResult(res("a", 0))).toEqual([{ kind: "partial", seg: 40, text: "a" }]);
    tracker.onFinalize(40);
    tracker.onAudio(BYTES_PER_100MS);
    expect(tracker.onVendorResult(res("b", 100))).toEqual([{ kind: "partial", seg: 41, text: "b" }]);
  });

  describe("(g) seeded property test", () => {
    function rng(seed: number) {
      let a = seed >>> 0;
      return () => {
        a = (a + 0x6d2b79f5) >>> 0;
        let t = a;
        t = Math.imul(t ^ (t >>> 15), t | 1);
        t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
      };
    }

    it("holds the exactly-one-final invariants over 200 random runs", () => {
      for (let run = 0; run < 200; run++) {
        const rand = rng(run + 1);
        const int = (n: number) => Math.floor(rand() * n);
        let clock = 0;
        const segBase = int(5);
        const tracker = new SegmentTracker({ segBase, now: () => clock });
        const finalized: number[] = [];
        const finalSeen = new Map<number, number>();
        let nextSeg = segBase;
        const record = (evs: TrackerEvent[]) => {
          for (const e of evs) {
            if (e.kind !== "final") continue;
            expect(finalized, `run ${run}: final before finalize`).toContain(e.seg);
            finalSeen.set(e.seg, (finalSeen.get(e.seg) ?? 0) + 1);
            expect(finalSeen.get(e.seg), `run ${run}: duplicate final`).toBe(1);
          }
        };
        const ops = 20 + int(60);
        for (let i = 0; i < ops; i++) {
          const op = int(10);
          if (op < 3) tracker.onAudio(64 * (1 + int(200)));
          else if (op < 5) {
            tracker.onFinalize(nextSeg);
            finalized.push(nextSeg);
            nextSeg++;
          } else if (op < 9) {
            const start = int(Math.max(1, Math.ceil(tracker.forwardedMs) + 300));
            record(
              tracker.onVendorResult({
                text: rand() < 0.2 ? "" : `w${int(100)}`,
                startMs: start,
                endMs: start + int(500),
                isFinal: rand() < 0.5,
                fromFinalize: rand() < 0.35,
              }),
            );
          } else {
            clock += int(1200);
            record(tracker.expire(clock));
          }
        }
        for (let k = 0; k < 3; k++) {
          clock += 2000;
          record(tracker.expire(clock));
        }
        expect(finalSeen.size, `run ${run}`).toBe(finalized.length);
        expect(tracker.pendingFinalizes).toBe(0);
      }
    });
  });
});
