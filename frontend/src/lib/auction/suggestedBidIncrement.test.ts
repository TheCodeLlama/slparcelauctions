import { describe, expect, it } from "vitest";
import { suggestedBidIncrement } from "./suggestedBidIncrement";

describe("suggestedBidIncrement", () => {
  // Mirrors backend BidIncrementSuggester tier boundaries exactly.
  // The suggestion helper uses startingBid (not currentBid) as input
  // since it operates at create time when there is no current bid.
  it.each([
    [0, 50],
    [999, 50],
    [1_000, 100],
    [9_999, 100],
    [10_000, 500],
    [99_999, 500],
    [100_000, 1_000],
  ])("suggestedBidIncrement(%i) === %i", (startingBid, expected) => {
    expect(suggestedBidIncrement(startingBid)).toBe(expected);
  });
});
