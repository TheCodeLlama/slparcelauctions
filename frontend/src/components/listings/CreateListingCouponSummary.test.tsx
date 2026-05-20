import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import { ApiError } from "@/lib/api";
import type {
  CouponRedemptionErrorCode,
  ProspectiveDiscountsDto,
} from "@/types/coupon";

vi.mock("@/hooks/useProspectiveDiscounts", () => ({
  useProspectiveDiscounts: vi.fn(),
  PROSPECTIVE_DISCOUNTS_KEY: ["prospective-discounts"] as const,
}));

vi.mock("@/hooks/useRedeemCoupon", () => ({
  useRedeemCoupon: vi.fn(),
}));

import { useProspectiveDiscounts } from "@/hooks/useProspectiveDiscounts";
import { useRedeemCoupon } from "@/hooks/useRedeemCoupon";
import { CreateListingCouponSummary } from "./CreateListingCouponSummary";

type ProspectiveHookReturn = ReturnType<typeof useProspectiveDiscounts>;
type RedeemHookReturn = ReturnType<typeof useRedeemCoupon>;

function dto(
  overrides: Partial<ProspectiveDiscountsDto> = {},
): ProspectiveDiscountsDto {
  return {
    listingFeeLindens: 100,
    commissionRate: "0.05",
    listingFeeCouponPublicId: null,
    listingFeeCouponCode: null,
    commissionCouponPublicId: null,
    commissionCouponCode: null,
    ...overrides,
  };
}

function setProspective(data: ProspectiveDiscountsDto | undefined) {
  vi.mocked(useProspectiveDiscounts).mockReturnValue({
    data,
    isPending: data === undefined,
    isError: false,
    error: null,
  } as unknown as ProspectiveHookReturn);
}

function setRedeem(
  opts: {
    isPending?: boolean;
    isError?: boolean;
    error?: unknown;
    mutate?: ReturnType<typeof vi.fn>;
  } = {},
) {
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

describe("<CreateListingCouponSummary />", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders only platform defaults when there are no eligible grants", () => {
    setProspective(dto());
    setRedeem();
    renderWithProviders(<CreateListingCouponSummary />);

    // Fee + commission both show the default value with no strike-through.
    expect(screen.getByText("L$100")).toBeInTheDocument();
    expect(screen.getByText("5%")).toBeInTheDocument();
    // No coupon-code badge rendered.
    expect(screen.queryByText(/WELCOME|FREE|HALF/i)).not.toBeInTheDocument();
    // No <s> strike-through tags in the summary list.
    expect(document.querySelector("s")).toBeNull();
  });

  it("renders the listing-fee badge with strike-through when fee is discounted", () => {
    setProspective(
      dto({
        listingFeeLindens: 0,
        listingFeeCouponPublicId: "00000000-0000-0000-0000-0000000000aa",
        listingFeeCouponCode: "FREE-LISTING",
      }),
    );
    setRedeem();
    renderWithProviders(<CreateListingCouponSummary />);

    const strike = screen.getByText("L$100");
    expect(strike.tagName).toBe("S");
    expect(screen.getByText("L$0")).toBeInTheDocument();
    expect(screen.getByText("FREE-LISTING")).toBeInTheDocument();
  });

  it("renders the commission badge with strike-through when commission is discounted", () => {
    setProspective(
      dto({
        commissionRate: "0.025",
        commissionCouponPublicId: "00000000-0000-0000-0000-0000000000bb",
        commissionCouponCode: "HALF-COMM",
      }),
    );
    setRedeem();
    renderWithProviders(<CreateListingCouponSummary />);

    const strike = screen.getByText("5%");
    expect(strike.tagName).toBe("S");
    expect(screen.getByText("2.5%")).toBeInTheDocument();
    expect(screen.getByText("HALF-COMM")).toBeInTheDocument();
  });

  it("renders both badges when both targets are discounted", () => {
    setProspective(
      dto({
        listingFeeLindens: 50,
        listingFeeCouponPublicId: "00000000-0000-0000-0000-0000000000aa",
        listingFeeCouponCode: "FEE-50",
        commissionRate: "0",
        commissionCouponPublicId: "00000000-0000-0000-0000-0000000000bb",
        commissionCouponCode: "ZERO-COMM",
      }),
    );
    setRedeem();
    renderWithProviders(<CreateListingCouponSummary />);

    expect(screen.getByText("FEE-50")).toBeInTheDocument();
    expect(screen.getByText("ZERO-COMM")).toBeInTheDocument();
    expect(screen.getByText("L$50")).toBeInTheDocument();
    expect(screen.getByText("0%")).toBeInTheDocument();
  });

  it("respects the defaultFeeLindens prop for the strike-through value", () => {
    setProspective(
      dto({
        listingFeeLindens: 200,
        listingFeeCouponPublicId: "00000000-0000-0000-0000-0000000000aa",
        listingFeeCouponCode: "OVERRIDE-FEE",
      }),
    );
    setRedeem();
    renderWithProviders(
      <CreateListingCouponSummary defaultFeeLindens={500} />,
    );
    const strike = screen.getByText("L$500");
    expect(strike.tagName).toBe("S");
    expect(screen.getByText("L$200")).toBeInTheDocument();
  });

  it("expander is collapsed by default and toggles open", async () => {
    setProspective(dto());
    setRedeem();
    renderWithProviders(<CreateListingCouponSummary />);

    expect(
      screen.queryByPlaceholderText(/Enter a coupon code/i),
    ).not.toBeInTheDocument();

    const toggle = screen.getByRole("button", { name: /Have a code/i });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    await userEvent.click(toggle);

    await waitFor(() => {
      expect(toggle).toHaveAttribute("aria-expanded", "true");
    });
    expect(
      screen.getByPlaceholderText(/Enter a coupon code/i),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Apply/i })).toBeInTheDocument();

    await userEvent.click(toggle);
    await waitFor(() => {
      expect(toggle).toHaveAttribute("aria-expanded", "false");
    });
    expect(
      screen.queryByPlaceholderText(/Enter a coupon code/i),
    ).not.toBeInTheDocument();
  });

  it("calls the redeem mutation with the trimmed typed code", async () => {
    setProspective(dto());
    const mutate = setRedeem();
    renderWithProviders(<CreateListingCouponSummary />);

    await userEvent.click(
      screen.getByRole("button", { name: /Have a code/i }),
    );
    const input = screen.getByPlaceholderText(/Enter a coupon code/i);
    await userEvent.type(input, "  WELCOME  ");
    await userEvent.click(screen.getByRole("button", { name: /Apply/i }));

    expect(mutate).toHaveBeenCalledWith("WELCOME", expect.any(Object));
  });

  it("disables Apply when the input is empty", async () => {
    setProspective(dto());
    setRedeem();
    renderWithProviders(<CreateListingCouponSummary />);
    await userEvent.click(
      screen.getByRole("button", { name: /Have a code/i }),
    );
    expect(screen.getByRole("button", { name: /Apply/i })).toBeDisabled();
  });

  it("renders the UNKNOWN_CODE error message inline", async () => {
    setProspective(dto());
    setRedeem({ isError: true, error: apiError("UNKNOWN_CODE") });
    renderWithProviders(<CreateListingCouponSummary />);
    await userEvent.click(
      screen.getByRole("button", { name: /Have a code/i }),
    );
    expect(
      screen.getByText(/don't recognize that code/i),
    ).toBeInTheDocument();
  });

  it("submits on Enter inside the input", async () => {
    setProspective(dto());
    const mutate = setRedeem();
    renderWithProviders(<CreateListingCouponSummary />);
    await userEvent.click(
      screen.getByRole("button", { name: /Have a code/i }),
    );
    const input = screen.getByPlaceholderText(/Enter a coupon code/i);
    await userEvent.type(input, "WELCOME{Enter}");
    expect(mutate).toHaveBeenCalledWith("WELCOME", expect.any(Object));
  });

  it("renders defaults when the query has not loaded yet", () => {
    setProspective(undefined);
    setRedeem();
    renderWithProviders(<CreateListingCouponSummary />);
    expect(screen.getByText("L$100")).toBeInTheDocument();
    expect(screen.getByText("5%")).toBeInTheDocument();
  });
});
