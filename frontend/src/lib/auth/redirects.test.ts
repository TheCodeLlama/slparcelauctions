// frontend/src/lib/auth/redirects.test.ts
import { describe, it, expect } from "vitest";
import { getSafeRedirect } from "./redirects";

describe("getSafeRedirect", () => {
  it("returns /dashboard when next is null", () => {
    expect(getSafeRedirect(null)).toBe("/dashboard");
  });

  it("returns /dashboard when next is undefined", () => {
    expect(getSafeRedirect(undefined)).toBe("/dashboard");
  });

  it("returns /dashboard when next is empty string", () => {
    expect(getSafeRedirect("")).toBe("/dashboard");
  });

  it("returns the path when next is a relative URL", () => {
    expect(getSafeRedirect("/browse")).toBe("/browse");
    expect(getSafeRedirect("/auction/42")).toBe("/auction/42");
  });

  it("returns /dashboard when next does not start with /", () => {
    expect(getSafeRedirect("browse")).toBe("/dashboard");
    expect(getSafeRedirect("https://evil.example/phish")).toBe("/dashboard");
  });

  it("rejects protocol-relative URLs (open redirect attack)", () => {
    expect(getSafeRedirect("//evil.example/phish")).toBe("/dashboard");
  });

  it("rejects URLs containing newlines or control characters", () => {
    expect(getSafeRedirect("/browse\n")).toBe("/dashboard");
    expect(getSafeRedirect("/browse\r")).toBe("/dashboard");
    expect(getSafeRedirect("/browse\0")).toBe("/dashboard");
  });
});
