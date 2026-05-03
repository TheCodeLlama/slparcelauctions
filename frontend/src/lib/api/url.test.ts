import { describe, it, expect, vi, afterEach } from "vitest";
import { apiUrl } from "./url";

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("apiUrl", () => {
  it("prefixes a relative path with NEXT_PUBLIC_API_URL", () => {
    vi.stubEnv("NEXT_PUBLIC_API_URL", "https://slpa.app");
    expect(apiUrl("/api/v1/photos/3")).toBe("https://slpa.app/api/v1/photos/3");
  });

  it("passes absolute http(s) URLs through unchanged", () => {
    expect(apiUrl("https://example.com/foo.jpg")).toBe(
      "https://example.com/foo.jpg",
    );
    expect(apiUrl("http://example.com/foo.jpg")).toBe(
      "http://example.com/foo.jpg",
    );
  });

  it("returns null on null/undefined input", () => {
    expect(apiUrl(null)).toBeNull();
    expect(apiUrl(undefined)).toBeNull();
  });
});
