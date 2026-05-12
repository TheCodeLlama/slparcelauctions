import { describe, it, expect, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders } from "@/test/render";
import { server } from "@/test/msw/server";
import { AgentFeePreview } from "./AgentFeePreview";

// ---------------------------------------------------------------------------
// Individual-listing (no groupPublicId) — existing tests
// ---------------------------------------------------------------------------

describe("AgentFeePreview — individual flow", () => {
  it("computes payout = startingBid - floor(startingBid * 0.05) - floor(startingBid * rate)", () => {
    // L$1000 - floor(50) - floor(20) = L$930
    renderWithProviders(<AgentFeePreview startingBid={1000} groupName="Sunset Realty" agentFeeRate={0.02} />);
    expect(screen.getByText(/Sunset Realty/i)).toBeInTheDocument();
    expect(screen.getByText(/L\$930/)).toBeInTheDocument();
    expect(screen.getByText(/2%/)).toBeInTheDocument();
  });

  it("shows 0% group fee when rate is 0", () => {
    renderWithProviders(<AgentFeePreview startingBid={1000} groupName="Free Group" agentFeeRate={0} />);
    expect(screen.getByText(/0%/)).toBeInTheDocument();
    expect(screen.getByText(/L\$950/)).toBeInTheDocument();
  });

  it("renders nothing for a non-positive startingBid", () => {
    renderWithProviders(
      <AgentFeePreview startingBid={0} groupName="X" agentFeeRate={0.02} />,
    );
    // Component returns null for non-positive bids — nothing visible to assert on.
    expect(screen.queryByText(/L\$/)).not.toBeInTheDocument();
  });

  it("uses floor rounding (rate 0.0333 on L$1000 -> fee 33 -> payout 917)", () => {
    renderWithProviders(<AgentFeePreview startingBid={1000} groupName="G" agentFeeRate={0.0333} />);
    expect(screen.getByText(/L\$917/)).toBeInTheDocument();
  });

  it("does not render a wallet source line when groupPublicId is not provided", () => {
    renderWithProviders(
      <AgentFeePreview startingBid={1000} groupName="Sunset Realty" agentFeeRate={0.02} />,
    );
    expect(screen.queryByText(/wallet/i)).not.toBeInTheDocument();
  });
});

// ---------------------------------------------------------------------------
// Group-listing flow — wallet source line + insufficient guard
// ---------------------------------------------------------------------------

const GROUP_ID = "g-wallet-test-1";

describe("AgentFeePreview — group flow", () => {
  it("renders wallet source line when balance is sufficient", async () => {
    // listingFee = floor(1000 * 0.05) = 50. Balance 500 > 50 → sufficient.
    server.use(
      http.get(`*/api/v1/realty/groups/${GROUP_ID}/wallet`, () =>
        HttpResponse.json({ balance: 500, reserved: 0, available: 500, recentLedger: [] }),
      ),
    );

    const onInsufficient = vi.fn();

    renderWithProviders(
      <AgentFeePreview
        startingBid={1000}
        groupName="Sunset Realty"
        agentFeeRate={0.02}
        groupPublicId={GROUP_ID}
        onInsufficient={onInsufficient}
      />,
    );

    await waitFor(() => {
      expect(
        screen.getByText(/Listing fee paid from Sunset Realty wallet — current balance L\$500\./),
      ).toBeInTheDocument();
    });

    await waitFor(() => {
      expect(onInsufficient).toHaveBeenCalledWith(false);
    });
  });

  it("renders shortfall message and calls onInsufficient(true) when balance is insufficient", async () => {
    // listingFee = floor(1000 * 0.05) = 50. Balance 20 < 50 → shortfall = 30.
    server.use(
      http.get(`*/api/v1/realty/groups/${GROUP_ID}/wallet`, () =>
        HttpResponse.json({ balance: 20, reserved: 0, available: 20, recentLedger: [] }),
      ),
    );

    const onInsufficient = vi.fn();

    renderWithProviders(
      <AgentFeePreview
        startingBid={1000}
        groupName="Sunset Realty"
        agentFeeRate={0.02}
        groupPublicId={GROUP_ID}
        onInsufficient={onInsufficient}
      />,
    );

    await waitFor(() => {
      expect(
        screen.getByText(/Group wallet has L\$20; deposit L\$30 to publish\./),
      ).toBeInTheDocument();
    });

    await waitFor(() => {
      expect(onInsufficient).toHaveBeenCalledWith(true);
    });
  });

  it("does not render an extra line while wallet data is loading", () => {
    // The wallet endpoint never resolves in this test — we just verify no
    // premature wallet-source line appears while the query is in-flight.
    server.use(
      http.get(`*/api/v1/realty/groups/${GROUP_ID}/wallet`, () =>
        new Promise(() => { /* never resolves */ }),
      ),
    );

    renderWithProviders(
      <AgentFeePreview
        startingBid={1000}
        groupName="Sunset Realty"
        agentFeeRate={0.02}
        groupPublicId={GROUP_ID}
      />,
    );

    // The payout line is visible immediately.
    expect(screen.getByText(/Sunset Realty agent fee/i)).toBeInTheDocument();
    // No wallet-source or shortfall line while loading.
    expect(screen.queryByText(/wallet/i)).not.toBeInTheDocument();
  });
});
