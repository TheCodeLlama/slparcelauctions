import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import {
  renderWithProviders,
  screen,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import { mockUser } from "@/test/msw/fixtures";
import type { PublicAuctionResponse } from "@/types/auction";
import type {
  AgentCardDto,
  LeaderCardDto,
  RealtyGroupPublicDto,
} from "@/types/realty";
import { BrokerCancelButton } from "./BrokerCancelButton";

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

const GROUP_ID = "00000000-0000-0000-0000-0000000000a1";
const SELLER_ID = "00000000-0000-0000-0000-0000000000b2";
const BROKER_ID = "00000000-0000-0000-0000-0000000000b3";
const LEADER_ID = "00000000-0000-0000-0000-0000000000b4";
const NON_MEMBER_ID = "00000000-0000-0000-0000-0000000000b5";
const AUCTION_ID = "00000000-0000-0000-0000-0000000000c0";

function leaderCard(overrides: Partial<LeaderCardDto> = {}): LeaderCardDto {
  return {
    userPublicId: LEADER_ID,
    displayName: "Leader Liz",
    avatarUrl: null,
    ...overrides,
  };
}

function agentCard(overrides: Partial<AgentCardDto>): AgentCardDto {
  return {
    memberPublicId: "00000000-0000-0000-0000-0000000000d0",
    userPublicId: BROKER_ID,
    displayName: "Broker Bob",
    avatarUrl: null,
    role: "AGENT",
    permissions: [],
    joinedAt: "2026-04-01T00:00:00Z",
    agentCommissionRate: 0.1,
    ...overrides,
  };
}

function publicGroup(overrides: Partial<RealtyGroupPublicDto> = {}): RealtyGroupPublicDto {
  return {
    publicId: GROUP_ID,
    name: "Sunset Realty",
    slug: "sunset",
    description: null,
    website: null,
    logoLightUrl: null, logoDarkUrl: null,
    coverLightUrl: null, coverDarkUrl: null,
    memberSince: "2026-01-01T00:00:00Z",
    leader: leaderCard(),
    agents: [],
    memberSeatLimit: 25,
    memberCount: 2,
    ...overrides,
  };
}

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
    bidCount: 0,
    currentHighBid: null,
    bidderCount: 0,
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
      logoLightUrl: null, logoDarkUrl: null,
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

function installGroup(group: RealtyGroupPublicDto) {
  server.use(
    http.get(`*/api/v1/realty-groups/${group.publicId}`, () =>
      HttpResponse.json(group),
    ),
  );
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("BrokerCancelButton", () => {
  it("shows the button for a broker (MANAGE_ALL_LISTINGS) who isn't the seller", async () => {
    installGroup(
      publicGroup({
        agents: [
          agentCard({
            userPublicId: BROKER_ID,
            permissions: ["MANAGE_ALL_LISTINGS"],
          }),
        ],
      }),
    );

    renderWithProviders(<BrokerCancelButton auction={auctionFixture()} />, {
      auth: "authenticated",
      authUser: { ...mockUser, publicId: BROKER_ID },
    });

    expect(
      await screen.findByTestId("broker-cancel-button"),
    ).toBeInTheDocument();
  });

  it("shows the button for the group leader implicitly (no flag check)", async () => {
    installGroup(publicGroup({ agents: [] }));

    renderWithProviders(<BrokerCancelButton auction={auctionFixture()} />, {
      auth: "authenticated",
      authUser: { ...mockUser, publicId: LEADER_ID },
    });

    expect(
      await screen.findByTestId("broker-cancel-button"),
    ).toBeInTheDocument();
  });

  it("hides the button when the caller is the seller (broker == seller is not a thing)", async () => {
    installGroup(
      publicGroup({
        agents: [
          agentCard({
            userPublicId: SELLER_ID,
            permissions: ["MANAGE_ALL_LISTINGS"],
          }),
        ],
      }),
    );

    renderWithProviders(<BrokerCancelButton auction={auctionFixture()} />, {
      auth: "authenticated",
      authUser: { ...mockUser, publicId: SELLER_ID },
    });

    // Give the group fetch time to settle, then assert nothing is rendered.
    await waitFor(() => {
      expect(
        screen.queryByTestId("broker-cancel-button"),
      ).not.toBeInTheDocument();
    });
  });

  it("hides the button when the caller is not a member of the group", async () => {
    installGroup(
      publicGroup({
        agents: [
          agentCard({
            userPublicId: BROKER_ID,
            permissions: ["MANAGE_ALL_LISTINGS"],
          }),
        ],
      }),
    );

    renderWithProviders(<BrokerCancelButton auction={auctionFixture()} />, {
      auth: "authenticated",
      authUser: { ...mockUser, publicId: NON_MEMBER_ID },
    });

    await waitFor(() => {
      expect(
        screen.queryByTestId("broker-cancel-button"),
      ).not.toBeInTheDocument();
    });
  });

  it("hides the button when the caller is a member without MANAGE_ALL_LISTINGS", async () => {
    installGroup(
      publicGroup({
        agents: [
          agentCard({
            userPublicId: BROKER_ID,
            permissions: ["CREATE_LISTING"],
          }),
        ],
      }),
    );

    renderWithProviders(<BrokerCancelButton auction={auctionFixture()} />, {
      auth: "authenticated",
      authUser: { ...mockUser, publicId: BROKER_ID },
    });

    await waitFor(() => {
      expect(
        screen.queryByTestId("broker-cancel-button"),
      ).not.toBeInTheDocument();
    });
  });

  it("hides the button for an individual (non-group) auction", () => {
    // No fetch is needed — the realtyGroup gate short-circuits before
    // useRealtyGroup is enabled.
    renderWithProviders(
      <BrokerCancelButton
        auction={auctionFixture({ realtyGroup: null, listingAgent: null })}
      />,
      { auth: "authenticated", authUser: { ...mockUser, publicId: BROKER_ID } },
    );
    expect(
      screen.queryByTestId("broker-cancel-button"),
    ).not.toBeInTheDocument();
  });

  it("hides the button when the auction is in a terminal status (ENDED)", () => {
    renderWithProviders(
      <BrokerCancelButton auction={auctionFixture({ status: "ENDED" })} />,
      { auth: "authenticated", authUser: { ...mockUser, publicId: BROKER_ID } },
    );
    expect(
      screen.queryByTestId("broker-cancel-button"),
    ).not.toBeInTheDocument();
  });

  it("hides the button when the caller is unauthenticated", () => {
    renderWithProviders(<BrokerCancelButton auction={auctionFixture()} />);
    expect(
      screen.queryByTestId("broker-cancel-button"),
    ).not.toBeInTheDocument();
  });
});
