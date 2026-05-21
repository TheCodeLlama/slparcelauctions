import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import type { CouponGrantDto } from "@/types/coupon";
import type { Page } from "@/types/page";

vi.mock("next/navigation", () => ({
  usePathname: vi.fn(
    () => "/admin/coupons/00000000-0000-0000-0000-0000000000a1",
  ),
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

vi.mock("@/hooks/admin/useAdminCouponGrants", () => ({
  useAdminCouponGrants: vi.fn(),
  adminCouponGrantsKey: (id: string, p: unknown) =>
    ["admin-coupon-grants", id, p] as const,
}));

const revokeMutate = vi.fn();
let revokePending = false;
vi.mock("@/hooks/admin/useRevokeCouponGrant", () => ({
  useRevokeCouponGrant: () => ({
    mutate: revokeMutate,
    isPending: revokePending,
  }),
}));

const directGrantMutate = vi.fn();
vi.mock("@/hooks/admin/useDirectGrantCoupon", () => ({
  useDirectGrantCoupon: () => ({
    mutate: directGrantMutate,
    isPending: false,
    isError: false,
    error: null,
    reset: vi.fn(),
  }),
}));

// Stub the autocomplete to a one-click button so the modal flow is
// observable without a fake user-search backend.
vi.mock("@/components/admin/bans/UserSearchAutocomplete", () => ({
  UserSearchAutocomplete: ({
    onSelect,
  }: {
    onSelect: (u: {
      publicId: string;
      username: string;
      displayName: string | null;
    }) => void;
  }) => (
    <button
      type="button"
      data-testid="mock-user-pick"
      onClick={() =>
        onSelect({
          publicId: "00000000-0000-0000-0000-0000000000b1",
          username: "alice",
          displayName: "Alice",
        })
      }
    >
      pick test user
    </button>
  ),
}));

import { useAdminCouponGrants } from "@/hooks/admin/useAdminCouponGrants";
import { AdminCouponDetailGrants } from "./AdminCouponDetailGrants";

const COUPON_ID = "00000000-0000-0000-0000-0000000000a1";

function grant(overrides: Partial<CouponGrantDto> = {}): CouponGrantDto {
  return {
    publicId: "00000000-0000-0000-0000-0000000000c1",
    couponPublicId: COUPON_ID,
    code: "WELCOME30",
    grantedAt: "2026-05-10T12:00:00Z",
    expiresAt: "2026-06-10T12:00:00Z",
    remainingCount: 1,
    state: "ACTIVE",
    source: "REDEMPTION",
    discounts: [
      { target: "LISTING_FEE", op: "PERCENT_OFF", value: "30", sortOrder: 0 },
    ],
    ...overrides,
  };
}

function page(
  content: CouponGrantDto[],
  overrides: Partial<Page<CouponGrantDto>> = {},
): Page<CouponGrantDto> {
  return {
    content,
    totalElements: content.length,
    totalPages: 1,
    number: 0,
    size: 25,
    ...overrides,
  };
}

function setHook(value: {
  isLoading?: boolean;
  isError?: boolean;
  data?: Page<CouponGrantDto>;
}) {
  vi.mocked(useAdminCouponGrants).mockReturnValue({
    isLoading: value.isLoading ?? false,
    isError: value.isError ?? false,
    data: value.data,
  } as unknown as ReturnType<typeof useAdminCouponGrants>);
}

describe("<AdminCouponDetailGrants />", () => {
  beforeEach(() => {
    revokeMutate.mockReset();
    directGrantMutate.mockReset();
    revokePending = false;
    vi.clearAllMocks();
  });

  it("renders the empty state when the page has no grants", () => {
    setHook({ data: page([]) });
    renderWithProviders(
      <AdminCouponDetailGrants couponPublicId={COUPON_ID} />,
    );
    expect(screen.getByTestId("grants-empty")).toBeInTheDocument();
  });

  it("renders rows with state pill, source label, and revoke action for active rows", () => {
    setHook({
      data: page([
        grant({
          publicId: "00000000-0000-0000-0000-0000000000c1",
          state: "ACTIVE",
          source: "REDEMPTION",
        }),
        grant({
          publicId: "00000000-0000-0000-0000-0000000000c2",
          state: "REVOKED",
          source: "ADMIN_GRANT",
        }),
      ]),
    });
    renderWithProviders(
      <AdminCouponDetailGrants couponPublicId={COUPON_ID} />,
    );
    const table = screen.getByTestId("grants-table");
    expect(table).toBeInTheDocument();
    // "Active" / "Revoked" labels collide with dropdown option text;
    // scope to the table body so we read the state pills, not the
    // filter chrome.
    expect(table).toHaveTextContent("Active");
    expect(table).toHaveTextContent("Revoked");
    expect(table).toHaveTextContent("Redemption");
    expect(table).toHaveTextContent("Admin grant");
    expect(
      screen.getByTestId("revoke-grant-00000000-0000-0000-0000-0000000000c1"),
    ).toBeInTheDocument();
    expect(
      screen.queryByTestId(
        "revoke-grant-00000000-0000-0000-0000-0000000000c2",
      ),
    ).not.toBeInTheDocument();
  });

  it("state filter dropdown wires through to the hook params", async () => {
    setHook({ data: page([]) });
    renderWithProviders(
      <AdminCouponDetailGrants couponPublicId={COUPON_ID} />,
    );
    await userEvent.selectOptions(
      screen.getByTestId("grants-state-select"),
      "ACTIVE",
    );
    // Re-render fires another call with state=ACTIVE.
    const calls = vi.mocked(useAdminCouponGrants).mock.calls;
    const lastCall = calls[calls.length - 1];
    expect(lastCall[1]?.state).toBe("ACTIVE");
  });

  it("source filter dropdown wires through to the hook params", async () => {
    setHook({ data: page([]) });
    renderWithProviders(
      <AdminCouponDetailGrants couponPublicId={COUPON_ID} />,
    );
    await userEvent.selectOptions(
      screen.getByTestId("grants-source-select"),
      "ADMIN_GRANT",
    );
    const calls = vi.mocked(useAdminCouponGrants).mock.calls;
    const lastCall = calls[calls.length - 1];
    expect(lastCall[1]?.source).toBe("ADMIN_GRANT");
  });

  it("revoke button asks for confirmation and triggers the mutation when accepted", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(true);
    setHook({ data: page([grant()]) });
    renderWithProviders(
      <AdminCouponDetailGrants couponPublicId={COUPON_ID} />,
    );
    await userEvent.click(
      screen.getByTestId("revoke-grant-00000000-0000-0000-0000-0000000000c1"),
    );
    expect(confirmSpy).toHaveBeenCalled();
    await waitFor(() => expect(revokeMutate).toHaveBeenCalledTimes(1));
    expect(revokeMutate).toHaveBeenCalledWith(
      "00000000-0000-0000-0000-0000000000c1",
    );
    confirmSpy.mockRestore();
  });

  it("revoke button does NOT trigger when confirm is cancelled", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(false);
    setHook({ data: page([grant()]) });
    renderWithProviders(
      <AdminCouponDetailGrants couponPublicId={COUPON_ID} />,
    );
    await userEvent.click(
      screen.getByTestId("revoke-grant-00000000-0000-0000-0000-0000000000c1"),
    );
    expect(revokeMutate).not.toHaveBeenCalled();
    confirmSpy.mockRestore();
  });

  it("Direct grant button opens the modal", async () => {
    setHook({ data: page([]) });
    renderWithProviders(
      <AdminCouponDetailGrants couponPublicId={COUPON_ID} />,
    );
    expect(
      screen.queryByTestId("direct-grant-modal"),
    ).not.toBeInTheDocument();
    await userEvent.click(screen.getByTestId("open-direct-grant-btn"));
    expect(screen.getByTestId("direct-grant-modal")).toBeInTheDocument();
  });

  it("direct-grant modal submits the selected user publicIds", async () => {
    setHook({ data: page([]) });
    renderWithProviders(
      <AdminCouponDetailGrants couponPublicId={COUPON_ID} />,
    );
    await userEvent.click(screen.getByTestId("open-direct-grant-btn"));
    await userEvent.click(screen.getByTestId("mock-user-pick"));
    await userEvent.click(screen.getByTestId("direct-grant-submit"));
    await waitFor(() => expect(directGrantMutate).toHaveBeenCalledTimes(1));
    const [userIds] = directGrantMutate.mock.calls[0];
    expect(userIds).toEqual(["00000000-0000-0000-0000-0000000000b1"]);
  });
});
