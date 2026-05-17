import { describe, expect, it } from "vitest";
import { computeRefund } from "./refundCalculation";

describe("computeRefund", () => {
  it("returns NONE for DRAFT (no fee paid yet)", () => {
    expect(computeRefund("DRAFT", null)).toMatchObject({
      kind: "NONE",
      amountLindens: null,
    });
  });

  it("returns FULL for DRAFT_PAID with the persisted listingFeeAmt", () => {
    const r = computeRefund("DRAFT_PAID", 100);
    expect(r).toMatchObject({ kind: "FULL", amountLindens: 100 });
    expect(r.copy).toMatch(/your SLParcels wallet/);
    expect(r.copy).toMatch(/instantly/);
  });

  it("uses the group-wallet phrasing when isGroupListing is true", () => {
    const r = computeRefund("DRAFT_PAID", 100, true);
    expect(r.copy).toMatch(/the group's SLParcels wallet/);
    expect(r.copy).not.toMatch(/your SLParcels wallet/);
  });

  it("returns FULL for VERIFICATION_PENDING", () => {
    expect(computeRefund("VERIFICATION_PENDING", 100)).toMatchObject({
      kind: "FULL",
      amountLindens: 100,
    });
  });

  it("returns FULL for VERIFICATION_FAILED", () => {
    expect(computeRefund("VERIFICATION_FAILED", 100)).toMatchObject({
      kind: "FULL",
    });
  });

  it("falls back to 0 when listingFeeAmt is null in a FULL case", () => {
    // Guards against a DRAFT_PAID row with an unexpectedly-null amount —
    // should not crash the UI; copy shows L$0 (the modal caller can
    // override with a friendlier fallback).
    expect(computeRefund("DRAFT_PAID", null)).toMatchObject({
      kind: "FULL",
      amountLindens: 0,
    });
  });

  it("returns NONE for ACTIVE (seller forfeits fee on mid-auction cancel)", () => {
    expect(computeRefund("ACTIVE", 100)).toMatchObject({ kind: "NONE" });
  });

  it("returns NONE for terminal statuses with cannot-be-cancelled copy", () => {
    expect(computeRefund("COMPLETED", 100).copy).toMatch(/cannot be cancelled/i);
    expect(computeRefund("EXPIRED", 100).copy).toMatch(/cannot be cancelled/i);
    expect(computeRefund("FROZEN", 100).copy).toMatch(/cannot be cancelled/i);
    expect(computeRefund("CANCELLED", 100).copy).toMatch(/cannot be cancelled/i);
    expect(computeRefund("SUSPENDED", 100).copy).toMatch(/cannot be cancelled/i);
  });
});
