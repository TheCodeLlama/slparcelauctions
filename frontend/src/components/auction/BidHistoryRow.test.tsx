import { describe, it, expect } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { BidHistoryRow } from "./BidHistoryRow";
import type { BidHistoryEntry } from "@/types/auction";

function entry(overrides: Partial<BidHistoryEntry> = {}): BidHistoryEntry {
  return {
    bidPublicId: "00000000-0000-0000-0000-000000000001",
    userPublicId: "00000000-0000-0000-0000-00000000002a",
    bidderDisplayName: "Alice",
    amount: 1500,
    bidType: "MANUAL",
    snipeExtensionMinutes: null,
    newEndsAt: null,
    createdAt: new Date(Date.now() - 2 * 60_000).toISOString(),
    ...overrides,
  };
}

describe("BidHistoryRow", () => {
  it("renders display name, amount, and profile link", () => {
    renderWithProviders(
      <ul>
        <BidHistoryRow entry={entry()} />
      </ul>,
    );
    const row = screen.getByTestId("bid-history-row");
    expect(row).toHaveAttribute(
      "data-bid-id",
      "00000000-0000-0000-0000-000000000001",
    );
    expect(screen.getByTestId("bid-history-row-name")).toHaveTextContent(
      "Alice",
    );
    expect(screen.getByTestId("bid-history-row-amount")).toHaveTextContent(
      "L$1,500",
    );
    // Name link targets the bidder's public profile.
    const links = screen.getAllByRole("link");
    const profileLinks = links.filter((l) =>
      (l.getAttribute("href") ?? "").startsWith(
        "/users/00000000-0000-0000-0000-00000000002a",
      ),
    );
    expect(profileLinks.length).toBeGreaterThan(0);
  });

  it("omits the type chip for MANUAL bids", () => {
    renderWithProviders(
      <ul>
        <BidHistoryRow entry={entry({ bidType: "MANUAL" })} />
      </ul>,
    );
    expect(
      screen.queryByTestId("bid-history-row-type-chip"),
    ).not.toBeInTheDocument();
  });

  it("renders a 'proxy' chip for PROXY_AUTO bids", () => {
    renderWithProviders(
      <ul>
        <BidHistoryRow entry={entry({ bidType: "PROXY_AUTO" })} />
      </ul>,
    );
    const chip = screen.getByTestId("bid-history-row-type-chip");
    expect(chip).toHaveAttribute("data-type", "PROXY_AUTO");
    expect(chip).toHaveTextContent("proxy");
  });

  it("renders a 'buy now' chip for BUY_NOW bids", () => {
    renderWithProviders(
      <ul>
        <BidHistoryRow entry={entry({ bidType: "BUY_NOW" })} />
      </ul>,
    );
    const chip = screen.getByTestId("bid-history-row-type-chip");
    expect(chip).toHaveAttribute("data-type", "BUY_NOW");
    expect(chip).toHaveTextContent("buy now");
  });

  it("renders the snipe-extension chip when snipeExtensionMinutes is set", () => {
    renderWithProviders(
      <ul>
        <BidHistoryRow entry={entry({ snipeExtensionMinutes: 15 })} />
      </ul>,
    );
    expect(
      screen.getByTestId("bid-history-row-snipe-chip"),
    ).toHaveTextContent("Extended 15m");
  });

  it("omits the snipe-extension chip when snipeExtensionMinutes is null", () => {
    renderWithProviders(
      <ul>
        <BidHistoryRow entry={entry({ snipeExtensionMinutes: null })} />
      </ul>,
    );
    expect(
      screen.queryByTestId("bid-history-row-snipe-chip"),
    ).not.toBeInTheDocument();
  });

  it("renders a relative timestamp with an absolute title attribute", () => {
    const created = new Date(Date.now() - 2 * 60_000).toISOString();
    renderWithProviders(
      <ul>
        <BidHistoryRow entry={entry({ createdAt: created })} />
      </ul>,
    );
    const time = screen.getByTestId("bid-history-row-time");
    expect(time).toHaveTextContent(/ago|just now/);
    expect(time).toHaveAttribute("title");
    expect(time.getAttribute("title")?.length).toBeGreaterThan(0);
  });

  it("applies the animation marker when isAnimated is true", () => {
    renderWithProviders(
      <ul>
        <BidHistoryRow entry={entry()} isAnimated />
      </ul>,
    );
    expect(screen.getByTestId("bid-history-row")).toHaveAttribute(
      "data-animated",
      "true",
    );
  });
});
