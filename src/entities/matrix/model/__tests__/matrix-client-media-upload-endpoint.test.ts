import { describe, it, expect } from "vitest";
import { MatrixClientService } from "../matrix-client";

/**
 * The endpoint the native Tor upload posts to. It read the token from
 * `client.credentials.accessToken`, which the SDK never sets (`credentials` is
 * `{ userId }`), so every upload of 5 MB and more under Tor threw before it reached
 * the plugin and the message stayed «sending» (Samsung, 2026-09-19).
 */
describe("MatrixClientService.getMediaUploadEndpoint", () => {
  const withClient = (client: unknown) => {
    const service = new MatrixClientService("test.invalid");
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (service as any).client = client;
    return service;
  };

  it("takes the token from the SDK client the way the SDK keeps it", () => {
    const service = withClient({ credentials: { userId: "@me:test.invalid" }, getAccessToken: () => "tok" });
    expect(service.getMediaUploadEndpoint()).toEqual({
      url: "https://test.invalid/_matrix/media/v3/upload",
      authorization: "Bearer tok",
    });
  });

  it("carries the file name as a query parameter", () => {
    const service = withClient({ getAccessToken: () => "tok" });
    expect(service.getMediaUploadEndpoint("отчёт 1.pdf").url).toBe(
      "https://test.invalid/_matrix/media/v3/upload?filename=%D0%BE%D1%82%D1%87%D1%91%D1%82+1.pdf",
    );
  });

  it("throws without a token rather than posting unauthenticated", () => {
    expect(() => withClient({ getAccessToken: () => null }).getMediaUploadEndpoint()).toThrow("No access token");
  });
});
