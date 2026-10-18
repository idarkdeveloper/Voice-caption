import { describe, expect, it } from "vitest";
import { CLOSE, MAX_FRAME_BYTES, parseClientMessage, serialize, type ServerMessage } from "../src/protocol.js";

const start = {
  type: "start",
  lang: "en",
  sample_rate: 16000,
  encoding: "linear16",
  channels: 1,
  seg_base: 10,
  client: "android-1.0",
};
const p = (o: unknown, opts?: { started: boolean }) => parseClientMessage(JSON.stringify(o), opts);

describe("parseClientMessage", () => {
  it("accepts a valid start", () => {
    const r = p(start);
    expect(r).toEqual({ ok: true, msg: start });
  });

  it.each([
    ["bad_sample_rate", { ...start, sample_rate: 8000 }],
    ["bad_encoding", { ...start, encoding: "opus" }],
    ["bad_channels", { ...start, channels: 2 }],
    ["bad_seg_base", { ...start, seg_base: 1.5 }],
    ["bad_seg_base", { ...start, seg_base: "1" }],
    ["bad_lang", { ...start, lang: undefined }],
    ["bad_lang", { ...start, lang: "" }],
    ["bad_type", { ...start, type: "bogus" }],
    ["bad_type", { lang: "en" }],
  ])("rejects %s", (reason, msg) => {
    const r = p(msg);
    expect(r).toEqual({ ok: false, reason });
  });

  it("rejects invalid JSON and non objects", () => {
    expect(parseClientMessage("{nope")).toEqual({ ok: false, reason: "bad_json" });
    expect(parseClientMessage("[1]")).toEqual({ ok: false, reason: "bad_json" });
    expect(parseClientMessage("null")).toEqual({ ok: false, reason: "bad_json" });
  });

  it("rejects a second start", () => {
    expect(p(start, { started: true })).toEqual({ ok: false, reason: "duplicate_start" });
  });

  it("finalize requires an integer seg", () => {
    expect(p({ type: "finalize", seg: 3 })).toEqual({ ok: true, msg: { type: "finalize", seg: 3 } });
    expect(p({ type: "finalize", seg: 3.2 })).toEqual({ ok: false, reason: "bad_seg" });
    expect(p({ type: "finalize" })).toEqual({ ok: false, reason: "bad_seg" });
    expect(p({ type: "finalize", seg: "3" })).toEqual({ ok: false, reason: "bad_seg" });
  });

  it("accepts stop", () => {
    expect(p({ type: "stop" })).toEqual({ ok: true, msg: { type: "stop" } });
  });

  it("never echoes client content in reasons", () => {
    const r = p({ ...start, type: "SECRET-TYPE-VALUE" });
    expect(JSON.stringify(r)).not.toContain("SECRET");
  });
});

describe("serialize and constants", () => {
  it("round-trips final with complete:false and empty text", () => {
    const m: ServerMessage = { type: "final", seg: 4, text: "", audio_ms: 0, complete: false };
    expect(JSON.parse(serialize(m))).toEqual(m);
  });

  it("exposes close codes and frame limit", () => {
    expect(CLOSE).toEqual({ NORMAL: 1000, BAD_JWT: 4001, LANG_UNSUPPORTED: 4004, PROTOCOL: 4400, VENDOR: 4503 });
    expect(MAX_FRAME_BYTES).toBe(65536);
  });
});
