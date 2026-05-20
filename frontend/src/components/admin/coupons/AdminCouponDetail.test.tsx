import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import type { CouponDto } from "@/types/coupon";
import type { Page } from "@/types/page";

const mockReplace = vi.fn();
const mockPush = vi.fn();
let mockSearchParams = new URLSearchParams();

vi.mock("next/navigation", () => ({
  usePathname: vi.fn(() => "/admin/coupons/00000000-0000-0000-0000-0000000000a1"),
  useRouter: () => ({
    push: mockPush,
    replace: mockReplace,
    refresh: vi.fn(),
    back: vi.fn(),
    forward: vi.fn(),
    prefetch: vi.fn(),
  }),
  useSearchParams: () => mockSearchParams,
}));

// Mock the coupon detail + grants hooks so the wrapper can be exercised
// without firing real fetches. The sub-tabs are mocked further down so
// each test focuses on the wrapper's responsibilities (header / pills /
// tab routing).
vi.mock("@/hooks/admin/useAdminCoupon", () => ({
  useAdminCoupon: vi.fn(),
  adminCouponKey: (id: string) => ["admin-coupon", id] as const,
}));

vi.mock("@/hooks/admin/useAdminCouponGrants", () => ({
  useAdminCouponGrants: vi.fn(),
  adminCouponGrantsKey: (id: string, p: unknown) =>
    ["admin-coupon-grants", id, p] as const,
}));

const deleteMutate = vi.fn();
vi.mock("@/hooks/admin/useDeleteAdminCoupon", () => ({
  useDeleteAdminCoupon: () => ({
    mutate: deleteMutate,
    isPending: false,
  }),
}));

// Tab content stubs — make each tab cheap and detectable.
vi.mock("./AdminCouponDetailOverview", () => ({
  AdminCouponDetailOverview: ({
    coupon,
    totalGrants,
    activeGrants,
  }: {
    coupon: CouponDto;
    totalGrants: number;
    activeGrants: number;
  }) => (
    <div data-testid="overview-stub">
      OVERVIEW {coupon.code} total={totalGrants} active={activeGrants}
    </div>
  ),
}));

vi.mock("./AdminCouponDetailGrants", () => ({
  AdminCouponDetailGrants: ({ couponPublicId }: { couponPublicId: string }) => (
    <div data-testid="grants-stub">GRANTS {couponPublicId}</div>
  ),
}));

vi.mock("./AdminCouponDetailEdit", () => ({
  AdminCouponDetailEdit: ({
    coupon,
    totalGrants,
  }: {
    coupon: CouponDto;
    totalGrants: number;
  }) => (
    <div data-testid="edit-stub">
      EDIT {coupon.code} totalGrants={totalGrants}
    </div>
  ),
}));

import { useAdminCoupon } from "@/hooks/admin/useAdminCoupon";
import { useAdminCouponGrants } from "@/hooks/admin/useAdminCouponGrants";
import { AdminCouponDetail } from "./AdminCouponDetail";

const COUPON_ID = "00000000-0000-0000-0000-0000000000a1";

function coupon(overrides: Partial<CouponDto> = {}): CouponDto {
  return {
    publicId: COUPON_ID,
    code: "WELCOME30",
    description: "First-listing promo",
    durationDays: 30,
    useCount: 1,
    redeemableUntil: null,
    maxTotalRedemptions: 100,
    maxPerUser: 1,
    signupWindowStart: null,
    signupWindowEnd: null,
    active: true,
    notifyOnGrant: true,
    discounts: [
      { target: "LISTING_FEE", op: "PERCENT_OFF", value: "30", sortOrder: 0 },
    ],
    allowedUserPublicIds: [],
    createdAt: "2026-05-01T00:00:00Z",
    updatedAt: "2026-05-01T00:00:00Z",
    ...overrides,
  };
}

function setCouponHook(state: {
  isLoading?: boolean;
  isError?: boolean;
  data?: CouponDto;
}) {
  vi.mocked(useAdminCoupon).mockReturnValue({
    isLoading: state.isLoading ?? false,
    isError: state.isError ?? false,
    data: state.data,
  } as unknown as ReturnType<typeof useAdminCoupon>);
}

function setGrantsHook(totals: { all: number; active: number }) {
  vi.mocked(useAdminCouponGrants).mockImplementation((_id, params) => {
    const isActive = params?.state === "ACTIVE";
    const total = isActive ? totals.active : totals.all;
    return {
      isLoading: false,
      isError: false,
      data: {
        content: [],
        totalElements: total,
        totalPages: 1,
        number: 0,
        size: 1,
      } satisfies Page<never>,
    } as unknown as ReturnType<typeof useAdminCouponGrants>;
  });
}

describe("<AdminCouponDetail />", () => {
  beforeEach(() => {
    mockReplace.mockReset();
    mockPush.mockReset();
    deleteMutate.mockReset();
    mockSearchParams = new URLSearchParams();
    vi.clearAllMocks();
  });

  it("shows loading state while the coupon query is pending", () => {
    setCouponHook({ isLoading: true });
    setGrantsHook({ all: 0, active: 0 });
    renderWithProviders(<AdminCouponDetail publicId={COUPON_ID} />);
    expect(screen.getByTestId("coupon-detail-loading")).toBeInTheDocument();
  });

  it("renders an error state when the coupon fetch fails", () => {
    setCouponHook({ isError: true });
    setGrantsHook({ all: 0, active: 0 });
    renderWithProviders(<AdminCouponDetail publicId={COUPON_ID} />);
    expect(screen.getByTestId("coupon-detail-error")).toBeInTheDocument();
  });

  it("renders header with code, description, and active pill", () => {
    setCouponHook({ data: coupon() });
    setGrantsHook({ all: 5, active: 3 });
    renderWithProviders(<AdminCouponDetail publicId={COUPON_ID} />);
    expect(screen.getByTestId("coupon-code")).toHaveTextContent("WELCOME30");
    expect(screen.getByTestId("coupon-status-pill")).toHaveTextContent(
      "Active",
    );
    expect(screen.getByText("First-listing promo")).toBeInTheDocument();
  });

  it("renders an Inactive pill when coupon.active=false", () => {
    setCouponHook({ data: coupon({ active: false }) });
    setGrantsHook({ all: 0, active: 0 });
    renderWithProviders(<AdminCouponDetail publicId={COUPON_ID} />);
    expect(screen.getByTestId("coupon-status-pill")).toHaveTextContent(
      "Inactive",
    );
  });

  it("renders an Expired pill when redeemableUntil is in the past", () => {
    setCouponHook({
      data: coupon({
        active: true,
        redeemableUntil: "2020-01-01T00:00:00Z",
      }),
    });
    setGrantsHook({ all: 0, active: 0 });
    renderWithProviders(<AdminCouponDetail publicId={COUPON_ID} />);
    expect(screen.getByTestId("coupon-status-pill")).toHaveTextContent(
      "Expired",
    );
  });

  it("defaults to the Overview tab and renders its content", () => {
    setCouponHook({ data: coupon() });
    setGrantsHook({ all: 5, active: 3 });
    renderWithProviders(<AdminCouponDetail publicId={COUPON_ID} />);
    expect(screen.getByTestId("overview-stub")).toBeInTheDocument();
    expect(screen.getByTestId("overview-stub")).toHaveTextContent(
      "OVERVIEW WELCOME30 total=5 active=3",
    );
    expect(screen.queryByTestId("grants-stub")).not.toBeInTheDocument();
    expect(screen.queryByTestId("edit-stub")).not.toBeInTheDocument();
  });

  it("renders the Grants tab when ?tab=grants is in the URL", () => {
    mockSearchParams = new URLSearchParams("tab=grants");
    setCouponHook({ data: coupon() });
    setGrantsHook({ all: 5, active: 3 });
    renderWithProviders(<AdminCouponDetail publicId={COUPON_ID} />);
    expect(screen.getByTestId("grants-stub")).toBeInTheDocument();
    expect(screen.queryByTestId("overview-stub")).not.toBeInTheDocument();
  });

  it("renders the Edit tab when ?tab=edit is in the URL", () => {
    mockSearchParams = new URLSearchParams("tab=edit");
    setCouponHook({ data: coupon() });
    setGrantsHook({ all: 2, active: 1 });
    renderWithProviders(<AdminCouponDetail publicId={COUPON_ID} />);
    expect(screen.getByTestId("edit-stub")).toBeInTheDocument();
    expect(screen.getByTestId("edit-stub")).toHaveTextContent(
      "EDIT WELCOME30 totalGrants=2",
    );
  });

  it("falls back to overview when ?tab=<unknown>", () => {
    mockSearchParams = new URLSearchParams("tab=garbage");
    setCouponHook({ data: coupon() });
    setGrantsHook({ all: 0, active: 0 });
    renderWithProviders(<AdminCouponDetail publicId={COUPON_ID} />);
    expect(screen.getByTestId("overview-stub")).toBeInTheDocument();
  });

  it("clicking a tab updates the URL via router.replace", async () => {
    setCouponHook({ data: coupon() });
    setGrantsHook({ all: 0, active: 0 });
    renderWithProviders(<AdminCouponDetail publicId={COUPON_ID} />);
    await userEvent.click(screen.getByTestId("coupon-tab-grants"));
    expect(mockReplace).toHaveBeenCalledWith(
      expect.stringContaining("tab=grants"),
      expect.objectContaining({ scroll: false }),
    );
  });

  it("clicking the Overview tab removes the tab query param", async () => {
    mockSearchParams = new URLSearchParams("tab=grants");
    setCouponHook({ data: coupon() });
    setGrantsHook({ all: 0, active: 0 });
    renderWithProviders(<AdminCouponDetail publicId={COUPON_ID} />);
    await userEvent.click(screen.getByTestId("coupon-tab-overview"));
    expect(mockReplace).toHaveBeenCalledWith(
      `/admin/coupons/${COUPON_ID}`,
      expect.objectContaining({ scroll: false }),
    );
  });

  it("delete button reads 'Delete' when totalGrants=0", () => {
    setCouponHook({ data: coupon() });
    setGrantsHook({ all: 0, active: 0 });
    renderWithProviders(<AdminCouponDetail publicId={COUPON_ID} />);
    expect(screen.getByTestId("coupon-delete-btn")).toHaveTextContent(
      "Delete",
    );
  });

  it("delete button reads 'Archive' when totalGrants>0", () => {
    setCouponHook({ data: coupon() });
    setGrantsHook({ all: 4, active: 2 });
    renderWithProviders(<AdminCouponDetail publicId={COUPON_ID} />);
    expect(screen.getByTestId("coupon-delete-btn")).toHaveTextContent(
      "Archive",
    );
  });

  it("delete button triggers the mutation on confirmation", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(true);
    setCouponHook({ data: coupon() });
    setGrantsHook({ all: 0, active: 0 });
    renderWithProviders(<AdminCouponDetail publicId={COUPON_ID} />);
    await userEvent.click(screen.getByTestId("coupon-delete-btn"));
    expect(confirmSpy).toHaveBeenCalled();
    await waitFor(() => expect(deleteMutate).toHaveBeenCalledTimes(1));
    confirmSpy.mockRestore();
  });

  it("delete button does NOT trigger when the confirm is cancelled", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(false);
    setCouponHook({ data: coupon() });
    setGrantsHook({ all: 0, active: 0 });
    renderWithProviders(<AdminCouponDetail publicId={COUPON_ID} />);
    await userEvent.click(screen.getByTestId("coupon-delete-btn"));
    expect(deleteMutate).not.toHaveBeenCalled();
    confirmSpy.mockRestore();
  });
});
