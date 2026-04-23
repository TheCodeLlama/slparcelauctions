import { describe, expect, it } from "vitest";
import {
  formatAbsoluteTime,
  formatRelativeTime,
} from "./relativeTime";

const BASE = new Date("2026-04-20T12:00:00Z").getTime();

describe("formatRelativeTime", () => {
  it("returns 'just now' for < 10 seconds", () => {
    expect(formatRelativeTime(new Date(BASE - 3_000), BASE)).toBe("just now");
    expect(formatRelativeTime(new Date(BASE), BASE)).toBe("just now");
  });

  it("returns seconds-ago for <= 59 seconds", () => {
    expect(formatRelativeTime(new Date(BASE - 30_000), BASE)).toBe("30s ago");
  });

  it("returns minutes-ago for < 1 hour", () => {
    expect(formatRelativeTime(new Date(BASE - 2 * 60_000), BASE)).toBe(
      "2m ago",
    );
    expect(formatRelativeTime(new Date(BASE - 59 * 60_000), BASE)).toBe(
      "59m ago",
    );
  });

  it("returns hours-ago for < 1 day", () => {
    expect(formatRelativeTime(new Date(BASE - 3 * 3_600_000), BASE)).toBe(
      "3h ago",
    );
  });

  it("returns days-ago for < 1 week", () => {
    expect(
      formatRelativeTime(new Date(BASE - 3 * 24 * 3_600_000), BASE),
    ).toBe("3d ago");
  });

  it("returns weeks-ago for < 5 weeks", () => {
    expect(
      formatRelativeTime(new Date(BASE - 2 * 7 * 24 * 3_600_000), BASE),
    ).toBe("2w ago");
  });

  it("falls back to an absolute date for older timestamps", () => {
    const result = formatRelativeTime(
      new Date(BASE - 60 * 24 * 3_600_000),
      BASE,
    );
    // toLocaleDateString output varies by locale, so just assert it's
    // no longer in the "Nw ago" / "Nd ago" shape.
    expect(result).not.toMatch(/ago$/);
    expect(result.length).toBeGreaterThan(0);
  });

  it("accepts an ISO-8601 string", () => {
    expect(
      formatRelativeTime("2026-04-20T11:58:00Z", BASE),
    ).toBe("2m ago");
  });

  it("returns empty string for an invalid timestamp", () => {
    expect(formatRelativeTime("not-a-date", BASE)).toBe("");
  });
});

describe("formatAbsoluteTime", () => {
  it("returns a non-empty locale string for a valid date", () => {
    expect(formatAbsoluteTime("2026-04-20T12:00:00Z").length).toBeGreaterThan(
      0,
    );
  });

  it("returns empty string for an invalid timestamp", () => {
    expect(formatAbsoluteTime("not-a-date")).toBe("");
  });
});
