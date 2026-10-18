import type { VendorResult } from "../asr/vendor.js";

export type TrackerEvent =
  | { kind: "partial"; seg: number; text: string }
  | { kind: "final"; seg: number; text: string; audioMs: number; complete: boolean };

/** A finalize that gets no vendor answer within this time is answered with an incomplete final. */
export const FINALIZE_TIMEOUT_MS = 1500;

/** Closed segments kept (for late-result routing) after they are done. */
const KEEP_DONE = 16;

interface Segment {
  seg: number;
  startMs: number;
  /** null while the segment is open (no finalize yet). */
  endMs: number | null;
  finals: string[];
  interim: string;
  state: "open" | "pending" | "done";
  finalizeAt: number;
}

const joined = (s: Segment, withInterim: boolean): string =>
  (withInterim && s.interim ? [...s.finals, s.interim] : s.finals).join(" ").trim();

/**
 * Pure bookkeeping that assigns vendor results to client segments and guarantees exactly one `final`
 * per finalize. Offsets are forwarded audio ms (bytes / 32); vendor `startMs` must be on the same
 * timeline (the session adds a base when it reopens a vendor stream).
 */
export class SegmentTracker {
  private totalBytes = 0;
  private readonly now: () => number;
  private readonly segments: Segment[];
  private readonly pending: Segment[] = [];

  constructor(opts: { segBase: number; now?: () => number }) {
    this.now = opts.now ?? Date.now;
    this.segments = [this.openSegment(opts.segBase, 0)];
  }

  private openSegment(seg: number, startMs: number): Segment {
    return { seg, startMs, endMs: null, finals: [], interim: "", state: "open", finalizeAt: 0 };
  }

  get forwardedMs(): number {
    return this.totalBytes / 32;
  }

  get pendingFinalizes(): number {
    return this.pending.length;
  }

  onAudio(bytes: number): void {
    this.totalBytes += bytes;
  }

  onFinalize(seg: number): void {
    const open = this.segments[this.segments.length - 1]!;
    open.seg = seg;
    open.endMs = this.forwardedMs;
    open.state = "pending";
    open.finalizeAt = this.now();
    this.pending.push(open);
    this.segments.push(this.openSegment(seg + 1, this.forwardedMs));
  }

  /** The segment whose range contains `startMs`, or null when it predates the retained history. */
  private find(startMs: number): Segment | null {
    for (let i = this.segments.length - 1; i >= 0; i--) {
      const s = this.segments[i]!;
      if (startMs >= s.startMs) return s;
    }
    return null;
  }

  private complete(s: Segment, complete: boolean): TrackerEvent {
    s.state = "done";
    const ev: TrackerEvent = {
      kind: "final",
      seg: s.seg,
      text: joined(s, !complete),
      audioMs: Math.round((s.endMs ?? this.forwardedMs) - s.startMs),
      complete,
    };
    this.prune();
    return ev;
  }

  private prune(): void {
    while (this.segments.length > KEEP_DONE + 1 && this.segments[0]!.state === "done") this.segments.shift();
  }

  onVendorResult(r: VendorResult): TrackerEvent[] {
    const text = r.text.trim();
    if (r.fromFinalize) {
      const target = this.pending[0];
      if (!target) return []; // late answer to an already expired finalize
      if (text) {
        // A non-empty answer that belongs to an already finished segment must not complete a later one.
        const home = this.find(r.startMs);
        if (home && home.state === "done") return [];
        target.finals.push(text);
      }
      target.interim = "";
      this.pending.shift();
      return [this.complete(target, true)];
    }
    const seg = this.find(r.startMs);
    if (!seg || seg.state === "done") return [];
    if (r.isFinal) {
      if (text) seg.finals.push(text);
      seg.interim = "";
    } else {
      seg.interim = text;
    }
    const full = joined(seg, true);
    return full ? [{ kind: "partial", seg: seg.seg, text: full }] : [];
  }

  expire(nowMs: number): TrackerEvent[] {
    const out: TrackerEvent[] = [];
    while (this.pending.length > 0 && this.pending[0]!.finalizeAt + FINALIZE_TIMEOUT_MS <= nowMs) {
      out.push(this.complete(this.pending.shift()!, false));
    }
    return out;
  }
}
