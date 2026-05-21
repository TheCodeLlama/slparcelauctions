import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
  within,
} from "@/test/render";
import { ApiError, type ProblemDetail } from "@/lib/api";
import type { CreateCouponRequest } from "@/types/coupon";
import type { AdminUserSummary } from "@/lib/admin/types";

// next/navigation must be mocked to keep the page-level hooks happy
// even though the form itself never touches the router directly.
vi.mock("next/navigation", () => ({
  usePathname: vi.fn(() => "/admin/coupons/new"),
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  useSearchParams: vi.fn(() => new URLSearchParams()),
}));

// Mock the create mutation hook so tests can assert the request body
// shape without driving fetch/MSW.
const mutateMock = vi.fn();
let mutationState: {
  isPending: boolean;
  isError: boolean;
  error: unknown;
} = { isPending: false, isError: false, error: null };

vi.mock("@/hooks/admin/useCreateAdminCoupon", () => ({
  useCreateAdminCoupon: () => ({
    mutate: mutateMock,
    isPending: mutationState.isPending,
    isError: mutationState.isError,
    error: mutationState.error,
  }),
}));

// Mock the user-search autocomplete to a tiny "click to add" stub so
// the allowed-users flow is testable without MSW debouncing.
const TEST_USER: AdminUserSummary = {
  publicId: "00000000-0000-0000-0000-0000000000a1",
  username: "alice",
  email: "alice@example.com",
  displayName: "Alice",
  slAvatarUuid: null,
  slDisplayName: null,
  role: "USER",
  verified: true,
  hasActiveBan: false,
  completedSales: 0,
  cancelledWithBids: 0,
  createdAt: "2026-01-01T00:00:00Z",
};

vi.mock("@/components/admin/bans/UserSearchAutocomplete", () => ({
  UserSearchAutocomplete: ({
    onSelect,
  }: {
    onSelect: (u: AdminUserSummary) => void;
  }) => (
    <button
      type="button"
      data-testid="mock-user-pick"
      onClick={() => onSelect(TEST_USER)}
    >
      pick test user
    </button>
  ),
}));

import { AdminCouponForm } from "./AdminCouponForm";

function resetMutation() {
  mutateMock.mockReset();
  mutationState = { isPending: false, isError: false, error: null };
}

describe("<AdminCouponForm />", () => {
  beforeEach(() => {
    resetMutation();
  });

  it("renders all six sections", () => {
    renderWithProviders(<AdminCouponForm />);
    expect(screen.getByText("Identity")).toBeInTheDocument();
    expect(screen.getByText("Discount bundle")).toBeInTheDocument();
    expect(screen.getByText("Lifetime")).toBeInTheDocument();
    expect(screen.getByText("Redemption controls")).toBeInTheDocument();
    expect(screen.getByText("Auto-grant signup window")).toBeInTheDocument();
    expect(screen.getByText("Status")).toBeInTheDocument();
  });

  it("starts with one discount row and adds another on click", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AdminCouponForm />);
    expect(screen.getByTestId("discount-row-0")).toBeInTheDocument();
    expect(screen.queryByTestId("discount-row-1")).not.toBeInTheDocument();

    await user.click(screen.getByTestId("discount-add-btn"));

    expect(screen.getByTestId("discount-row-0")).toBeInTheDocument();
    expect(screen.getByTestId("discount-row-1")).toBeInTheDocument();
  });

  it("disables remove on the only row, enables it once a second is added", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AdminCouponForm />);

    expect(screen.getByTestId("discount-remove-0")).toBeDisabled();
    await user.click(screen.getByTestId("discount-add-btn"));
    expect(screen.getByTestId("discount-remove-0")).not.toBeDisabled();

    await user.click(screen.getByTestId("discount-remove-0"));
    expect(screen.queryByTestId("discount-row-1")).not.toBeInTheDocument();
    expect(screen.getByTestId("discount-row-0")).toBeInTheDocument();
  });

  it("generate button populates the code input", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AdminCouponForm />);
    const codeInput = screen.getByTestId("coupon-code-input") as HTMLInputElement;
    expect(codeInput.value).toBe("");

    await user.click(screen.getByTestId("coupon-generate-btn"));

    expect(codeInput.value.length).toBeGreaterThanOrEqual(1);
    expect(codeInput.value).toMatch(/^[A-Z0-9]+$/);
  });

  it("flags empty duration AND empty use_count as a lifetime error", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AdminCouponForm />);

    // Fill code + a discount value so other validations pass.
    await user.type(screen.getByTestId("coupon-code-input"), "WELCOME");
    await user.type(screen.getByTestId("discount-value-0"), "10");

    await user.click(screen.getByTestId("coupon-submit-btn"));

    expect(screen.getByTestId("lifetime-error")).toHaveTextContent(
      /duration .* or use count/i,
    );
    expect(mutateMock).not.toHaveBeenCalled();
  });

  it("flags partial signup-window (only start set) as paired error", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AdminCouponForm />);

    await user.type(screen.getByTestId("coupon-code-input"), "WELCOME");
    await user.type(screen.getByTestId("discount-value-0"), "10");
    await user.type(screen.getByTestId("duration-days-input"), "30");
    await user.type(screen.getByTestId("signup-start-input"), "2026-01-01");

    await user.click(screen.getByTestId("coupon-submit-btn"));

    expect(screen.getByTestId("signup-window-error")).toHaveTextContent(
      /both a start and end date/i,
    );
    expect(mutateMock).not.toHaveBeenCalled();
  });

  it("submits with a correctly shaped request body", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AdminCouponForm />);

    await user.type(screen.getByTestId("coupon-code-input"), "WELCOME30");
    await user.type(
      screen.getByTestId("coupon-description-input"),
      "Spring promo",
    );

    // Discount row 0: keep target=LISTING_FEE, change op to PERCENT_OFF,
    // value=30
    await user.selectOptions(screen.getByTestId("discount-op-0"), "PERCENT_OFF");
    await user.type(screen.getByTestId("discount-value-0"), "30");

    // Add a second row for COMMISSION_RATE / OVERRIDE / 0.02
    await user.click(screen.getByTestId("discount-add-btn"));
    await user.selectOptions(
      screen.getByTestId("discount-target-1"),
      "COMMISSION_RATE",
    );
    await user.selectOptions(screen.getByTestId("discount-op-1"), "OVERRIDE");
    await user.type(screen.getByTestId("discount-value-1"), "0.02");

    // Lifetime
    await user.type(screen.getByTestId("duration-days-input"), "30");
    await user.type(screen.getByTestId("use-count-input"), "1");

    // Redemption controls
    await user.type(screen.getByTestId("max-total-input"), "100");
    await user.clear(screen.getByTestId("max-per-user-input"));
    await user.type(screen.getByTestId("max-per-user-input"), "2");

    // Allowed users via mocked autocomplete.
    await user.click(screen.getByTestId("mock-user-pick"));
    expect(screen.getByTestId("allowed-users-list")).toBeInTheDocument();

    // Signup window paired.
    await user.type(screen.getByTestId("signup-start-input"), "2026-01-01");
    await user.type(screen.getByTestId("signup-end-input"), "2026-12-31");

    await user.click(screen.getByTestId("coupon-submit-btn"));

    await waitFor(() => expect(mutateMock).toHaveBeenCalledTimes(1));
    const body = mutateMock.mock.calls[0][0] as CreateCouponRequest;

    expect(body.code).toBe("WELCOME30");
    expect(body.description).toBe("Spring promo");
    expect(body.durationDays).toBe(30);
    expect(body.useCount).toBe(1);
    expect(body.maxTotalRedemptions).toBe(100);
    expect(body.maxPerUser).toBe(2);
    expect(body.active).toBe(true);
    expect(body.notifyOnGrant).toBe(true);
    expect(body.signupWindowStart).toBe("2026-01-01");
    expect(body.signupWindowEnd).toBe("2026-12-31");
    expect(body.allowedUserPublicIds).toEqual([
      "00000000-0000-0000-0000-0000000000a1",
    ]);
    expect(body.discounts).toEqual([
      {
        target: "LISTING_FEE",
        op: "PERCENT_OFF",
        value: "30",
        sortOrder: 0,
      },
      {
        target: "COMMISSION_RATE",
        op: "OVERRIDE",
        value: "0.02",
        sortOrder: 1,
      },
    ]);
  });

  it("renders a server error banner with the IMMUTABLE_FIELD code message", () => {
    const problem: ProblemDetail = {
      status: 409,
      title: "Conflict",
      detail: "code already exists",
      code: "IMMUTABLE_FIELD",
    };
    mutationState = {
      isPending: false,
      isError: true,
      error: new ApiError(problem),
    };

    renderWithProviders(<AdminCouponForm />);

    const banner = screen.getByTestId("form-error");
    expect(banner).toHaveTextContent(/coupon with that code already exists/i);
  });

  it("renders a generic message when the API error has no known code", () => {
    const problem: ProblemDetail = {
      status: 500,
      title: "Server error",
      detail: "boom",
    };
    mutationState = {
      isPending: false,
      isError: true,
      error: new ApiError(problem),
    };

    renderWithProviders(<AdminCouponForm />);

    expect(screen.getByTestId("form-error")).toHaveTextContent("boom");
  });

  it("disables submit while the mutation is pending", () => {
    mutationState = { isPending: true, isError: false, error: null };
    renderWithProviders(<AdminCouponForm />);
    const btn = screen.getByTestId("coupon-submit-btn");
    expect(btn).toBeDisabled();
  });

  it("removes an allowed user when its chip remove button is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AdminCouponForm />);

    await user.click(screen.getByTestId("mock-user-pick"));
    const list = screen.getByTestId("allowed-users-list");
    const chip = within(list).getByTestId(
      `allowed-user-chip-${TEST_USER.publicId}`,
    );
    expect(chip).toBeInTheDocument();

    await user.click(within(chip).getByRole("button", { name: /remove alice/i }));

    expect(screen.queryByTestId("allowed-users-list")).not.toBeInTheDocument();
  });

  it("renders an inline explainer under the Use count input", () => {
    renderWithProviders(<AdminCouponForm />);
    const hint = screen.getByTestId("use-count-hint");
    expect(hint).toHaveTextContent(/how many listings this coupon can discount/i);
    expect(hint).toHaveTextContent(/leave blank for unlimited uses/i);
  });

  it("opens the discount help modal when Help is clicked and closes it on Close", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AdminCouponForm />);

    // Help link is closed by default; the table is not in the DOM.
    expect(screen.queryByTestId("discount-help-table")).not.toBeInTheDocument();

    await user.click(screen.getByTestId("discount-help-btn"));

    const table = await screen.findByTestId("discount-help-table");
    expect(table).toBeInTheDocument();
    // Spot-check the value-format guidance the user asked for: percentages
    // should be entered as whole numbers (50 means 50%, not 0.5). The
    // "enter 50, not 0.5" string appears in two rows (listing fee % off
    // and commission % off), so match length >= 2 with getAllByText.
    expect(
      within(table).getAllByText(/enter 50, not 0\.5/i).length,
    ).toBeGreaterThanOrEqual(2);
    // OVERRIDE row for commission should clarify it's a percent, not a rate.
    expect(
      within(table).getByText(/enter 3, not 0\.03/i),
    ).toBeInTheDocument();

    await user.click(screen.getByTestId("discount-help-close"));
    await waitFor(() =>
      expect(screen.queryByTestId("discount-help-table")).not.toBeInTheDocument(),
    );
  });
});
