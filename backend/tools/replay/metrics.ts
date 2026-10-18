export interface SegmentRecord {
  label: string;
  clip: string;
  seg: number;
  ttfp_ms: number | null;
  final_after_finalize_ms: number;
  complete: boolean;
  audio_ms: number;
}

export interface StreamRecord {
  label: string;
  type: "stream";
  open_to_ready_ms: number;
  audio_ms_sent: number;
}

export type ResultRecord = SegmentRecord | StreamRecord;

export const isStreamRecord = (r: ResultRecord): r is StreamRecord => "type" in r && r.type === "stream";

/** Nearest-rank percentile, p in (0, 100]. */
export function percentile(values: number[], p: number): number {
  if (values.length === 0) throw new Error("percentile of empty input");
  if (!(p > 0 && p <= 100)) throw new Error("p must be in (0, 100]");
  const sorted = [...values].sort((a, b) => a - b);
  const rank = Math.max(1, Math.ceil((p / 100) * sorted.length));
  return sorted[rank - 1]!;
}

export interface Pct {
  p50: number;
  p95: number;
}

export interface Summary {
  label: string;
  segments: number;
  streams: number;
  open_to_ready_ms: Pct | null;
  ttfp_ms: Pct | null;
  final_after_finalize_ms: Pct | null;
  complete_false_share: number;
  audio_ms_sent_total: number;
}

const pct = (v: number[]): Pct | null => (v.length ? { p50: percentile(v, 50), p95: percentile(v, 95) } : null);

export function summarize(records: ResultRecord[]): Summary {
  const segs = records.filter((r): r is SegmentRecord => !isStreamRecord(r));
  const streams = records.filter(isStreamRecord);
  const labels = new Set(records.map((r) => r.label));
  return {
    label: labels.size === 1 ? [...labels][0]! : labels.size === 0 ? "" : "mixed",
    segments: segs.length,
    streams: streams.length,
    open_to_ready_ms: pct(streams.map((s) => s.open_to_ready_ms)),
    ttfp_ms: pct(segs.flatMap((s) => (s.ttfp_ms === null ? [] : [s.ttfp_ms]))),
    final_after_finalize_ms: pct(segs.map((s) => s.final_after_finalize_ms)),
    complete_false_share: segs.length ? segs.filter((s) => !s.complete).length / segs.length : 0,
    audio_ms_sent_total: streams.reduce((a, s) => a + s.audio_ms_sent, 0),
  };
}

/** Parses JSON Lines result text into records (blank lines ignored). */
export function parseResults(text: string): ResultRecord[] {
  return text
    .split("\n")
    .filter((l) => l.trim())
    .map((l) => JSON.parse(l) as ResultRecord);
}
