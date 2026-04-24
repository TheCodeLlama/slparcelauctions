import { describe, expect, it, vi, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import type { ParcelDto } from "@/types/parcel";
import type { SellerAuctionResponse } from "@/types/auction";
import { ListingWizardForm } from "./ListingWizardForm";

const routerPush = vi.fn();
const routerReplace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push: routerPush,
    replace: routerReplace,
    back: vi.fn(),
    refresh: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  usePathname: () => "/listings/create",
  useSearchParams: () => new URLSearchParams(),
}));

const VALID_UUID = "00000000-0000-0000-0000-000000000001";

const sampleParcel: ParcelDto = {
  id: 42,
  slParcelUuid: VALID_UUID,
  ownerUuid: "aaaa1111-0000-0000-0000-000000000000",
  ownerType: "agent",
  regionName: "Heterocera",
  gridX: 1000,
  gridY: 1000,
  positionX: 128,
  positionY: 128,
  positionZ: 0,
  continentName: "Heterocera",
  areaSqm: 1024,
  description: "Beachfront retreat",
  snapshotUrl: null,
  slurl: "secondlife://Heterocera/128/128/25",
  maturityRating: "GENERAL",
  verified: false,
  verifiedAt: null,
  lastChecked: null,
  createdAt: "2026-04-17T00:00:00Z",
};

function sellerResponse(
  overrides: Partial<SellerAuctionResponse> = {},
): SellerAuctionResponse {
  return {
    id: 7,
    sellerId: 1,
    title: "Featured Parcel Listing",
    parcel: sampleParcel,
    status: "DRAFT",
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
    listingFeePaid: false,
    listingFeeAmt: null,
    listingFeeTxn: null,
    listingFeePaidAt: null,
    commissionRate: 0.05,
    commissionAmt: null,
    createdAt: "2026-04-17T00:00:00Z",
    updatedAt: "2026-04-17T00:00:00Z",
    ...overrides,
  };
}

function installHappyPathHandlers() {
  server.use(
    http.post("*/api/v1/parcels/lookup", () =>
      HttpResponse.json(sampleParcel),
    ),
    http.get("*/api/v1/parcel-tags", () => HttpResponse.json([])),
    http.post("*/api/v1/auctions", () =>
      HttpResponse.json(sellerResponse({ id: 7 }), { status: 201 }),
    ),
    http.put("*/api/v1/auctions/7", () =>
      HttpResponse.json(sellerResponse({ id: 7 })),
    ),
    http.get("*/api/v1/auctions/7", () =>
      HttpResponse.json(sellerResponse({ id: 7 })),
    ),
  );
}

beforeEach(() => {
  if (typeof window !== "undefined") window.sessionStorage.clear();
  routerPush.mockReset();
  routerReplace.mockReset();
});

describe("ListingWizardForm (create flow)", () => {
  it("walks lookup -> settings -> review -> submit and navigates to activate", async () => {
    installHappyPathHandlers();

    renderWithProviders(<ListingWizardForm mode="create" />);

    await userEvent.type(
      screen.getByLabelText(/listing title/i),
      "Premium Waterfront",
    );
    await userEvent.type(
      screen.getByLabelText(/Parcel UUID/i),
      VALID_UUID,
    );
    await userEvent.click(
      screen.getByRole("button", { name: /Look up/i }),
    );
    await screen.findByText("Beachfront retreat");

    const startingBid = await screen.findByLabelText(/Starting bid/i);
    await userEvent.clear(startingBid);
    await userEvent.type(startingBid, "500");

    await userEvent.click(
      screen.getByRole("button", { name: /Continue to Review/i }),
    );

    await screen.findByText(/Preview — this is how your listing/i);

    await userEvent.click(screen.getByRole("button", { name: /^Submit$/ }));

    await waitFor(() =>
      expect(routerPush).toHaveBeenCalledWith("/listings/7/activate"),
    );
  });

  it("saves as draft without advancing to Review", async () => {
    installHappyPathHandlers();

    renderWithProviders(<ListingWizardForm mode="create" />);

    await userEvent.type(
      screen.getByLabelText(/listing title/i),
      "Premium Waterfront",
    );
    await userEvent.type(
      screen.getByLabelText(/Parcel UUID/i),
      VALID_UUID,
    );
    await userEvent.click(
      screen.getByRole("button", { name: /Look up/i }),
    );
    await screen.findByText("Beachfront retreat");

    await userEvent.click(
      screen.getByRole("button", { name: /Save as Draft/i }),
    );

    // Still on the Configure step — the Parcel heading is visible and
    // the Review banner is not.
    await waitFor(() => {
      expect(
        screen.getByRole("button", { name: /Continue to Review/i }),
      ).toBeInTheDocument();
    });
    expect(
      screen.queryByText(/Preview — this is how your listing/i),
    ).toBeNull();
    expect(routerPush).not.toHaveBeenCalled();

    // First successful create should replace the URL to /listings/{id}/edit
    // per sub-spec 2 §4.1.4 so a refresh keeps the seller on the same auction.
    await waitFor(() =>
      expect(routerReplace).toHaveBeenCalledWith("/listings/7/edit"),
    );
  });

  it("disables save and continue until a parcel is resolved", () => {
    installHappyPathHandlers();
    renderWithProviders(<ListingWizardForm mode="create" />);
    expect(
      screen.getByRole("button", { name: /Save as Draft/i }),
    ).toBeDisabled();
    expect(
      screen.getByRole("button", { name: /Continue to Review/i }),
    ).toBeDisabled();
  });

  it("surfaces the server error message when save fails", async () => {
    server.use(
      http.post("*/api/v1/parcels/lookup", () =>
        HttpResponse.json(sampleParcel),
      ),
      http.get("*/api/v1/parcel-tags", () => HttpResponse.json([])),
      http.post("*/api/v1/auctions", () =>
        HttpResponse.json(
          { status: 400, title: "Bad Request", detail: "Starting bid too low." },
          { status: 400 },
        ),
      ),
    );

    renderWithProviders(<ListingWizardForm mode="create" />);
    await userEvent.type(
      screen.getByLabelText(/listing title/i),
      "Premium Waterfront",
    );
    await userEvent.type(
      screen.getByLabelText(/Parcel UUID/i),
      VALID_UUID,
    );
    await userEvent.click(
      screen.getByRole("button", { name: /Look up/i }),
    );
    await screen.findByText("Beachfront retreat");

    await userEvent.click(
      screen.getByRole("button", { name: /Save as Draft/i }),
    );

    expect(
      await screen.findByText(/Starting bid too low/i),
    ).toBeInTheDocument();
  });

  it("requires a non-empty title under 120 chars", async () => {
    installHappyPathHandlers();

    renderWithProviders(<ListingWizardForm mode="create" />, {
      auth: "authenticated",
    });

    // Resolve a parcel so the submit button becomes enabled.
    await userEvent.type(
      screen.getByLabelText(/Parcel UUID/i),
      VALID_UUID,
    );
    await userEvent.click(
      screen.getByRole("button", { name: /Look up/i }),
    );
    await screen.findByText("Beachfront retreat");

    // Submit without a title → inline validation error surfaces.
    await userEvent.click(
      screen.getByRole("button", { name: /continue to review/i }),
    );
    expect(
      await screen.findByText(/title is required/i),
    ).toBeInTheDocument();

    const titleInput = screen.getByLabelText(/listing title/i);
    await userEvent.type(titleInput, "Premium Waterfront");
    expect(screen.getByText("18 / 120")).toBeInTheDocument();

    await userEvent.clear(titleInput);
    await userEvent.type(titleInput, "x".repeat(121));
    await userEvent.click(
      screen.getByRole("button", { name: /continue to review/i }),
    );
    expect(
      await screen.findByText(/120 characters or less/i),
    ).toBeInTheDocument();
  });
});

describe("ListingWizardForm (edit flow)", () => {
  it("shows 'Save as Draft' when editing a DRAFT auction", async () => {
    server.use(
      http.get("*/api/v1/auctions/55", () =>
        HttpResponse.json(
          sellerResponse({
            id: 55,
            status: "DRAFT",
            startingBid: 2500,
            sellerDesc: "Existing description",
          }),
        ),
      ),
      http.get("*/api/v1/parcel-tags", () => HttpResponse.json([])),
    );

    renderWithProviders(<ListingWizardForm mode="edit" id={55} />);

    await screen.findByText("Beachfront retreat");
    expect(
      screen.queryByRole("button", { name: /Look up/i }),
    ).not.toBeInTheDocument();

    const startingBid = await screen.findByLabelText(/Starting bid/i);
    expect(startingBid).toHaveValue(2500);

    // Still a draft — button stays "Save as Draft", not "Save changes".
    expect(
      screen.getByRole("button", { name: /Save as Draft/i }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /Save changes/i }),
    ).not.toBeInTheDocument();
  });

  it("shows 'Save changes' when editing a DRAFT_PAID auction", async () => {
    server.use(
      http.get("*/api/v1/auctions/56", () =>
        HttpResponse.json(
          sellerResponse({
            id: 56,
            status: "DRAFT_PAID",
            startingBid: 2500,
          }),
        ),
      ),
      http.get("*/api/v1/parcel-tags", () => HttpResponse.json([])),
    );

    renderWithProviders(<ListingWizardForm mode="edit" id={56} />);

    await screen.findByText("Beachfront retreat");

    // Fee paid — button label flips to "Save changes".
    expect(
      screen.getByRole("button", { name: /Save changes/i }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /Save as Draft/i }),
    ).not.toBeInTheDocument();
  });

  it("redirects to activate when editing an auction past DRAFT_PAID", async () => {
    server.use(
      http.get("*/api/v1/auctions/77", () =>
        HttpResponse.json(
          sellerResponse({
            id: 77,
            status: "ACTIVE",
            startingBid: 2500,
          }),
        ),
      ),
      http.get("*/api/v1/parcel-tags", () => HttpResponse.json([])),
    );

    renderWithProviders(<ListingWizardForm mode="edit" id={77} />);

    await waitFor(() =>
      expect(routerReplace).toHaveBeenCalledWith("/listings/77/activate"),
    );
  });
});
