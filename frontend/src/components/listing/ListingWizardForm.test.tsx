import { describe, expect, it, vi, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { server } from "@/test/msw/server";
import { realtyGroupWalletHandlers } from "@/test/msw/handlers";
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
  ownerName: null,
  parcelName: null,
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
    publicId: "00000000-0000-0000-0000-000000000007",
    sellerPublicId: "00000000-0000-0000-0000-000000000001",
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
    winnerPublicId: null,
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
      HttpResponse.json(sellerResponse(), { status: 201 }),
    ),
    http.put("*/api/v1/auctions/00000000-0000-0000-0000-000000000007", () =>
      HttpResponse.json(sellerResponse()),
    ),
    http.get("*/api/v1/auctions/00000000-0000-0000-0000-000000000007", () =>
      HttpResponse.json(sellerResponse()),
    ),
  );
}

beforeEach(() => {
  if (typeof window !== "undefined") window.sessionStorage.clear();
  routerPush.mockReset();
  routerReplace.mockReset();
  // Default: no eligible groups. Individual tests override to test the picker.
  server.use(
    http.get("*/api/v1/realty/me/listing-eligible-groups", () =>
      HttpResponse.json([]),
    ),
  );
});

describe("ListingWizardForm (create flow)", () => {
  it("walks lookup -> settings -> save & continue and navigates to activate", async () => {
    installHappyPathHandlers();

    renderWithProviders(<ListingWizardForm mode="create" />);

    await userEvent.type(
      screen.getByLabelText(/Parcel UUID/i),
      VALID_UUID,
    );
    await userEvent.click(
      screen.getByRole("button", { name: /Look up/i }),
    );
    await screen.findByText("Beachfront retreat");
    await userEvent.type(
      screen.getByLabelText(/listing title/i),
      "Premium Waterfront",
    );

    const startingBid = await screen.findByLabelText(/Starting bid/i);
    await userEvent.clear(startingBid);
    await userEvent.type(startingBid, "500");

    await userEvent.click(
      screen.getByRole("button", { name: /Save & continue/i }),
    );

    await waitFor(() =>
      expect(routerPush).toHaveBeenCalledWith("/listings/00000000-0000-0000-0000-000000000007/activate"),
    );
  });

  it("saves as draft without navigating to activate", async () => {
    installHappyPathHandlers();

    renderWithProviders(<ListingWizardForm mode="create" />);

    await userEvent.type(
      screen.getByLabelText(/Parcel UUID/i),
      VALID_UUID,
    );
    await userEvent.click(
      screen.getByRole("button", { name: /Look up/i }),
    );
    await screen.findByText("Beachfront retreat");
    await userEvent.type(
      screen.getByLabelText(/listing title/i),
      "Premium Waterfront",
    );

    await userEvent.click(
      screen.getByRole("button", { name: /Save as Draft/i }),
    );

    // Still on the configure form — the "Save & continue" button is
    // still visible and we did NOT navigate to activate.
    await waitFor(() => {
      expect(
        screen.getByRole("button", { name: /Save & continue/i }),
      ).toBeInTheDocument();
    });
    expect(routerPush).not.toHaveBeenCalled();

    // First successful create should replace the URL to /listings/{id}/activate
    // per sub-spec 2 §4.1.4 so a refresh keeps the seller on the same auction.
    await waitFor(() =>
      expect(routerReplace).toHaveBeenCalledWith("/listings/00000000-0000-0000-0000-000000000007/activate"),
    );
  });

  it("disables save and continue until a parcel is resolved", () => {
    installHappyPathHandlers();
    renderWithProviders(<ListingWizardForm mode="create" />);
    expect(
      screen.getByRole("button", { name: /Save as Draft/i }),
    ).toBeDisabled();
    expect(
      screen.getByRole("button", { name: /Save & continue/i }),
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
      screen.getByLabelText(/Parcel UUID/i),
      VALID_UUID,
    );
    await userEvent.click(
      screen.getByRole("button", { name: /Look up/i }),
    );
    await screen.findByText("Beachfront retreat");
    await userEvent.type(
      screen.getByLabelText(/listing title/i),
      "Premium Waterfront",
    );

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
      screen.getByRole("button", { name: /save & continue/i }),
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
      screen.getByRole("button", { name: /save & continue/i }),
    );
    expect(
      await screen.findByText(/120 characters or less/i),
    ).toBeInTheDocument();
  });
});

describe("ListingWizardForm (edit flow)", () => {
  it("shows 'Save as Draft' when editing a DRAFT auction", async () => {
    server.use(
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-000000000037", () =>
        HttpResponse.json(
          sellerResponse({
            publicId: "00000000-0000-0000-0000-000000000037",
            status: "DRAFT",
            startingBid: 2500,
            sellerDesc: "Existing description",
          }),
        ),
      ),
      http.get("*/api/v1/parcel-tags", () => HttpResponse.json([])),
    );

    renderWithProviders(<ListingWizardForm mode="edit" id="00000000-0000-0000-0000-000000000037" />);

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
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-000000000038", () =>
        HttpResponse.json(
          sellerResponse({
            publicId: "00000000-0000-0000-0000-000000000038",
            status: "DRAFT_PAID",
            startingBid: 2500,
          }),
        ),
      ),
      http.get("*/api/v1/parcel-tags", () => HttpResponse.json([])),
    );

    renderWithProviders(<ListingWizardForm mode="edit" id="00000000-0000-0000-0000-000000000038" />);

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
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-00000000004d", () =>
        HttpResponse.json(
          sellerResponse({
            publicId: "00000000-0000-0000-0000-00000000004d",
            status: "ACTIVE",
            startingBid: 2500,
          }),
        ),
      ),
      http.get("*/api/v1/parcel-tags", () => HttpResponse.json([])),
    );

    renderWithProviders(<ListingWizardForm mode="edit" id="00000000-0000-0000-0000-00000000004d" />);

    await waitFor(() =>
      expect(routerReplace).toHaveBeenCalledWith("/listings/00000000-0000-0000-0000-00000000004d/activate"),
    );
  });
});

/**
 * Sub-spec 2 §8.4 — backend 403 carries a structured {@code code} field
 * on the ProblemDetail. The wizard's submit handler reads the code and
 * routes to {@link SuspensionErrorModal} per code value rather than the
 * inline {@link FormError}.
 */
describe("ListingWizardForm (suspension gate)", () => {
  async function setupAndAttemptSave() {
    renderWithProviders(<ListingWizardForm mode="create" />);

    await userEvent.type(
      screen.getByLabelText(/Parcel UUID/i),
      VALID_UUID,
    );
    await userEvent.click(
      screen.getByRole("button", { name: /Look up/i }),
    );
    await screen.findByText("Beachfront retreat");
    await userEvent.type(
      screen.getByLabelText(/listing title/i),
      "Premium Waterfront",
    );
    await userEvent.click(
      screen.getByRole("button", { name: /Save as Draft/i }),
    );
  }

  it("routes a 403 with PENALTY_OWED code to the SuspensionErrorModal", async () => {
    server.use(
      http.post("*/api/v1/parcels/lookup", () =>
        HttpResponse.json(sampleParcel),
      ),
      http.get("*/api/v1/parcel-tags", () => HttpResponse.json([])),
      http.post("*/api/v1/auctions", () =>
        HttpResponse.json(
          {
            status: 403,
            title: "Listing suspended",
            detail: "You have an outstanding penalty.",
            code: "PENALTY_OWED",
          },
          { status: 403 },
        ),
      ),
    );

    await setupAndAttemptSave();

    const modal = await screen.findByTestId("suspension-error-modal");
    expect(modal.dataset.code).toBe("PENALTY_OWED");
    expect(modal).toHaveTextContent(/penalty owed/i);
    // Inline error should be suppressed when the focused modal owns
    // the surface area.
    expect(
      screen.queryByText(/You have an outstanding penalty\./i),
    ).not.toBeInTheDocument();
  });

  it("routes a 403 with TIMED_SUSPENSION code", async () => {
    server.use(
      http.post("*/api/v1/parcels/lookup", () =>
        HttpResponse.json(sampleParcel),
      ),
      http.get("*/api/v1/parcel-tags", () => HttpResponse.json([])),
      http.post("*/api/v1/auctions", () =>
        HttpResponse.json(
          {
            status: 403,
            title: "Listing suspended",
            detail: "Suspended.",
            code: "TIMED_SUSPENSION",
          },
          { status: 403 },
        ),
      ),
    );

    await setupAndAttemptSave();

    const modal = await screen.findByTestId("suspension-error-modal");
    expect(modal.dataset.code).toBe("TIMED_SUSPENSION");
    expect(modal).toHaveTextContent(/temporarily suspended/i);
  });

  it("routes a 403 with PERMANENT_BAN code", async () => {
    server.use(
      http.post("*/api/v1/parcels/lookup", () =>
        HttpResponse.json(sampleParcel),
      ),
      http.get("*/api/v1/parcel-tags", () => HttpResponse.json([])),
      http.post("*/api/v1/auctions", () =>
        HttpResponse.json(
          {
            status: 403,
            title: "Listing suspended",
            detail: "Banned.",
            code: "PERMANENT_BAN",
          },
          { status: 403 },
        ),
      ),
    );

    await setupAndAttemptSave();

    const modal = await screen.findByTestId("suspension-error-modal");
    expect(modal.dataset.code).toBe("PERMANENT_BAN");
    expect(modal).toHaveTextContent(/permanently suspended/i);
    expect(modal).toHaveTextContent(/contact support/i);
  });

  it("falls through to the inline FormError on a 403 without a recognised code", async () => {
    server.use(
      http.post("*/api/v1/parcels/lookup", () =>
        HttpResponse.json(sampleParcel),
      ),
      http.get("*/api/v1/parcel-tags", () => HttpResponse.json([])),
      http.post("*/api/v1/auctions", () =>
        HttpResponse.json(
          {
            status: 403,
            title: "Forbidden",
            detail: "Access denied.",
            code: "OTHER_ROUTE",
          },
          { status: 403 },
        ),
      ),
    );

    await setupAndAttemptSave();

    expect(
      await screen.findByText(/Access denied\./i),
    ).toBeInTheDocument();
    expect(
      screen.queryByTestId("suspension-error-modal"),
    ).not.toBeInTheDocument();
  });
});

describe("ListingWizardForm — List-as picker", () => {
  it("renders the picker when listing-eligible-groups returns at least one group", async () => {
    server.use(
      http.get("*/api/v1/realty/me/listing-eligible-groups", () =>
        HttpResponse.json([
          {
            publicId: "g1",
            name: "Sunset Realty",
            slug: "sunset",
            logoUrl: null,
            agentCommissionRate: 0.02,
          },
        ]),
      ),
      http.post("*/api/v1/parcels/lookup", () => HttpResponse.json(sampleParcel)),
      http.get("*/api/v1/parcel-tags", () => HttpResponse.json([])),
    );

    renderWithProviders(<ListingWizardForm mode="create" />);

    // Resolve a parcel so the parcel-gated form body renders.
    await userEvent.type(screen.getByLabelText(/Parcel UUID/i), VALID_UUID);
    await userEvent.click(screen.getByRole("button", { name: /Look up/i }));
    await screen.findByText("Beachfront retreat");

    // The picker should now appear.
    expect(await screen.findByText(/Sunset Realty/i)).toBeInTheDocument();
  });

  it("does NOT render the picker when listing-eligible-groups returns empty", async () => {
    // Default beforeEach handler already returns []; just install parcel lookup.
    server.use(
      http.post("*/api/v1/parcels/lookup", () => HttpResponse.json(sampleParcel)),
      http.get("*/api/v1/parcel-tags", () => HttpResponse.json([])),
    );

    renderWithProviders(<ListingWizardForm mode="create" />);

    await userEvent.type(screen.getByLabelText(/Parcel UUID/i), VALID_UUID);
    await userEvent.click(screen.getByRole("button", { name: /Look up/i }));
    await screen.findByText("Beachfront retreat");

    // Give the eligible-groups query a moment to resolve.
    await waitFor(() => {
      expect(screen.queryByText(/List as/i)).not.toBeInTheDocument();
    });
  });

  it("does NOT render the picker in edit mode even if groups are returned", async () => {
    server.use(
      http.get("*/api/v1/realty/me/listing-eligible-groups", () =>
        HttpResponse.json([
          {
            publicId: "g1",
            name: "Sunset Realty",
            slug: "sunset",
            logoUrl: null,
            agentCommissionRate: 0.02,
          },
        ]),
      ),
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-000000000037", () =>
        HttpResponse.json(
          sellerResponse({
            publicId: "00000000-0000-0000-0000-000000000037",
            status: "DRAFT",
            startingBid: 2500,
          }),
        ),
      ),
      http.get("*/api/v1/parcel-tags", () => HttpResponse.json([])),
    );

    renderWithProviders(
      <ListingWizardForm mode="edit" id="00000000-0000-0000-0000-000000000037" />,
    );

    await screen.findByText("Beachfront retreat");

    // The picker must never appear in edit mode.
    await waitFor(() => {
      expect(screen.queryByText(/List as/i)).not.toBeInTheDocument();
    });
  });

  it("posts listAsGroupPublicId when a group is chosen and form is submitted", async () => {
    const user = userEvent.setup();
    let capturedBody: Record<string, unknown> | null = null;

    server.use(
      http.get("*/api/v1/realty/me/listing-eligible-groups", () =>
        HttpResponse.json([
          {
            publicId: "g1",
            name: "Sunset Realty",
            slug: "sunset",
            logoUrl: null,
            agentCommissionRate: 0.02,
          },
        ]),
      ),
      // Sufficient balance so AgentCommissionPreview does not disable the submit button.
      realtyGroupWalletHandlers.walletSuccess("g1", { balance: 10000, reserved: 0, available: 10000 }),
      http.post("*/api/v1/parcels/lookup", () => HttpResponse.json(sampleParcel)),
      http.get("*/api/v1/parcel-tags", () => HttpResponse.json([])),
      http.post("*/api/v1/auctions", async ({ request }) => {
        capturedBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(sellerResponse(), { status: 201 });
      }),
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-000000000007", () =>
        HttpResponse.json(sellerResponse()),
      ),
    );

    renderWithProviders(<ListingWizardForm mode="create" />);

    // Resolve a parcel.
    await user.type(screen.getByLabelText(/Parcel UUID/i), VALID_UUID);
    await user.click(screen.getByRole("button", { name: /Look up/i }));
    await screen.findByText("Beachfront retreat");

    // Wait for the picker to appear, then select the group.
    await user.click(await screen.findByText(/Sunset Realty/i));

    // Fill in the required title.
    await user.type(screen.getByLabelText(/listing title/i), "Premium Waterfront");

    // Submit.
    await user.click(screen.getByRole("button", { name: /Save & continue/i }));

    await waitFor(() => expect(capturedBody).toBeTruthy());
    expect(capturedBody!.listAsGroupPublicId).toBe("g1");
  });

  // Realty Groups: E §6.1 — when the parcel is SL-group-owned, the picker
  // omits Individual (you can't personally own group-owned land) and
  // auto-selects the first eligible realty group so the form is never in
  // an invalid "no group, no Individual" state.
  it("auto-selects the first eligible group when the parcel is SL-group-owned (no Individual option)", async () => {
    const groupOwnedParcel: ParcelDto = {
      ...sampleParcel,
      ownerType: "group",
    };
    server.use(
      http.get("*/api/v1/realty/me/listing-eligible-groups", () =>
        HttpResponse.json([
          {
            publicId: "g1",
            name: "Sunset Realty",
            slug: "sunset",
            logoUrl: null,
            agentCommissionRate: 0.02,
          },
        ]),
      ),
      http.post("*/api/v1/parcels/lookup", () => HttpResponse.json(groupOwnedParcel)),
      http.get("*/api/v1/parcel-tags", () => HttpResponse.json([])),
      http.get("*/api/v1/realty-groups/g1", () =>
        HttpResponse.json({
          publicId: "g1",
          name: "Sunset Realty",
          slug: "sunset",
          description: null,
          website: null,
          logoUrl: null,
          coverUrl: null,
          memberSince: "2026-01-01T00:00:00Z",
          leader: {
            userPublicId: "leader",
            displayName: "Leader",
            avatarUrl: null,
          },
          agents: [
            {
              memberPublicId: "m1",
              userPublicId: "00000000-0000-0000-0000-00000000002a",
              displayName: "Caller",
              avatarUrl: null,
              role: "AGENT",
              permissions: ["CREATE_LISTING"],
              joinedAt: "2026-02-01T00:00:00Z",
              agentCommissionRate: 0.1,
            },
          ],
          memberSeatLimit: 25,
          memberCount: 2,
        }),
      ),
      http.get("*/api/v1/realty/groups/g1/wallet", () =>
        HttpResponse.json({
          balance: 10000,
          reserved: 0,
          available: 10000,
          recentLedger: [],
        }),
      ),
    );

    renderWithProviders(<ListingWizardForm mode="create" />, {
      auth: "authenticated",
    });

    await userEvent.type(screen.getByLabelText(/Parcel UUID/i), VALID_UUID);
    await userEvent.click(screen.getByRole("button", { name: /Look up/i }));
    await screen.findByText("Beachfront retreat");

    // Picker should appear with Sunset Realty as the (auto-selected) only
    // option. Individual must not be in the DOM.
    await screen.findByLabelText(/Sunset Realty/i);
    expect(screen.queryByLabelText(/Individual/i)).not.toBeInTheDocument();
  });

  it("renders the case-3 AgentCommissionPreview when the parcel is SL-group-owned", async () => {
    const groupOwnedParcel: ParcelDto = {
      ...sampleParcel,
      ownerType: "group",
    };
    server.use(
      http.get("*/api/v1/realty/me/listing-eligible-groups", () =>
        HttpResponse.json([
          {
            publicId: "g1",
            name: "Sunset Realty",
            slug: "sunset",
            logoUrl: null,
            agentCommissionRate: 0.02,
          },
        ]),
      ),
      http.post("*/api/v1/parcels/lookup", () => HttpResponse.json(groupOwnedParcel)),
      http.get("*/api/v1/parcel-tags", () => HttpResponse.json([])),
      http.get("*/api/v1/realty-groups/g1", () =>
        HttpResponse.json({
          publicId: "g1",
          name: "Sunset Realty",
          slug: "sunset",
          description: null,
          website: null,
          logoUrl: null,
          coverUrl: null,
          memberSince: "2026-01-01T00:00:00Z",
          leader: {
            userPublicId: "leader",
            displayName: "Leader",
            avatarUrl: null,
          },
          agents: [
            {
              memberPublicId: "m1",
              userPublicId: "00000000-0000-0000-0000-00000000002a",
              displayName: "Caller",
              avatarUrl: null,
              role: "AGENT",
              permissions: ["CREATE_LISTING"],
              joinedAt: "2026-02-01T00:00:00Z",
              agentCommissionRate: 0.1,
            },
          ],
          memberSeatLimit: 25,
          memberCount: 2,
        }),
      ),
      http.get("*/api/v1/realty/groups/g1/wallet", () =>
        HttpResponse.json({
          balance: 10000,
          reserved: 0,
          available: 10000,
          recentLedger: [],
        }),
      ),
    );

    renderWithProviders(<ListingWizardForm mode="create" />, {
      auth: "authenticated",
    });

    await userEvent.type(screen.getByLabelText(/Parcel UUID/i), VALID_UUID);
    await userEvent.click(screen.getByRole("button", { name: /Look up/i }));
    await screen.findByText("Beachfront retreat");

    // The starting bid prefilled at 500 from sellerResponse default; the
    // wizard's AuctionSettingsForm will load the same default. Verify the
    // case-3 preview renders with the L$/commission split copy.
    expect(
      await screen.findByTestId("agent-commission-preview"),
    ).toBeInTheDocument();
  });

  // Realty Groups: G — case-1 ("agent listing their own land under a group")
  // is gone. The wizard now renders AgentCommissionPreview unconditionally
  // whenever an eligible group is selected and startingBid > 0, regardless
  // of whether the parcel is SL-group-owned.
  it("renders AgentCommissionPreview for a non-SL-group-owned parcel once a group is picked (post-G — case-1 removed)", async () => {
    const user = userEvent.setup();

    server.use(
      http.get("*/api/v1/realty/me/listing-eligible-groups", () =>
        HttpResponse.json([
          {
            publicId: "g1",
            name: "Sunset Realty",
            slug: "sunset",
            logoUrl: null,
            agentCommissionRate: 0.02,
          },
        ]),
      ),
      realtyGroupWalletHandlers.walletSuccess("g1", {
        balance: 10000,
        reserved: 0,
        available: 10000,
      }),
      http.post("*/api/v1/parcels/lookup", () => HttpResponse.json(sampleParcel)),
      http.get("*/api/v1/parcel-tags", () => HttpResponse.json([])),
    );

    renderWithProviders(<ListingWizardForm mode="create" />, {
      auth: "authenticated",
    });

    await user.type(screen.getByLabelText(/Parcel UUID/i), VALID_UUID);
    await user.click(screen.getByRole("button", { name: /Look up/i }));
    await screen.findByText("Beachfront retreat");

    // Parcel ownerType="agent" (default sampleParcel) -- pre-G this would
    // have rendered AgentFeePreview. Post-G the wizard renders the
    // commission preview regardless.
    await user.click(await screen.findByText(/Sunset Realty/i));

    // Default seller fixture starts at L$500 starting bid — the preview gates
    // on startingBid > 0, which the AuctionSettingsForm provides out of the box.
    expect(
      await screen.findByTestId("agent-commission-preview"),
    ).toBeInTheDocument();
  });

  it("posts listAsGroupPublicId as null when Individual is selected", async () => {
    const user = userEvent.setup();
    let capturedBody: Record<string, unknown> | null = null;

    server.use(
      http.get("*/api/v1/realty/me/listing-eligible-groups", () =>
        HttpResponse.json([
          {
            publicId: "g1",
            name: "Sunset Realty",
            slug: "sunset",
            logoUrl: null,
            agentCommissionRate: 0.02,
          },
        ]),
      ),
      http.post("*/api/v1/parcels/lookup", () => HttpResponse.json(sampleParcel)),
      http.get("*/api/v1/parcel-tags", () => HttpResponse.json([])),
      http.post("*/api/v1/auctions", async ({ request }) => {
        capturedBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(sellerResponse(), { status: 201 });
      }),
      http.get("*/api/v1/auctions/00000000-0000-0000-0000-000000000007", () =>
        HttpResponse.json(sellerResponse()),
      ),
    );

    renderWithProviders(<ListingWizardForm mode="create" />);

    await user.type(screen.getByLabelText(/Parcel UUID/i), VALID_UUID);
    await user.click(screen.getByRole("button", { name: /Look up/i }));
    await screen.findByText("Beachfront retreat");

    // Wait for picker, then explicitly pick Individual (the default).
    await screen.findByText(/Sunset Realty/i);
    await user.click(screen.getByLabelText(/Individual/i));

    await user.type(screen.getByLabelText(/listing title/i), "Premium Waterfront");
    await user.click(screen.getByRole("button", { name: /Save & continue/i }));

    await waitFor(() => expect(capturedBody).toBeTruthy());
    expect(capturedBody!.listAsGroupPublicId).toBeNull();
  });
});
