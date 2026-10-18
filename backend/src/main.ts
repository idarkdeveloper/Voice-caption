import { createDeepgramVendor } from "./asr/deepgram.js";
import { ConfigError, loadConfig } from "./config.js";
import { createLogger } from "./log.js";
import { buildServer } from "./server.js";

async function main(): Promise<void> {
  const cfg = loadConfig(process.env);
  const log = createLogger((line) => process.stdout.write(`${line}\n`), {
    strict: false,
    redact: [cfg.deepgramApiKey, cfg.jwtSecret],
  });
  const server = await buildServer(cfg, {
    vendor: createDeepgramVendor({ apiKey: cfg.deepgramApiKey, hosts: cfg.deepgramHosts }),
    log,
  });
  await server.listen();
  log.info("listening", { region: cfg.region });

  const stop = () => {
    void server.close().finally(() => process.exit(0));
  };
  process.on("SIGTERM", stop);
  process.on("SIGINT", stop);
}

main().catch((e: unknown) => {
  // ConfigError messages list variable names only, never values.
  process.stderr.write(`${e instanceof ConfigError ? e.message : "fatal startup error"}\n`);
  process.exit(1);
});
