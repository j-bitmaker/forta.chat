import { describe, it, expect } from "vitest";

// local-ai/adapters/node-testing imports node-llama-cpp at module top level,
// but that package is only local-ai's devDependency and is never installed
// here — so every suite importing the node-testing adapters failed to load.
describe("node-llama-cpp test stub", () => {
  it("lets local-ai's node-testing adapters load without node-llama-cpp installed", async () => {
    const adapters = await import("local-ai/adapters/node-testing");

    expect(adapters.FakeLlmRuntimeAdapter).toBeTypeOf("function");
  });

  it("fails loudly when a test reaches for the real llama runtime", async () => {
    const { NodeLlamaCppAdapter } = await import("local-ai/adapters/node-testing");
    const adapter = new NodeLlamaCppAdapter();

    await expect(
      adapter.loadModel({ modelPath: "/nonexistent.gguf", contextLength: 512 }),
    ).rejects.toThrow(/FakeLlmRuntimeAdapter/);
  });
});
