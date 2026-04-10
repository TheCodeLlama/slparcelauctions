import { describe, it, expect } from "vitest";
import { useAuth } from "./auth";

describe("useAuth (stub)", () => {
  it("returns an unauthenticated session", () => {
    expect(useAuth()).toEqual({ status: "unauthenticated", user: null });
  });
});
