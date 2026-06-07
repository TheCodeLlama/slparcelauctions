import { describe, it, expect, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useFeatureListing } from "./useFeatureListing";
import * as promotions from "@/lib/api/promotions";

describe("useFeatureListing", () => {
  it("calls purchaseFeatured and exposes pending + result", async () => {
    const spy = vi.spyOn(promotions, "purchaseFeatured").mockResolvedValue({
      slotPublicId: "s1", boardIndex: 1, position: 0,
      priceLindens: 500, newBalanceLindens: 9500,
    });
    const { result } = renderHook(() => useFeatureListing());
    expect(result.current.pending).toBe(false);
    await act(async () => {
      const res = await result.current.purchase("a1");
      expect(res.newBalanceLindens).toBe(9500);
    });
    expect(spy).toHaveBeenCalledWith("a1");
  });
});
