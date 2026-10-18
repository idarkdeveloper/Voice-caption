/**
 * Real-time audio token bucket. Tokens are milliseconds of audio. The bucket refills at
 * `ratePerS` audio seconds per wall-clock second (1.5 = 1.5x real time) up to `burstS` seconds of audio.
 */
export class AudioTokenBucket {
  private tokens: number;
  private last: number;
  private readonly capacity: number;
  private readonly refillPerMs: number;
  private readonly now: () => number;

  constructor(opts: { ratePerS: number; burstS: number; now: () => number }) {
    this.now = opts.now;
    this.capacity = opts.burstS * 1000;
    this.refillPerMs = opts.ratePerS;
    this.tokens = this.capacity;
    this.last = opts.now();
  }

  /** Takes `audioMs` of audio; false (and nothing taken) when the sender is faster than allowed. */
  take(audioMs: number): boolean {
    const t = this.now();
    if (t > this.last) {
      this.tokens = Math.min(this.capacity, this.tokens + (t - this.last) * this.refillPerMs);
    }
    this.last = Math.max(this.last, t);
    if (audioMs > this.tokens) return false;
    this.tokens -= audioMs;
    return true;
  }
}
