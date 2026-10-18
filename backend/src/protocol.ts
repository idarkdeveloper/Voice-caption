/** Client protocol (speech only): message types, hand-written validation, limits and close codes. */

export const CLOSE = {
  NORMAL: 1000,
  BAD_JWT: 4001,
  LANG_UNSUPPORTED: 4004,
  PROTOCOL: 4400,
  VENDOR: 4503,
} as const;

export const MAX_FRAME_BYTES = 65536;

export interface StartMsg {
  type: "start";
  lang: string;
  sample_rate: 16000;
  encoding: "linear16";
  channels: 1;
  seg_base: number;
  client?: string;
}
export interface FinalizeMsg {
  type: "finalize";
  seg: number;
}
export interface StopMsg {
  type: "stop";
}
export type ClientMessage = StartMsg | FinalizeMsg | StopMsg;

export type ParseResult = { ok: true; msg: ClientMessage } | { ok: false; reason: string };

export interface ParseOptions {
  /** True once a `start` has been accepted on this connection; a second one is rejected. */
  started?: boolean;
}

const fail = (reason: string): ParseResult => ({ ok: false, reason });

export function parseClientMessage(raw: string, opts: ParseOptions = {}): ParseResult {
  let v: unknown;
  try {
    v = JSON.parse(raw);
  } catch {
    return fail("bad_json");
  }
  if (typeof v !== "object" || v === null || Array.isArray(v)) return fail("bad_json");
  const o = v as Record<string, unknown>;
  switch (o.type) {
    case "start": {
      if (opts.started) return fail("duplicate_start");
      if (typeof o.lang !== "string" || !/^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?$/.test(o.lang)) return fail("bad_lang");
      if (o.sample_rate !== 16000) return fail("bad_sample_rate");
      if (o.encoding !== "linear16") return fail("bad_encoding");
      if (o.channels !== 1) return fail("bad_channels");
      if (typeof o.seg_base !== "number" || !Number.isSafeInteger(o.seg_base) || o.seg_base < 0) {
        return fail("bad_seg_base");
      }
      if (o.client !== undefined && (typeof o.client !== "string" || o.client.length > 64)) return fail("bad_client");
      const msg: StartMsg = {
        type: "start",
        lang: o.lang,
        sample_rate: 16000,
        encoding: "linear16",
        channels: 1,
        seg_base: o.seg_base,
      };
      if (o.client !== undefined) msg.client = o.client;
      return { ok: true, msg };
    }
    case "finalize":
      if (typeof o.seg !== "number" || !Number.isSafeInteger(o.seg)) return fail("bad_seg");
      return { ok: true, msg: { type: "finalize", seg: o.seg } };
    case "stop":
      return { ok: true, msg: { type: "stop" } };
    default:
      return fail("bad_type");
  }
}

export type ServerMessage =
  | {
      type: "ready";
      stream_id: string;
      region: string;
      asr_region: "us" | "eu";
      model: string;
      lang: string;
      remaining_ms: number;
      flush_interval_ms: number;
      max_stream_ms: number;
      config_version: number;
    }
  | { type: "partial"; seg: number; text: string }
  | { type: "final"; seg: number; text: string; audio_ms: number; complete: boolean };

export function serialize(msg: ServerMessage): string {
  return JSON.stringify(msg);
}
