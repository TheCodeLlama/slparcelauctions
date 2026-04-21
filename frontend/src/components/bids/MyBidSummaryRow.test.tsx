import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import type { MyBidStatus, MyBidSummary } from "@/types/auction";
import { MyBidSummaryRow } from "./MyBidSummaryRow";

function summary(
  overrides: {
    status?: MyBidStatus;
    auctionId?: number;
    parcelName?: string | null;
    parcelRegion?: string | null;
    myProxyMaxAmount?: number | null;
    auctionStatus?: MyBidSummary["auction"]["status"];
    currentBid?: number | null;
    endsAt?: string | null;
  } = {},
): MyBidSummary {
  return {
    auction: {
      id: overrides.auctionId ?? 42,
      status: overrides.auctionStatus ?? "ACTIVE",
      endOutcome: null,
      parcelName:
        "parcelName" in overrides ? overrides.parcelName! : "Heterocera Plot",
      parcelRegion:
        "parcelRegion" in overrides ? overrides.parcelRegion! : "Neumoegen",
      parcelAreaSqm: 1024,
      snapshotUrl: null,
      endsAt:
        overrides.endsAt === undefined
          ? new Date(Date.now() + 60 * 60 * 1000).toISOString()
          : overrides.endsAt,
      endedAt: null,
      currentBid: "currentBid" in overrides ? overrides.currentBid! : 4200,
      bidderCount: 3,
      sellerUserId: 7,
      sellerDisplayName: "Seller",
    },
    myHighestBidAmount: 4200,
    myHighestBidAt: "2026-04-20T12:00:00Z",
    myProxyMaxAmount:
      "myProxyMaxAmount" in overrides ? overrides.myProxyMaxAmount! : null,
    myBidStatus: overrides.status ?? "WINNING",
  };
}

describe("MyBidSummaryRow", () => {
  it("renders parcel name, region, area and my bid amount", () => {
    render(<MyBidSummaryRow bid={summary()} />);
    expect(screen.getByText("Heterocera Plot")).toBeInTheDocument();
    expect(screen.getByText(/Neumoegen/)).toBeInTheDocument();
    expect(screen.getByText(/1,024 m²/)).toBeInTheDocument();
    // There are multiple "L$4,200" (your bid + current), just assert at least one.
    expect(screen.getAllByText("L$4,200").length).toBeGreaterThan(0);
  });

  it("links the row to the public auction page", () => {
    render(<MyBidSummaryRow bid={summary({ auctionId: 101 })} />);
    const anchor = screen.getByRole("link");
    expect(anchor).toHaveAttribute("href", "/auction/101");
  });

  it.each<{ status: MyBidStatus; borderClass: string }>([
    { status: "WINNING", borderClass: "border-l-tertiary-container" },
    { status: "OUTBID", borderClass: "border-l-error" },
    { status: "WON", borderClass: "border-l-primary" },
    { status: "LOST", borderClass: "border-l-on-surface-variant" },
    { status: "RESERVE_NOT_MET", borderClass: "border-l-secondary-container" },
    { status: "CANCELLED", borderClass: "border-l-on-surface-variant" },
    { status: "SUSPENDED", borderClass: "border-l-error" },
  ])("applies the $borderClass accent for $status", ({ status, borderClass }) => {
    render(<MyBidSummaryRow bid={summary({ status })} />);
    const row = screen.getByTestId("my-bid-row-42");
    expect(row.className).toContain(borderClass);
  });

  it("strikes through parcel name for CANCELLED", () => {
    render(<MyBidSummaryRow bid={summary({ status: "CANCELLED" })} />);
    const name = screen.getByText("Heterocera Plot");
    expect(name.className).toContain("line-through");
  });

  it("strikes through parcel name for SUSPENDED", () => {
    render(<MyBidSummaryRow bid={summary({ status: "SUSPENDED" })} />);
    const name = screen.getByText("Heterocera Plot");
    expect(name.className).toContain("line-through");
  });

  it("renders the proxy max amount when provided", () => {
    render(
      <MyBidSummaryRow bid={summary({ myProxyMaxAmount: 7500 })} />,
    );
    expect(screen.getByText("L$7,500")).toBeInTheDocument();
    expect(screen.getByText(/Proxy max/)).toBeInTheDocument();
  });

  it("omits the proxy max line when absent", () => {
    render(<MyBidSummaryRow bid={summary({ myProxyMaxAmount: null })} />);
    expect(screen.queryByText(/Proxy max/)).not.toBeInTheDocument();
  });

  it("renders an em-dash for current when no current bid", () => {
    render(
      <MyBidSummaryRow bid={summary({ currentBid: null })} />,
    );
    expect(screen.getByText("—")).toBeInTheDocument();
  });

  it("falls back to '(unnamed parcel)' when parcelName missing", () => {
    render(<MyBidSummaryRow bid={summary({ parcelName: null })} />);
    expect(screen.getByText("(unnamed parcel)")).toBeInTheDocument();
  });

  it("omits the countdown when the auction is not ACTIVE", () => {
    render(
      <MyBidSummaryRow
        bid={summary({ auctionStatus: "ENDED", status: "WON" })}
      />,
    );
    expect(screen.queryByRole("timer")).not.toBeInTheDocument();
  });
});
