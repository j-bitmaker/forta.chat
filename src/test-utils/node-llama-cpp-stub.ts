// Stands in for node-llama-cpp in vitest (see `test.alias` in vite.config.ts).
// Only NodeLlamaCppAdapter reaches it; anything that does fails loudly.
function unavailable(): never {
  throw new Error(
    "node-llama-cpp is not installed in forta.chat; use FakeLlmRuntimeAdapter in tests",
  );
}

export async function getLlama(): Promise<never> {
  return unavailable();
}

export class LlamaChat {
  constructor() {
    unavailable();
  }
}

export class LlamaCompletion {
  constructor() {
    unavailable();
  }
}
