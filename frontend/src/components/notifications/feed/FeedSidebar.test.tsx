import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { FeedSidebar } from "./FeedSidebar";

// ---------------------------------------------------------------------------
// Control breakdown data returned by the hook
// ---------------------------------------------------------------------------
const breakdownData = vi.hoisted(() => ({
  count: 0,
  byGroup: {} as Record<string, number>,
}));

vi.mock("@/hooks/notifications/useUnreadCount", () => ({
  useUnreadCountBreakdown: () => ({
    data: { count: breakdownData.count, byGroup: breakdownData.byGroup },
    isPending: false,
  }),
}));

describe("FeedSidebar", () => {
  beforeEach(() => {
    breakdownData.count = 0;
    breakdownData.byGroup = {};
  });

  it("group counts match byGroup response from useUnreadCountBreakdown", () => {
    breakdownData.count = 5;
    breakdownData.byGroup = { bidding: 3, escrow: 2 };

    renderWithProviders(<FeedSidebar value="all" onChange={vi.fn()} />);

    // Bidding badge shows 3
    const biddingBtn = screen.getByRole("button", { name: /Bidding/i });
    expect(biddingBtn).toHaveTextContent("3");

    // Escrow badge shows 2
    const escrowBtn = screen.getByRole("button", { name: /Escrow/i });
    expect(escrowBtn).toHaveTextContent("2");
  });

  it("zero counts do not render a count badge", () => {
    breakdownData.count = 0;
    breakdownData.byGroup = { bidding: 0 };

    renderWithProviders(<FeedSidebar value="all" onChange={vi.fn()} />);

    const biddingBtn = screen.getByRole("button", { name: /Bidding/i });
    // Only the label span is present — no second span for the count
    expect(biddingBtn.querySelectorAll("span")).toHaveLength(1);
  });

  it("active item has the brand border class", () => {
    renderWithProviders(<FeedSidebar value="escrow" onChange={vi.fn()} />);

    const escrowBtn = screen.getByRole("button", { name: /Escrow/i });
    expect(escrowBtn).toHaveClass("border-brand");
  });
});
