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

    // Pin the wall-clock to a known epoch so we know which listing is "current"
    // before the timer fires. Math.floor(epoch / 30) is the index source.
    const fixedNowMs = 0;          // floor(0 / 30) % 2 = 0 -> Alpha
    vi.setSystemTime(new Date(fixedNowMs));

    render(<FeaturedBoardCycler listings={items} cycleSeconds={30} />);
    expect(screen.getByText("Alpha")).toBeInTheDocument();
    expect(screen.queryByText("Bravo")).toBeNull();

    // Advance the fake clock by 30s; advanceTimersByTime also moves Date.now()
    // forward, so the interval callback reads epoch second 30.
    // floor(30 / 30) % 2 = 1 -> Bravo.
    act(() => { vi.advanceTimersByTime(30_000); });

    expect(screen.getByText("Bravo")).toBeInTheDocument();
    expect(screen.queryByText("Alpha")).toBeNull();
  });
});
