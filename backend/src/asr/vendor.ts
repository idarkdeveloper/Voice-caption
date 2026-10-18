/** Vendor abstraction. The only vendor-specific code lives behind these interfaces. */

export type VendorErrorKind = "connect" | "auth" | "rate_limited" | "protocol" | "timeout" | "dropped";

/** `message` is a short fixed string. It never contains the API key, URLs, audio or transcript text. */
export interface VendorError {
  kind: VendorErrorKind;
  message: string;
}

export interface VendorResult {
  text: string;
  startMs: number;
  endMs: number;
  isFinal: boolean;
  fromFinalize: boolean;
}

export interface VendorStream {
  /** Vendor request id when the vendor reported one; safe to log as `vendor_request_id`. */
  readonly requestId?: string;
  sendAudio(pcm: Buffer): void;
  finalize(): void;
  keepAlive(): void;
  close(): Promise<void>;
  on(ev: "result", f: (r: VendorResult) => void): void;
  on(ev: "error" | "closed", f: (e?: VendorError) => void): void;
}

export interface AsrVendor {
  /**
   * Opens a vendor stream. Vendor-side failures (auth, connect, ...) never reject: the returned stream
   * emits `error` then `closed` on a later macrotask, so callers must attach listeners synchronously
   * after the promise resolves.
   */
  open(o: { lang: string; model: string; asrRegion: "us" | "eu"; traceId: string }): Promise<VendorStream>;
}
