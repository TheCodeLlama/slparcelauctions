import { describe, it, expect, beforeEach } from "vitest";
import { getAccessToken, setAccessToken } from "./session";

describe("session token ref", () => {
  beforeEach(() => {
    setAccessToken(null);
  });

  it("getAccessToken returns null by default", () => {
    expect(getAccessToken()).toBeNull();
  });

  it("setAccessToken stores the value", () => {
    setAccessToken("token-abc");
    expect(getAccessToken()).toBe("token-abc");
  });

  it("setAccessToken(null) clears the stored value", () => {
    setAccessToken("token-abc");
    setAccessToken(null);
    expect(getAccessToken()).toBeNull();
  });
});
