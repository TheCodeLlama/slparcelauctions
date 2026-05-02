import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import { mockVerifiedCurrentUser } from "@/test/msw/fixtures";
import type { SellerAuctionResponse } from "@/types/auction";
import type {
  CancellationOffenseKind,
  CancellationStatusResponse,
} from "@/types/cancellation";
import type { CurrentUser } from "@/lib/user/api";
import {
  CancelListingModal,
  resolveCopyVariant,
} from "./CancelListingModal";

const push = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push,
    replace: vi.fn(),
    back: vi.fn(),
    refresh: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  usePathname: () => "/listings/42/activate",
  useSearchParams: () => new URLSearchParams(),
}));

function baseAuction(
  overrides: Partial<SellerAuctionResponse> = {},
): SellerAuctionResponse {
  return {
    id: 42,
    sellerId: 1,
    title: "Featured Parcel Listing",
    parcel: {
      id: 1,
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
      verified: false,
      verifiedAt: null,
      lastChecked: null,
      createdAt: "2026-04-17T00:00:00Z",
    },
    status: "DRAFT_PAID",
    verificationMethod: null,
    verificationTier: null,
    pendingVerification: null,
    verificationNotes: null,
    startingBid: 500,
    reservePrice: null,
    buyNowPrice: null,
    currentBid: null,
    bidCount: 0,
    currentHighBid: null,
    bidderCount: 0,
    winnerId: null,
    durationHours: 72,
    snipeProtect: true,
    snipeWindowMin: 10,
    startsAt: null,
    endsAt: null,
    originalEndsAt: null,
    sellerDesc: null,
    tags: [],
    photos: [],
    listingFeePaid: true,
    listingFeeAmt: 100,
    listingFeeTxn: null,
    listingFeePaidAt: null,
    commissionRate: 0.05,
    commissionAmt: null,
    createdAt: "2026-04-17T00:00:00Z",
    updatedAt: "2026-04-17T00:00:00Z",
    ...overrides,
  };
}

function makeMe(overrides: Partial<CurrentUser> = {}): CurrentUser {
  return { ...mockVerifiedCurrentUser, ...overrides };
}

function makeStatus(
  priorOffensesWithBids: number,
  nextKind: CancellationOffenseKind = "WARNING",
  amountL: number | null = null,
): CancellationStatusResponse {
  return {
    priorOffensesWithBids,
    currentSuspension: {
      penaltyBalanceOwed: 0,
      listingSuspensionUntil: null,
      bannedFromListing: false,
    },
    nextConsequenceIfBidsPresent: {
      kind: nextKind,
      amountL,
      suspends30Days: nextKind === "PENALTY_AND_30D",
      permanentBan: nextKind === "PERMANENT_BAN",
    },
  };
}

/**
 * Common MSW seeding for the consequence-aware copy renders. Wires both
 * {@code /me} and {@code /me/cancellation-status} so the modal has the
 * data it needs to compute the variant on the first paint.
 */
function seedHandlers(user: CurrentUser, status: CancellationStatusResponse) {
  server.use(
    http.get("*/api/v1/users/me", () => HttpResponse.json(user)),
    http.get("*/api/v1/users/me/cancellation-status", () =>
      HttpResponse.json(status),
    ),
  );
}

describe("resolveCopyVariant", () => {
  it("returns BANNED first regardless of priors or bid count", () => {
    expect(
      resolveCopyVariant({
        bannedFromListing: true,
        bidCount: 5,
        priorOffensesWithBids: 0,
      }),
    ).toBe("BANNED");
    expect(
      resolveCopyVariant({
        bannedFromListing: true,
        bidCount: 0,
        priorOffensesWithBids: 99,
      }),
    ).toBe("BANNED");
  });

  it("returns NO_BIDS when bidCount is 0 (and not banned)", () => {
    expect(
      resolveCopyVariant({
        bannedFromListing: false,
        bidCount: 0,
        priorOffensesWithBids: 3,
      }),
    ).toBe("NO_BIDS");
  });

  it("returns FIRST_OFFENSE when 0 priors and bids present", () => {
    expect(
      resolveCopyVariant({
        bannedFromListing: false,
        bidCount: 1,
        priorOffensesWithBids: 0,
      }),
    ).toBe("FIRST_OFFENSE");
  });

  it("returns FIRST_OFFENSE while priors are still loading (undefined)", () => {
    expect(
      resolveCopyVariant({
        bannedFromListing: false,
        bidCount: 1,
        priorOffensesWithBids: undefined,
      }),
    ).toBe("FIRST_OFFENSE");
  });

  it("ladders to SECOND/THIRD/FOURTH+ as priors increase", () => {
    expect(
      resolveCopyVariant({
        bannedFromListing: false,
        bidCount: 1,
        priorOffensesWithBids: 1,
      }),
    ).toBe("SECOND_OFFENSE");
    expect(
      resolveCopyVariant({
        bannedFromListing: false,
        bidCount: 1,
        priorOffensesWithBids: 2,
      }),
    ).toBe("THIRD_OFFENSE");
    expect(
      resolveCopyVariant({
        bannedFromListing: false,
        bidCount: 1,
        priorOffensesWithBids: 3,
      }),
    ).toBe("FOURTH_PLUS_OFFENSE");
    expect(
      resolveCopyVariant({
        bannedFromListing: false,
        bidCount: 1,
        priorOffensesWithBids: 7,
      }),
    ).toBe("FOURTH_PLUS_OFFENSE");
  });
});

describe("CancelListingModal — consequence-aware copy", () => {
  it("renders the BANNED variant when the user is permanently banned", async () => {
    seedHandlers(makeMe({ bannedFromListing: true }), makeStatus(2));
    renderWithProviders(
      <CancelListingModal
        open
        onClose={vi.fn()}
        auction={baseAuction({ status: "ACTIVE", bidCount: 3 })}
      />,
      { auth: "authenticated" },
    );
    const copy = await screen.findByTestId("cancel-modal-consequence-copy");
    // /me may resolve a tick after first paint, so wait for the
    // variant to settle on BANNED rather than reading the snapshot at
    // findBy time.
    await waitFor(() => expect(copy.dataset.variant).toBe("BANNED"));
    expect(copy).toHaveTextContent(
      /permanently banned from creating new listings/i,
    );
  });

  it("renders NO_BIDS when there are no bids on the auction", async () => {
    seedHandlers(makeMe(), makeStatus(2));
    renderWithProviders(
      <CancelListingModal
        open
        onClose={vi.fn()}
        auction={baseAuction({ status: "ACTIVE", bidCount: 0 })}
      />,
      { auth: "authenticated" },
    );
    const copy = await screen.findByTestId("cancel-modal-consequence-copy");
    await waitFor(() => expect(copy.dataset.variant).toBe("NO_BIDS"));
    expect(copy).toHaveTextContent(/no penalty will apply/i);
  });

  it("renders FIRST_OFFENSE when bids present and 0 priors", async () => {
    seedHandlers(makeMe(), makeStatus(0, "WARNING"));
    renderWithProviders(
      <CancelListingModal
        open
        onClose={vi.fn()}
        auction={baseAuction({ status: "ACTIVE", bidCount: 2 })}
      />,
      { auth: "authenticated" },
    );
    const copy = await screen.findByTestId("cancel-modal-consequence-copy");
    await waitFor(() => expect(copy.dataset.variant).toBe("FIRST_OFFENSE"));
    expect(copy).toHaveTextContent(/recorded as a warning/i);
  });

  it("renders SECOND_OFFENSE on 1 prior with bids present", async () => {
    seedHandlers(makeMe(), makeStatus(1, "PENALTY", 1000));
    renderWithProviders(
      <CancelListingModal
        open
        onClose={vi.fn()}
        auction={baseAuction({ status: "ACTIVE", bidCount: 2 })}
      />,
      { auth: "authenticated" },
    );
    const copy = await screen.findByTestId("cancel-modal-consequence-copy");
    await waitFor(() => expect(copy.dataset.variant).toBe("SECOND_OFFENSE"));
    expect(copy).toHaveTextContent(/2nd cancellation/i);
    expect(copy).toHaveTextContent(/L\$1000 penalty/i);
  });

  it("renders THIRD_OFFENSE on 2 priors with bids present", async () => {
    seedHandlers(makeMe(), makeStatus(2, "PENALTY_AND_30D", 2500));
    renderWithProviders(
      <CancelListingModal
        open
        onClose={vi.fn()}
        auction={baseAuction({ status: "ACTIVE", bidCount: 2 })}
      />,
      { auth: "authenticated" },
    );
    const copy = await screen.findByTestId("cancel-modal-consequence-copy");
    await waitFor(() => expect(copy.dataset.variant).toBe("THIRD_OFFENSE"));
    expect(copy).toHaveTextContent(/3rd cancellation/i);
    expect(copy).toHaveTextContent(/30 days/i);
    expect(copy).toHaveTextContent(/L\$2500/i);
    expect(copy).toHaveTextContent(/permanent ban/i);
  });

  it("renders FOURTH_PLUS_OFFENSE on 3 priors when not yet banned", async () => {
    seedHandlers(makeMe(), makeStatus(3, "PERMANENT_BAN"));
    renderWithProviders(
      <CancelListingModal
        open
        onClose={vi.fn()}
        auction={baseAuction({ status: "ACTIVE", bidCount: 2 })}
      />,
      { auth: "authenticated" },
    );
    const copy = await screen.findByTestId("cancel-modal-consequence-copy");
    await waitFor(() =>
      expect(copy.dataset.variant).toBe("FOURTH_PLUS_OFFENSE"),
    );
    expect(copy).toHaveTextContent(/4th cancellation/i);
    expect(copy).toHaveTextContent(/permanent ban/i);
  });
});

describe("CancelListingModal", () => {
  it("renders the refund copy derived from the auction status", () => {
    seedHandlers(makeMe(), makeStatus(0));
    renderWithProviders(
      <CancelListingModal
        open
        onClose={vi.fn()}
        auction={baseAuction({ status: "DRAFT_PAID", listingFeeAmt: 100 })}
      />,
      { auth: "authenticated" },
    );
    expect(
      screen.getByText(/Refund: L\$100.*full refund/i),
    ).toBeInTheDocument();
  });

  it("shows the ACTIVE-forfeit copy when cancelling an active listing", () => {
    seedHandlers(makeMe(), makeStatus(0));
    renderWithProviders(
      <CancelListingModal
        open
        onClose={vi.fn()}
        auction={baseAuction({ status: "ACTIVE" })}
      />,
      { auth: "authenticated" },
    );
    expect(
      screen.getByText(
        /No refund — cancelling an active listing does not refund the fee/i,
      ),
    ).toBeInTheDocument();
  });

  it("posts the cancel and redirects on success", async () => {
    push.mockClear();
    seedHandlers(makeMe(), makeStatus(0));
    let received: { reason?: string } | null = null;
    server.use(
      http.put("*/api/v1/auctions/42/cancel", async ({ request }) => {
        received = (await request.json()) as { reason?: string };
        return HttpResponse.json(baseAuction({ status: "CANCELLED" }));
      }),
    );
    const onClose = vi.fn();
    renderWithProviders(
      <CancelListingModal
        open
        onClose={onClose}
        auction={baseAuction()}
      />,
      { auth: "authenticated" },
    );
    await userEvent.click(
      screen.getByRole("button", { name: /Cancel listing/i }),
    );
    await waitFor(() => {
      expect(received).not.toBeNull();
      // No reason entered → body should be `{}`, not `{ reason: "" }`.
      expect(received?.reason).toBeUndefined();
    });
    await waitFor(() => expect(push).toHaveBeenCalledWith("/dashboard/listings"));
    expect(onClose).toHaveBeenCalled();
  });
});
