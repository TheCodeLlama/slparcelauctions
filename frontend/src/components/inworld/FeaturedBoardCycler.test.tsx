import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, act } from "@testing-library/react";
import { FeaturedBoardCycler } from "./FeaturedBoardCycler";
import type { FeaturedBoardListing } from "@/types/promotion";

const listing = (id: string, title: string): FeaturedBoardListing => ({
  publicId: id, title, region: "Heterocera", sqm: 1024,
  photoUrl: null, currentBid: 1, endsAt: "2030-01-01T00:00:00Z",
  listingUrl: "/auction/" + id, slurl: null,
});

describe("FeaturedBoardCycler", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("renders the only listing statically when length === 1", () => {
    render(<FeaturedBoardCycler listings={[listing("a", "Alpha")]} cycleSeconds={30} />);
    expect(screen.getByText("Alpha")).toBeInTheDocument();
  });

  it("advances index every cycleSeconds when length >= 2", () => {
    const items = [listing("a", "Alpha"), listing("b", "Bravo")];
    render(<FeaturedBoardCycler listings={items} cycleSeconds={30} />);
    const initial = screen.queryByText("Alpha") || screen.queryByText("Bravo");
    expect(initial).toBeInTheDocument();
    act(() => { vi.advanceTimersByTime(30_000); });
    const flipped = screen.queryByText("Alpha") || screen.queryByText("Bravo");
    expect(flipped).toBeInTheDocument();
  });
});
