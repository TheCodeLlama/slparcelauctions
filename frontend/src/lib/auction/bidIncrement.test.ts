import { describe, expect, it } from "vitest";
import { minIncrement, minRequiredBid } from "./bidIncrement";

describe("minIncrement", () => {
  // Mirrors backend BidIncrementTableTest.java tier boundaries exactly —
  // the two tables MUST stay in lockstep. Adding a row here without
  // updating the backend (or vice versa) is a bug the server-side lock
  // would surface as a BID_TOO_LOW after the UI said the amount was
  // fine.
  it.each([
    [0, 50],
    [49, 50],
    [50, 50],
    [999, 50],
    [1_000, 100],
    [9_999, 100],
    [10_000, 500],
    [99_999, 500],
    [100_000, 1_000],
    [500_000, 1_000],
  ])("minIncrement(%i) === %i", (currentBid, expected) => {
    expect(minIncrement(currentBid)).toBe(expected);
  });
});

describe("minRequiredBid", () => {
  it("falls back to startingBid when there are no bids yet", () => {
    expect(minRequiredBid(null, 500)).toBe(500);
    expect(minRequiredBid(0, 500)).toBe(500);
  });

  it("adds the tiered increment to the current bid when there is one", () => {
    expect(minRequiredBid(500, 500)).toBe(550);
    expect(minRequiredBid(1_000, 500)).toBe(1_100);
    expect(minRequiredBid(10_000, 500)).toBe(10_500);
    expect(minRequiredBid(100_000, 500)).toBe(101_000);
  });

  it("tolerates BigDecimal-string currentHighBid from the backend", () => {
    expect(minRequiredBid("1500", 500)).toBe(1_600);
  });

  it("falls back to startingBid for a non-finite coerced value", () => {
    expect(minRequiredBid("not-a-number", 500)).toBe(500);
  });
});
