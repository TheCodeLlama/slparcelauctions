import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { ApiError } from "@/lib/api";
import type {
  CouponGrantDto,
  CouponRedemptionErrorCode,
} from "@/types/coupon";

vi.mock("@/hooks/useMyCoupons", () => ({
  useMyCoupons: vi.fn(),
}));

vi.mock("@/hooks/useRedeemCoupon", () => ({
  useRedeemCoupon: vi.fn(),
}));

import { useMyCoupons } from "@/hooks/useMyCoupons";
import { useRedeemCoupon } from "@/hooks/useRedeemCoupon";
import { WalletCouponsCard } from "./WalletCouponsCard";

function grant(overrides: Partial<CouponGrantDto> = {}): CouponGrantDto {
  return {
    publicId: "00000000-0000-0000-0000-0000000000a1",
    couponPublicId: "00000000-0000-0000-0000-0000000000b1",
    code: "WELCOME10",
    grantedAt: "2026-05-10T10:00:00Z",
    expiresAt: null,
    remainingCount: null,
    state: "ACTIVE",
    source: "REDEMPTION",
    discounts: [
      { target: "LISTING_FEE", op: "OVERRIDE", value: "0", sortOrder: 0 },
    ],
    ...overrides,
  };
}

type CouponsHookReturn = ReturnType<typeof useMyCoupons>;
type RedeemHookReturn = ReturnType<typeof useRedeemCoupon>;

function setCoupons({
  active = [],
  history = [],
}: { active?: CouponGrantDto[]; history?: CouponGrantDto[] } = {}) {
  vi.mocked(useMyCoupons).mockImplementation((filter) =>
    ({
      data: filter === "history" ? history : active,
      isPending: false,
      isError: false,
      error: null,
    }) as unknown as CouponsHookReturn,
  );
}

function setRedeem(opts: {
  isPending?: boolean;
  isError?: boolean;
  error?: unknown;
  mutate?: ReturnType<typeof vi.fn>;
} = {}) {
  const mutate = opts.mutate ?? vi.fn();
  vi.mocked(useRedeemCoupon).mockReturnValue({
    mutate,
    isPending: opts.isPending ?? false,
    isError: opts.isError ?? false,
    error: opts.error ?? null,
  } as unknown as RedeemHookReturn);
  return mutate;
}

function apiError(code: CouponRedemptionErrorCode, status = 409): ApiError {
  return new ApiError({
    status,
    title: "Coupon error",
    detail: "test",
    code,
  });
}

describe("<WalletCouponsCard />", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders the redeem form", () => {
    setCoupons();
    setRedeem();
    renderWithProviders(<WalletCouponsCard />);
    expect(
      screen.getByPlaceholderText(/Enter a coupon code/i),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /Redeem/i }),
    ).toBeInTheDocument();
  });

  it("renders active grants from the hook", () => {
    setCoupons({
      active: [grant({ code: "FREE-LISTING" }), grant({
        publicId: "00000000-0000-0000-0000-0000000000a2",
        code: "HALF-OFF",
        discounts: [
          { target: "LISTING_FEE", op: "PERCENT_OFF", value: "50", sortOrder: 0 },
        ],
      })],
    });
    setRedeem();
    renderWithProviders(<WalletCouponsCard />);
    expect(screen.getByText("FREE-LISTING")).toBeInTheDocument();
    expect(screen.getByText("HALF-OFF")).toBeInTheDocument();
  });

  it("shows an empty state when there are no active grants", () => {
    setCoupons({ active: [] });
    setRedeem();
    renderWithProviders(<WalletCouponsCard />);
    expect(screen.getByText(/No active coupons/i)).toBeInTheDocument();
  });

  it("calls the redeem mutation with the typed code", async () => {
    setCoupons();
    const mutate = setRedeem();
    renderWithProviders(<WalletCouponsCard />);
    const input = screen.getByPlaceholderText(/Enter a coupon code/i);
    await userEvent.type(input, "WELCOME");
    await userEvent.click(screen.getByRole("button", { name: /Redeem/i }));
    expect(mutate).toHaveBeenCalledWith("WELCOME", expect.any(Object));
  });

  it("trims whitespace before submitting", async () => {
    setCoupons();
    const mutate = setRedeem();
    renderWithProviders(<WalletCouponsCard />);
    const input = screen.getByPlaceholderText(/Enter a coupon code/i);
    await userEvent.type(input, "  PADDED  ");
    await userEvent.click(screen.getByRole("button", { name: /Redeem/i }));
    expect(mutate).toHaveBeenCalledWith("PADDED", expect.any(Object));
  });

  it("submits on Enter inside the input", async () => {
    setCoupons();
    const mutate = setRedeem();
    renderWithProviders(<WalletCouponsCard />);
    const input = screen.getByPlaceholderText(/Enter a coupon code/i);
    await userEvent.type(input, "WELCOME{Enter}");
    expect(mutate).toHaveBeenCalledWith("WELCOME", expect.any(Object));
  });

  it("disables submit when the input is empty", () => {
    setCoupons();
    setRedeem();
    renderWithProviders(<WalletCouponsCard />);
    expect(
      screen.getByRole("button", { name: /Redeem/i }),
    ).toBeDisabled();
  });

  const errorCases: Array<[CouponRedemptionErrorCode, RegExp]> = [
    ["UNKNOWN_CODE", /don't recognize that code/i],
    ["NOT_ELIGIBLE", /isn't available for your account/i],
    ["ALREADY_REDEEMED", /already redeemed this code/i],
    ["EXPIRED", /code has expired/i],
    ["PAUSED", /code is paused/i],
    ["MAX_REACHED", /fully redeemed/i],
    ["INACTIVE", /isn't active/i],
  ];

  it.each(errorCases)(
    "renders the right message for %s",
    (code, matcher) => {
      setCoupons();
      setRedeem({ isError: true, error: apiError(code) });
      renderWithProviders(<WalletCouponsCard />);
      expect(screen.getByText(matcher)).toBeInTheDocument();
    },
  );

  it("falls back to a generic message for an unknown error code", () => {
    setCoupons();
    setRedeem({
      isError: true,
      error: apiError(
        "WHATEVER_NEW_CODE" as CouponRedemptionErrorCode,
      ),
    });
    renderWithProviders(<WalletCouponsCard />);
    // detail from the ApiError fixture
    expect(screen.getByText("test")).toBeInTheDocument();
  });

  it("toggles the history expander and renders non-ACTIVE grants when open", async () => {
    setCoupons({
      active: [],
      history: [
        grant({
          publicId: "00000000-0000-0000-0000-0000000000d1",
          code: "OLD-CODE",
          state: "EXPIRED",
        }),
      ],
    });
    setRedeem();
    renderWithProviders(<WalletCouponsCard />);

    // Collapsed by default — the history grant is not visible
    expect(screen.queryByText("OLD-CODE")).not.toBeInTheDocument();

    const toggle = screen.getByRole("button", { name: /History \(1\)/i });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    await userEvent.click(toggle);

    await waitFor(() => {
      expect(toggle).toHaveAttribute("aria-expanded", "true");
    });
    expect(screen.getByText("OLD-CODE")).toBeInTheDocument();
  });

  it("hides the history expander entirely when history is empty", () => {
    setCoupons({ active: [grant()], history: [] });
    setRedeem();
    renderWithProviders(<WalletCouponsCard />);
    expect(
      screen.queryByRole("button", { name: /History/i }),
    ).not.toBeInTheDocument();
  });
});
