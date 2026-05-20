import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import type { CouponSummaryDto } from "@/types/coupon";
import type { Page } from "@/types/page";

const mockReplace = vi.fn();

vi.mock("next/navigation", () => ({
  usePathname: vi.fn(() => "/admin/coupons"),
  useRouter: () => ({
    push: vi.fn(),
    replace: mockReplace,
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  useSearchParams: vi.fn(() => new URLSearchParams()),
}));

vi.mock("@/hooks/admin/useAdminCoupons", () => ({
  useAdminCoupons: vi.fn(),
  adminCouponsKey: (p: unknown) => ["admin-coupons", p] as const,
}));

import { useAdminCoupons } from "@/hooks/admin/useAdminCoupons";
import { AdminCouponList } from "./AdminCouponList";

type HookReturn = ReturnType<typeof useAdminCoupons>;

function summary(
  overrides: Partial<CouponSummaryDto> = {},
): CouponSummaryDto {
  return {
    publicId: "00000000-0000-0000-0000-0000000000a1",
    code: "WELCOME10",
    description: "10% off the first listing",
    active: true,
    redeemableUntil: null,
    discounts: [
      { target: "LISTING_FEE", op: "PERCENT_OFF", value: "10", sortOrder: 0 },
    ],
    totalGrants: 2,
    activeGrants: 1,
    maxTotalRedemptions: 100,
    ...overrides,
  };
}

function page(
  content: CouponSummaryDto[],
  overrides: Partial<Page<CouponSummaryDto>> = {},
): Page<CouponSummaryDto> {
  return {
    content,
    totalElements: content.length,
    totalPages: 1,
    number: 0,
    size: 25,
    ...overrides,
  };
}

function setHook(value: Partial<HookReturn> & { data?: Page<CouponSummaryDto> }) {
  vi.mocked(useAdminCoupons).mockReturnValue({
    isLoading: false,
    isError: false,
    ...value,
  } as unknown as HookReturn);
}

describe("<AdminCouponList />", () => {
  beforeEach(() => {
    mockReplace.mockReset();
    vi.clearAllMocks();
  });

  it("renders the empty state when the page has no rows", () => {
    setHook({ data: page([]) });
    renderWithProviders(<AdminCouponList />);
    expect(screen.getByTestId("coupons-empty")).toBeInTheDocument();
    expect(screen.queryByTestId("coupons-table")).not.toBeInTheDocument();
  });

  it("renders rows with code, status, and discount pills", () => {
    setHook({
      data: page([
        summary({
          publicId: "00000000-0000-0000-0000-0000000000a1",
          code: "ALPHA",
        }),
        summary({
          publicId: "00000000-0000-0000-0000-0000000000a2",
          code: "BETA",
          active: false,
          discounts: [
            {
              target: "LISTING_FEE",
              op: "OVERRIDE",
              value: "0",
              sortOrder: 0,
            },
            {
              target: "COMMISSION_RATE",
              op: "PERCENT_OFF",
              value: "50",
              sortOrder: 1,
            },
            {
              target: "COMMISSION_RATE",
              op: "FLAT_OFF",
              value: "5",
              sortOrder: 2,
            },
          ],
        }),
      ]),
    });

    renderWithProviders(<AdminCouponList />);

    expect(screen.getByTestId("coupons-table")).toBeInTheDocument();
    expect(screen.getByText("ALPHA")).toBeInTheDocument();
    expect(screen.getByText("BETA")).toBeInTheDocument();
    // ALPHA is active, BETA is inactive. Scope the assertion to the
    // table body so the status-filter dropdown's options (which also
    // include "Active" / "Inactive" labels) don't collide.
    const table = screen.getByTestId("coupons-table");
    expect(table).toHaveTextContent("Active");
    expect(table).toHaveTextContent("Inactive");
    // BETA has three discounts; two pills + "+1 more".
    expect(screen.getByText("Listing fee: L$ 0")).toBeInTheDocument();
    expect(screen.getByText("Commission: -50%")).toBeInTheDocument();
    expect(screen.getByText("+1 more")).toBeInTheDocument();
  });

  it("renders a coupon code as a link to the detail page", () => {
    setHook({
      data: page([
        summary({
          publicId: "00000000-0000-0000-0000-0000000000a1",
          code: "ALPHA",
        }),
      ]),
    });
    renderWithProviders(<AdminCouponList />);
    const link = screen.getByTestId(
      "coupon-code-00000000-0000-0000-0000-0000000000a1",
    );
    expect(link).toHaveAttribute(
      "href",
      "/admin/coupons/00000000-0000-0000-0000-0000000000a1",
    );
  });

  it("status select updates the URL", async () => {
    setHook({ data: page([]) });
    renderWithProviders(<AdminCouponList />);
    const select = screen.getByTestId("status-select");
    await userEvent.selectOptions(select, "active");
    expect(mockReplace).toHaveBeenCalledWith(
      expect.stringContaining("status=active"),
      expect.anything(),
    );
  });

  it("discount-target select updates the URL", async () => {
    setHook({ data: page([]) });
    renderWithProviders(<AdminCouponList />);
    const select = screen.getByTestId("target-select");
    await userEvent.selectOptions(select, "COMMISSION_RATE");
    expect(mockReplace).toHaveBeenCalledWith(
      expect.stringContaining("discount_target=COMMISSION_RATE"),
      expect.anything(),
    );
  });

  it("search input commits on Enter", async () => {
    setHook({ data: page([]) });
    renderWithProviders(<AdminCouponList />);
    const input = screen.getByTestId("coupon-search-input");
    await userEvent.type(input, "WEL{Enter}");
    await waitFor(() =>
      expect(mockReplace).toHaveBeenCalledWith(
        expect.stringContaining("q=WEL"),
        expect.anything(),
      ),
    );
  });

  it("renders pagination when totalPages > 1", () => {
    setHook({
      data: page(
        [summary()],
        { totalPages: 3, totalElements: 75, number: 0 },
      ),
    });
    renderWithProviders(<AdminCouponList />);
    expect(
      screen.getByRole("navigation", { name: /pagination/i }),
    ).toBeInTheDocument();
  });

  it("hides pagination when totalPages <= 1", () => {
    setHook({ data: page([summary()]) });
    renderWithProviders(<AdminCouponList />);
    expect(
      screen.queryByRole("navigation", { name: /pagination/i }),
    ).not.toBeInTheDocument();
  });

  it("renders an error state", () => {
    setHook({ isError: true, data: undefined });
    renderWithProviders(<AdminCouponList />);
    expect(
      screen.getByText(/Could not load coupons/i),
    ).toBeInTheDocument();
  });

  it("formats redemptions as totalGrants / cap and 'unlimited' when cap is null", () => {
    setHook({
      data: page([
        summary({
          publicId: "00000000-0000-0000-0000-0000000000a1",
          code: "CAPPED",
          totalGrants: 7,
          maxTotalRedemptions: 50,
        }),
        summary({
          publicId: "00000000-0000-0000-0000-0000000000a2",
          code: "UNCAPPED",
          totalGrants: 3,
          maxTotalRedemptions: null,
        }),
      ]),
    });
    renderWithProviders(<AdminCouponList />);
    expect(screen.getByText("7 / 50")).toBeInTheDocument();
    expect(screen.getByText("3 / unlimited")).toBeInTheDocument();
  });
});
