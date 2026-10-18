import { readFileSync } from "node:fs";

export interface Wav {
  sampleRate: number;
  samples: Int16Array;
}

/** Parses a RIFF/WAVE buffer; only 16-bit PCM mono is accepted. */
export function parseWav(buf: Buffer): Wav {
  if (buf.length < 12 || buf.toString("ascii", 0, 4) !== "RIFF" || buf.toString("ascii", 8, 12) !== "WAVE") {
    throw new Error("not a RIFF/WAVE file");
  }
  let fmt: { format: number; channels: number; sampleRate: number; bits: number } | null = null;
  let pos = 12;
  while (pos + 8 <= buf.length) {
    const id = buf.toString("ascii", pos, pos + 4);
    const size = buf.readUInt32LE(pos + 4);
    const body = pos + 8;
    if (id === "fmt ") {
      if (size < 16 || body + 16 > buf.length) throw new Error("truncated fmt chunk");
      fmt = {
        format: buf.readUInt16LE(body),
        channels: buf.readUInt16LE(body + 2),
        sampleRate: buf.readUInt32LE(body + 4),
        bits: buf.readUInt16LE(body + 14),
      };
    } else if (id === "data") {
      if (!fmt) throw new Error("data chunk before fmt chunk");
      if (fmt.format !== 1) throw new Error(`unsupported WAV format ${fmt.format} (PCM only)`);
      if (fmt.bits !== 16) throw new Error(`unsupported bit depth ${fmt.bits} (16-bit only)`);
      if (fmt.channels !== 1) throw new Error(`unsupported channel count ${fmt.channels} (mono only)`);
      const end = Math.min(buf.length, body + size); // tolerate a data size that overshoots the file
      const n = Math.floor((end - body) / 2);
      const samples = new Int16Array(n);
      for (let i = 0; i < n; i++) samples[i] = buf.readInt16LE(body + i * 2);
      return { sampleRate: fmt.sampleRate, samples };
    }
    pos = body + size + (size & 1);
  }
  throw new Error(fmt ? "no data chunk" : "no fmt chunk");
}

export function readWav(path: string): Wav {
  return parseWav(readFileSync(path));
}

/** Builds a 16-bit mono PCM WAV (used by tests and tooling). */
export function encodeWav(samples: Int16Array, sampleRate: number): Buffer {
  const data = Buffer.alloc(samples.length * 2);
  for (let i = 0; i < samples.length; i++) data.writeInt16LE(samples[i]!, i * 2);
  const head = Buffer.alloc(44);
  head.write("RIFF", 0, "ascii");
  head.writeUInt32LE(36 + data.length, 4);
  head.write("WAVEfmt ", 8, "ascii");
  head.writeUInt32LE(16, 16);
  head.writeUInt16LE(1, 20);
  head.writeUInt16LE(1, 22);
  head.writeUInt32LE(sampleRate, 24);
  head.writeUInt32LE(sampleRate * 2, 28);
  head.writeUInt16LE(2, 32);
  head.writeUInt16LE(16, 34);
  head.write("data", 36, "ascii");
  head.writeUInt32LE(data.length, 40);
  return Buffer.concat([head, data]);
}
