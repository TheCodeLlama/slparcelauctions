import { describe, expect, it, vi } from "vitest";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import { brokerCancelHandlers } from "@/test/msw/handlers";
import type { PublicAuctionResponse } from "@/types/auction";
import { BrokerCancelModal } from "./BrokerCancelModal";

const AUCTION_ID = "00000000-0000-0000-0000-0000000000c0";
const SELLER_ID = "00000000-0000-0000-0000-0000000000b2";
const GROUP_ID = "00000000-0000-0000-0000-0000000000a1";

function auctionFixture(
  overrides: Partial<PublicAuctionResponse> = {},
): PublicAuctionResponse {
  return {
    publicId: AUCTION_ID,
    sellerPublicId: SELLER_ID,
    title: "Sunset Plot",
    parcel: {
      slParcelUuid: "00000000-0000-0000-0000-0000000000e0",
      ownerUuid: "00000000-0000-0000-0000-0000000000e1",
      ownerType: "group",
      ownerName: "SL Group",
      parcelName: null,
      regionName: "Heterocera",
      gridX: 1000,
      gridY: 1000,
      positionX: 128,
      positionY: 128,
      positionZ: 0,
      areaSqm: 1024,
      description: null,
      snapshotUrl: null,
      slurl: "secondlife://Heterocera/128/128/25",
      verified: true,
      verifiedAt: "2026-04-01T00:00:00Z",
      lastChecked: "2026-04-02T00:00:00Z",
    },
    status: "ACTIVE",
    verificationTier: "SCRIPT",
    startingBid: 1000,
    hasReserve: false,
    reserveMet: false,
    buyNowPrice: null,
    currentBid: null,
    bidCount: 3,
    currentHighBid: 1500,
    bidderCount: 3,
    durationHours: 72,
    snipeProtect: false,
    snipeWindowMin: null,
    startsAt: "2026-04-02T00:00:00Z",
    endsAt: "2026-04-05T00:00:00Z",
    originalEndsAt: "2026-04-05T00:00:00Z",
    sellerDesc: null,
    tags: [],
    photos: [],
    realtyGroup: {
      publicId: GROUP_ID,
      name: "Sunset Realty",
      slug: "sunset",
      logoUrl: null,
      dissolved: false,
    },
    listingAgent: {
      publicId: SELLER_ID,
      displayName: "Seller Sam",
      avatarUrl: null,
    },
    ...overrides,
  };
}

describe("BrokerCancelModal", () => {
  it("renders auction title, active-bid state, and refund destination", () => {
    renderWithProviders(
      <BrokerCancelModal
        open
        onClose={() => {}}
        auction={auctionFixture()}
      />,
    );
    expect(screen.getByText("Sunset Plot")).toBeInTheDocument();
    expect(screen.getByTestId("broker-cancel-bids")).toHaveTextContent(
      /3 bids — current highest L\$1,500/,
    );
    expect(
      screen.getByText(/refund will be credited to the group wallet/i),
    ).toBeInTheDocument();
  });

  it("renders a 'no bids yet' line when bidCount is 0", () => {
    renderWithProviders(
      <BrokerCancelModal
        open
        onClose={() => {}}
        auction={auctionFixture({ bidCount: 0, currentHighBid: null })}
      />,
    );
    expect(screen.getByTestId("broker-cancel-bids")).toHaveTextContent(
      /No bids on this listing yet/i,
    );
  });

  it("disables the Cancel listing button until a non-empty reason is typed", async () => {
    renderWithProviders(
      <BrokerCancelModal
        open
        onClose={() => {}}
        auction={auctionFixture()}
      />,
    );
    const confirm = screen.getByTestId("broker-cancel-confirm");
    expect(confirm).toBeDisabled();

    await userEvent.type(
      screen.getByTestId("broker-cancel-reason"),
      "Seller MIA",
    );
    expect(confirm).toBeEnabled();
  });

  it("treats whitespace-only reasons as empty (button stays disabled)", async () => {
    renderWithProviders(
      <BrokerCancelModal
        open
        onClose={() => {}}
        auction={auctionFixture()}
      />,
    );
    const confirm = screen.getByTestId("broker-cancel-confirm");
    await userEvent.type(
      screen.getByTestId("broker-cancel-reason"),
      "   ",
    );
    expect(confirm).toBeDisabled();
  });

  it("submits the broker-cancel and closes on success", async () => {
    server.use(
      brokerCancelHandlers.cancelSuccess(AUCTION_ID, {
        ...auctionFixture(),
        status: "CANCELLED",
      }),
    );
    const onClose = vi.fn();

    renderWithProviders(
      <BrokerCancelModal
        open
        onClose={onClose}
        auction={auctionFixture()}
      />,
    );

    await userEvent.type(
      screen.getByTestId("broker-cancel-reason"),
      "Bot-detected fraud",
    );
    await userEvent.click(screen.getByTestId("broker-cancel-confirm"));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it("surfaces a 409 BROKER_CANCEL_NOT_APPLICABLE error inline without closing", async () => {
    server.use(brokerCancelHandlers.notApplicable(AUCTION_ID));
    const onClose = vi.fn();

    renderWithProviders(
      <BrokerCancelModal
        open
        onClose={onClose}
        auction={auctionFixture()}
      />,
    );

    await userEvent.type(
      screen.getByTestId("broker-cancel-reason"),
      "Trying anyway",
    );
    await userEvent.click(screen.getByTestId("broker-cancel-confirm"));

    expect(
      await screen.findByText(/Broker cancel not applicable/i),
    ).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
  });
});
