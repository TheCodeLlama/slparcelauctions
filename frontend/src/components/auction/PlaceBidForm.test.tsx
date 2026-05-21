import { describe, it, expect, vi, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import { PlaceBidForm } from "./PlaceBidForm";
import type { GroupAttribution, PublicAuctionResponse } from "@/types/auction";

vi.mock("@/lib/ws/client", () => ({
  subscribe: vi.fn(),
  subscribeToConnectionState: vi.fn(),
  getConnectionState: vi.fn(() => ({ status: "connected" })),
}));

function auctionFixture(
  overrides: Partial<PublicAuctionResponse> = {},
): PublicAuctionResponse {
  return {
    publicId: "00000000-0000-0000-0000-000000000007",
    sellerPublicId: "00000000-0000-0000-0000-000000000064",
    title: "Featured Parcel Listing",
    parcel: {
      slParcelUuid: "00000000-0000-0000-0000-000000000001",
      ownerUuid: "aaaa1111-0000-0000-0000-000000000000",
      ownerType: "agent",
      regionName: "Heterocera",
      gridX: 0,
      gridY: 0,
      positionX: 128,
      positionY: 128,
      positionZ: 0,
      ownerName: null,
      parcelName: null,
      continentName: null,
      areaSqm: 1024,
      description: "Beachfront parcel",
      snapshotUrl: null,
      slurl: "secondlife://Heterocera/128/128/25",
      maturityRating: "GENERAL",
      verified: true,
      verifiedAt: null,
      lastChecked: null,
      createdAt: "2026-04-17T00:00:00Z",
    },
    status: "ACTIVE",
    verificationTier: "SCRIPT",
    startingBid: 500,
    hasReserve: false,
    reserveMet: true,
    buyNowPrice: null,
    currentBid: 1500,
    bidCount: 3,
    currentHighBid: 1500,
    bidderCount: 2,
    durationHours: 72,
    snipeProtect: true,
    snipeWindowMin: 10,
    startsAt: "2026-04-19T00:00:00Z",
    endsAt: "2026-04-22T00:00:00Z",
    originalEndsAt: "2026-04-22T00:00:00Z",
    sellerDesc: null,
    tags: [],
    photos: [],
    ...overrides,
  };
}

const connected = { status: "connected" as const };

describe("PlaceBidForm", () => {
  beforeEach(() => {
    if (typeof window !== "undefined") {
      window.sessionStorage.clear();
    }
  });

  it("disables submit when below min and enables it when satisfied", async () => {
    // currentHighBid=1500 → min = 1500 + 100 = 1600.
    renderWithProviders(
      <PlaceBidForm auction={auctionFixture()} connectionState={connected} />,
      { auth: "authenticated" },
    );
    const submit = screen.getByTestId("place-bid-submit");
    expect(submit).toBeDisabled();

    const input = screen.getByTestId(
      "place-bid-amount-input",
    ) as HTMLInputElement;
    await userEvent.type(input, "1599");
    expect(submit).toBeDisabled();
    await userEvent.clear(input);
    await userEvent.type(input, "1600");
    expect(submit).not.toBeDisabled();
  });

  it("posts a valid bid and clears the input on success", async () => {
    let received: { amount: number } | null = null;
    server.use(
      http.post("*/api/v1/auctions/00000000-0000-0000-0000-000000000007/bids", async ({ request }) => {
        received = (await request.json()) as { amount: number };
        return HttpResponse.json({
          bidId: 100,
          auctionId: 7,
          amount: received.amount,
          bidType: "MANUAL",
          bidCount: 4,
          endsAt: "2026-04-22T00:00:00Z",
          originalEndsAt: "2026-04-22T00:00:00Z",
          snipeExtensionMinutes: null,
          newEndsAt: null,
          buyNowTriggered: false,
        });
      }),
    );
    renderWithProviders(
      <PlaceBidForm auction={auctionFixture()} connectionState={connected} />,
      { auth: "authenticated" },
    );
    const input = screen.getByTestId(
      "place-bid-amount-input",
    ) as HTMLInputElement;
    await userEvent.type(input, "1600");
    await userEvent.click(screen.getByTestId("place-bid-submit"));
    await waitFor(() => {
      expect(received).not.toBeNull();
    });
    expect(received!.amount).toBe(1600);
    await waitFor(() => {
      expect(input.value).toBe("");
    });
  });

  it("surfaces BID_TOO_LOW inline and auto-fills the input with server min", async () => {
    server.use(
      http.post("*/api/v1/auctions/00000000-0000-0000-0000-000000000007/bids", () =>
        HttpResponse.json(
          {
            status: 400,
            title: "Bid Too Low",
            detail: "Bid below minimum",
            code: "BID_TOO_LOW",
            minRequired: 1700,
          },
          { status: 400 },
        ),
      ),
    );
    renderWithProviders(
      <PlaceBidForm auction={auctionFixture()} connectionState={connected} />,
      { auth: "authenticated" },
    );
    const input = screen.getByTestId(
      "place-bid-amount-input",
    ) as HTMLInputElement;
    await userEvent.type(input, "1600");
    await userEvent.click(screen.getByTestId("place-bid-submit"));
    await waitFor(() => {
      expect(screen.getByText(/Minimum bid is L\$1,700/i)).toBeInTheDocument();
    });
    expect(input.value).toBe("1700");
  });

  it("inline error clears when user edits the input", async () => {
    server.use(
      http.post("*/api/v1/auctions/00000000-0000-0000-0000-000000000007/bids", () =>
        HttpResponse.json(
          {
            status: 400,
            title: "Bid Too Low",
            detail: "Bid below minimum",
            code: "BID_TOO_LOW",
            minRequired: 1700,
          },
          { status: 400 },
        ),
      ),
    );
    renderWithProviders(
      <PlaceBidForm auction={auctionFixture()} connectionState={connected} />,
      { auth: "authenticated" },
    );
    const input = screen.getByTestId(
      "place-bid-amount-input",
    ) as HTMLInputElement;
    await userEvent.type(input, "1600");
    await userEvent.click(screen.getByTestId("place-bid-submit"));
    // Server error surfaces inline.
    await waitFor(() => {
      expect(screen.getByText(/Minimum bid is L\$1,700/i)).toBeInTheDocument();
    });
    // Typing a new value should clear the inline error immediately.
    await userEvent.type(input, "0");
    await waitFor(() => {
      expect(
        screen.queryByText(/Minimum bid is L\$1,700/i),
      ).not.toBeInTheDocument();
    });
  });

  it("fires the buy-now confirm dialog when amount > buyNowPrice", async () => {
    const auction = auctionFixture({ buyNowPrice: 5_000, currentHighBid: 0 });
    renderWithProviders(
      <PlaceBidForm auction={auction} connectionState={connected} />,
      { auth: "authenticated" },
    );
    const input = screen.getByTestId(
      "place-bid-amount-input",
    ) as HTMLInputElement;
    await userEvent.type(input, "9999");
    await userEvent.click(screen.getByTestId("place-bid-submit"));
    expect(await screen.findByTestId("confirm-bid-dialog")).toHaveTextContent(
      "buy-now at L$5,000",
    );
  });

  it("flips the button label to 'Buy now' when amount equals buyNowPrice", async () => {
    const auction = auctionFixture({ buyNowPrice: 5_000, currentHighBid: 0 });
    renderWithProviders(
      <PlaceBidForm auction={auction} connectionState={connected} />,
      { auth: "authenticated" },
    );
    const input = screen.getByTestId(
      "place-bid-amount-input",
    ) as HTMLInputElement;
    await userEvent.type(input, "5000");
    expect(screen.getByTestId("place-bid-submit")).toHaveTextContent(
      /Buy now · L\$5,000/i,
    );
  });

  it("shows a Buy now button when the auction has a buy-now price", () => {
    const auction = auctionFixture({ buyNowPrice: 25_000, currentHighBid: 0 });
    renderWithProviders(
      <PlaceBidForm auction={auction} connectionState={connected} />,
      { auth: "authenticated" },
    );
    expect(screen.getByTestId("place-bid-buy-now")).toHaveTextContent(
      "Buy now · L$25,000",
    );
  });

  it("does not show the Buy now button when there is no buy-now price", () => {
    renderWithProviders(
      <PlaceBidForm
        auction={auctionFixture({ buyNowPrice: null })}
        connectionState={connected}
      />,
      { auth: "authenticated" },
    );
    expect(
      screen.queryByTestId("place-bid-buy-now"),
    ).not.toBeInTheDocument();
  });

  it("Buy now opens the buy-now confirm and places a bid at exactly the buy-now price", async () => {
    let received: { amount: number } | null = null;
    server.use(
      http.post("*/api/v1/auctions/00000000-0000-0000-0000-000000000007/bids", async ({ request }) => {
        received = (await request.json()) as { amount: number };
        return HttpResponse.json({
          bidId: 100,
          auctionId: 7,
          amount: received.amount,
          bidType: "BUY_NOW",
          bidCount: 1,
          endsAt: "2026-04-22T00:00:00Z",
          originalEndsAt: "2026-04-22T00:00:00Z",
          snipeExtensionMinutes: null,
          newEndsAt: null,
          buyNowTriggered: true,
        });
      }),
    );
    const auction = auctionFixture({ buyNowPrice: 25_000, currentHighBid: 0 });
    renderWithProviders(
      <PlaceBidForm auction={auction} connectionState={connected} />,
      { auth: "authenticated" },
    );
    // Type an unrelated bid amount; Buy now must ignore it.
    const input = screen.getByTestId(
      "place-bid-amount-input",
    ) as HTMLInputElement;
    await userEvent.type(input, "600");
    await userEvent.click(screen.getByTestId("place-bid-buy-now"));
    // ConfirmBidDialog buy-now branch is open — its confirm button reads
    // "Buy now · L$25,000"; scope to the dialog testid since the dedicated
    // trigger button carries the same accessible name.
    const dialog = await screen.findByTestId("confirm-bid-dialog");
    expect(dialog).toHaveTextContent("buy-now at L$25,000");
    await userEvent.click(
      screen.getByTestId("confirm-bid-dialog-confirm"),
    );
    await waitFor(() => {
      expect(received).not.toBeNull();
    });
    expect(received!.amount).toBe(25_000);
  });

  it("disables Buy now when disconnected", () => {
    const auction = auctionFixture({ buyNowPrice: 25_000, currentHighBid: 0 });
    renderWithProviders(
      <PlaceBidForm
        auction={auction}
        connectionState={{ status: "reconnecting" }}
      />,
      { auth: "authenticated" },
    );
    expect(screen.getByTestId("place-bid-buy-now")).toBeDisabled();
  });

  it("fires the large-bid confirm dialog when amount > 10,000", async () => {
    // currentHighBid=0 → min = startingBid = 500, so 15000 is valid.
    const auction = auctionFixture({
      currentHighBid: 0,
      startingBid: 500,
    });
    renderWithProviders(
      <PlaceBidForm auction={auction} connectionState={connected} />,
      { auth: "authenticated" },
    );
    const input = screen.getByTestId("place-bid-amount-input");
    await userEvent.type(input, "15000");
    await userEvent.click(screen.getByTestId("place-bid-submit"));
    expect(await screen.findByTestId("confirm-bid-dialog")).toHaveTextContent(
      "L$15,000",
    );
  });

  it("skips the large-bid confirm after the don't-ask-again checkbox is set", async () => {
    const auction = auctionFixture({ currentHighBid: 0, startingBid: 500 });

    let calls = 0;
    server.use(
      http.post("*/api/v1/auctions/00000000-0000-0000-0000-000000000007/bids", async () => {
        calls += 1;
        return HttpResponse.json({
          bidId: 100,
          auctionId: 7,
          amount: 15_000,
          bidType: "MANUAL",
          bidCount: 1,
          endsAt: "2026-04-22T00:00:00Z",
          originalEndsAt: "2026-04-22T00:00:00Z",
          snipeExtensionMinutes: null,
          newEndsAt: null,
          buyNowTriggered: false,
        });
      }),
    );

    renderWithProviders(
      <PlaceBidForm auction={auction} connectionState={connected} />,
      { auth: "authenticated" },
    );
    const input = screen.getByTestId(
      "place-bid-amount-input",
    ) as HTMLInputElement;
    await userEvent.type(input, "15000");
    await userEvent.click(screen.getByTestId("place-bid-submit"));
    // First time: dialog appears. Check "don't ask again" + confirm.
    await userEvent.click(
      screen.getByTestId("confirm-bid-dialog-dont-ask-again"),
    );
    await userEvent.click(screen.getByTestId("confirm-bid-dialog-confirm"));
    await waitFor(() => {
      expect(calls).toBe(1);
    });

    // Retype and submit again — should bypass the dialog entirely.
    await userEvent.type(input, "15000");
    await userEvent.click(screen.getByTestId("place-bid-submit"));
    await waitFor(() => {
      expect(calls).toBe(2);
    });
    expect(screen.queryByTestId("confirm-bid-dialog")).not.toBeInTheDocument();
  });

  it("disables submit and shows helper text when disconnected", async () => {
    renderWithProviders(
      <PlaceBidForm
        auction={auctionFixture()}
        connectionState={{ status: "reconnecting" }}
      />,
      { auth: "authenticated" },
    );
    const input = screen.getByTestId("place-bid-amount-input");
    await userEvent.type(input, "1600");
    expect(screen.getByTestId("place-bid-submit")).toBeDisabled();
    expect(screen.getByTestId("place-bid-connection-helper")).toHaveTextContent(
      /Waiting for connection/i,
    );
  });

  describe("group COI", () => {
    const groupAttribution: GroupAttribution = {
      publicId: "g1",
      name: "Sunset Realty",
      slug: "sunset",
      logoLightUrl: null, logoDarkUrl: null,
      dissolved: false,
    };

    it("renders COI message when current user is a member of the auction group", async () => {
      server.use(
        http.get("*/api/v1/me/realty-groups", () =>
          HttpResponse.json([
            { publicId: "g1", name: "Sunset Realty", slug: "sunset", logoLightUrl: null, logoDarkUrl: null, memberCount: 1, memberSince: "2026-01-01T00:00:00Z" },
          ]),
        ),
      );
      renderWithProviders(
        <PlaceBidForm
          auction={auctionFixture({ realtyGroup: groupAttribution })}
          connectionState={connected}
        />,
        { auth: "authenticated" },
      );
      expect(
        await screen.findByText(/You're a member of/i),
      ).toBeInTheDocument();
      expect(screen.getByText(/Sunset Realty/)).toBeInTheDocument();
      expect(screen.queryByTestId("place-bid-form")).not.toBeInTheDocument();
    });

    it("renders the bid form normally when current user is not a member", async () => {
      // Default handler returns [] — user is not a member.
      renderWithProviders(
        <PlaceBidForm
          auction={auctionFixture({ realtyGroup: groupAttribution })}
          connectionState={connected}
        />,
        { auth: "authenticated" },
      );
      await waitFor(() => {
        expect(screen.queryByText(/You're a member of/i)).not.toBeInTheDocument();
      });
      expect(screen.getByTestId("place-bid-form")).toBeInTheDocument();
    });

    it("renders the bid form normally when auction has no realtyGroup", async () => {
      renderWithProviders(
        <PlaceBidForm
          auction={auctionFixture({ realtyGroup: null })}
          connectionState={connected}
        />,
        { auth: "authenticated" },
      );
      await waitFor(() => {
        expect(screen.queryByText(/You're a member of/i)).not.toBeInTheDocument();
      });
      expect(screen.getByTestId("place-bid-form")).toBeInTheDocument();
    });
  });
});
