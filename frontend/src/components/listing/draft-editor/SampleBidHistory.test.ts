import { describe, expect, it } from "vitest";
import {
  SAMPLE_BIDS,
  sampleCurrentBid,
  sampleBidderCount,
} from "./SampleBidHistory";

describe("SampleBidHistory", () => {
  it("exports a frozen array of 4 entries", () => {
    expect(SAMPLE_BIDS.length).toBe(4);
    expect(Object.isFrozen(SAMPLE_BIDS)).toBe(true);
  });

  it("amounts are monotonically increasing in placement order", () => {
    const ordered = [...SAMPLE_BIDS].sort(
      (a, b) =>
        new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime(),
    );
    for (let i = 1; i < ordered.length; i++) {
      expect(ordered[i].amount).toBeGreaterThan(ordered[i - 1].amount);
    }
  });

  it("sampleCurrentBid is the max amount", () => {
    const max = Math.max(...SAMPLE_BIDS.map((b) => b.amount));
    expect(sampleCurrentBid()).toBe(max);
  });

  it("sampleBidderCount is the unique bidder publicId count", () => {
    const unique = new Set(SAMPLE_BIDS.map((b) => b.userPublicId)).size;
    expect(sampleBidderCount()).toBe(unique);
  });
});
