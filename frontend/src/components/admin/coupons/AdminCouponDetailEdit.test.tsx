import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  renderWithProviders,
  screen,
  userEvent,
  waitFor,
} from "@/test/render";
import type { CouponDto, PatchCouponRequest } from "@/types/coupon";

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

const patchMutate = vi.fn();
let patchPending = false;
let patchError: unknown = null;
let patchIsError = false;

vi.mock("@/hooks/admin/usePatchAdminCoupon", () => ({
  usePatchAdminCoupon: () => ({
    mutate: patchMutate,
    isPending: patchPending,
    isError: patchIsError,
    error: patchError,
  }),
}));

import { AdminCouponDetailEdit } from "./AdminCouponDetailEdit";

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

describe("<AdminCouponDetailEdit />", () => {
  beforeEach(() => {
    patchMutate.mockReset();
    patchPending = false;
    patchError = null;
    patchIsError = false;
    vi.clearAllMocks();
  });

  it("renders pre-filled inputs from the coupon", () => {
    renderWithProviders(
      <AdminCouponDetailEdit coupon={coupon()} totalGrants={0} />,
    );
    const desc = screen.getByTestId(
      "edit-description-input",
    ) as HTMLInputElement;
    expect(desc.value).toBe("First-listing promo");
    const duration = screen.getByTestId(
      "edit-duration-days-input",
    ) as HTMLInputElement;
    expect(duration.value).toBe("30");
  });

  it("enables locked inputs when totalGrants=0", () => {
    renderWithProviders(
      <AdminCouponDetailEdit coupon={coupon()} totalGrants={0} />,
    );
    expect(screen.getByTestId("edit-duration-days-input")).not.toBeDisabled();
    expect(screen.getByTestId("edit-use-count-input")).not.toBeDisabled();
    expect(screen.getByTestId("edit-max-per-user-input")).not.toBeDisabled();
    expect(screen.getByTestId("edit-signup-start-input")).not.toBeDisabled();
    expect(screen.getByTestId("edit-signup-end-input")).not.toBeDisabled();
    expect(
      screen.queryByTestId("locked-after-grant-hint"),
    ).not.toBeInTheDocument();
  });

  it("disables lifetime + maxPerUser + signup-window inputs when totalGrants>0", () => {
    renderWithProviders(
      <AdminCouponDetailEdit coupon={coupon()} totalGrants={4} />,
    );
    expect(screen.getByTestId("edit-duration-days-input")).toBeDisabled();
    expect(screen.getByTestId("edit-use-count-input")).toBeDisabled();
    expect(screen.getByTestId("edit-max-per-user-input")).toBeDisabled();
    expect(screen.getByTestId("edit-signup-start-input")).toBeDisabled();
    expect(screen.getByTestId("edit-signup-end-input")).toBeDisabled();
    // The hint appears in three locked Sections.
    expect(
      screen.getAllByTestId("locked-after-grant-hint").length,
    ).toBeGreaterThan(0);
  });

  it("submits only the changed fields on save", async () => {
    renderWithProviders(
      <AdminCouponDetailEdit coupon={coupon()} totalGrants={0} />,
    );
    const desc = screen.getByTestId(
      "edit-description-input",
    ) as HTMLInputElement;
    await userEvent.clear(desc);
    await userEvent.type(desc, "Updated description");
    await userEvent.click(screen.getByTestId("edit-submit-btn"));
    await waitFor(() => expect(patchMutate).toHaveBeenCalledTimes(1));
    const [body] = patchMutate.mock.calls[0] as [PatchCouponRequest];
    expect(body.description).toBe("Updated description");
    // Other unchanged fields are absent.
    expect(body.active).toBeUndefined();
    expect(body.durationDays).toBeUndefined();
    expect(body.useCount).toBeUndefined();
    expect(body.maxPerUser).toBeUndefined();
  });

  it("clearing description sends null on the wire", async () => {
    renderWithProviders(
      <AdminCouponDetailEdit coupon={coupon()} totalGrants={0} />,
    );
    const desc = screen.getByTestId(
      "edit-description-input",
    ) as HTMLInputElement;
    await userEvent.clear(desc);
    await userEvent.click(screen.getByTestId("edit-submit-btn"));
    await waitFor(() => expect(patchMutate).toHaveBeenCalledTimes(1));
    const [body] = patchMutate.mock.calls[0] as [PatchCouponRequest];
    expect(body.description).toBeNull();
  });

  it("flagging the active checkbox off sends active=false", async () => {
    renderWithProviders(
      <AdminCouponDetailEdit coupon={coupon()} totalGrants={0} />,
    );
    await userEvent.click(screen.getByTestId("edit-active-checkbox"));
    await userEvent.click(screen.getByTestId("edit-submit-btn"));
    await waitFor(() => expect(patchMutate).toHaveBeenCalledTimes(1));
    const [body] = patchMutate.mock.calls[0] as [PatchCouponRequest];
    expect(body.active).toBe(false);
  });

  it("shows a paired-signup-window error inline without firing the mutation", async () => {
    renderWithProviders(
      <AdminCouponDetailEdit coupon={coupon()} totalGrants={0} />,
    );
    await userEvent.type(
      screen.getByTestId("edit-signup-start-input"),
      "2026-01-01",
    );
    await userEvent.click(screen.getByTestId("edit-submit-btn"));
    expect(screen.getByTestId("edit-signup-window-error")).toBeInTheDocument();
    expect(patchMutate).not.toHaveBeenCalled();
  });

  it("does NOT call mutate when nothing changed", async () => {
    renderWithProviders(
      <AdminCouponDetailEdit coupon={coupon()} totalGrants={0} />,
    );
    await userEvent.click(screen.getByTestId("edit-submit-btn"));
    // The form short-circuits and just sets savedAt.
    expect(patchMutate).not.toHaveBeenCalled();
  });
});
