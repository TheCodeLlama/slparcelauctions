"use client";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useCallback } from "react";
import { Button } from "@/components/ui/Button";
import { useAdminCoupon } from "@/hooks/admin/useAdminCoupon";
import { useDeleteAdminCoupon } from "@/hooks/admin/useDeleteAdminCoupon";
import { useAdminCouponGrants } from "@/hooks/admin/useAdminCouponGrants";
import { cn } from "@/lib/cn";
import type { CouponDto } from "@/types/coupon";
import { AdminCouponDetailOverview } from "./AdminCouponDetailOverview";
import { AdminCouponDetailGrants } from "./AdminCouponDetailGrants";
import { AdminCouponDetailEdit } from "./AdminCouponDetailEdit";

type Tab = "overview" | "grants" | "edit";

/**
 * Status pill derivation. Lives at module scope (not inside the
 * component body) so the `Date.now()` impurity check stays outside
 * React's render path — the lint rule (`react-hooks/purity`) only
 * looks at function bodies that React executes itself.
 */
function statusOf(coupon: CouponDto): {
  label: string;
  className: string;
} {
  if (!coupon.active) {
    return { label: "Inactive", className: "bg-bg-muted text-fg-muted" };
  }
  if (
    coupon.redeemableUntil !== null &&
    new Date(coupon.redeemableUntil).getTime() <= Date.now()
  ) {
    return { label: "Expired", className: "bg-danger-bg text-danger" };
  }
  return { label: "Active", className: "bg-success-bg text-success" };
}

const TABS: Array<{ id: Tab; label: string }> = [
  { id: "overview", label: "Overview" },
  { id: "grants", label: "Grants" },
  { id: "edit", label: "Edit" },
];

function isTab(s: string | null | undefined): s is Tab {
  return s === "overview" || s === "grants" || s === "edit";
}

type Props = {
  publicId: string;
};

/**
 * Top-level wrapper for the admin coupon detail page. Owns the coupon
 * fetch, the tab nav, and the URL-sync logic (browser back / forward
 * navigates between tabs). The three tab components own their own
 * sub-queries and mutations; this wrapper only passes the resolved
 * `CouponDto` down.
 *
 * Aggregate counters (`totalGrants`, `activeGrants`) come from a
 * cheap `useAdminCouponGrants(..., {size: 1})` call so the page does
 * not need a separate `/stats` endpoint. The `size: 1` server response
 * carries `totalElements` which is the count we want; for
 * `activeGrants` we add the `state=ACTIVE` filter.
 */
export function AdminCouponDetail({ publicId }: Props) {
  const router = useRouter();
  const searchParams = useSearchParams();

  const rawTab = searchParams?.get("tab") ?? null;
  const activeTab: Tab = isTab(rawTab) ? rawTab : "overview";

  const couponQuery = useAdminCoupon(publicId);
  const totalsAllQuery = useAdminCouponGrants(publicId, { size: 1 });
  const totalsActiveQuery = useAdminCouponGrants(publicId, {
    size: 1,
    state: "ACTIVE",
  });

  const setTab = useCallback(
    (tab: Tab) => {
      const sp = new URLSearchParams();
      // Preserve any non-tab params the caller might have added in
      // future (we currently emit none, but stay defensive).
      if (searchParams) {
        searchParams.forEach((value, key) => {
          if (key !== "tab") sp.set(key, value);
        });
      }
      if (tab !== "overview") sp.set("tab", tab);
      const qs = sp.toString();
      router.replace(
        qs
          ? `/admin/coupons/${publicId}?${qs}`
          : `/admin/coupons/${publicId}`,
        { scroll: false },
      );
    },
    [publicId, router, searchParams],
  );

  const deleteMutation = useDeleteAdminCoupon(publicId);

  if (couponQuery.isLoading) {
    return (
      <div
        className="py-12 text-sm text-fg-muted"
        data-testid="coupon-detail-loading"
      >
        Loading coupon...
      </div>
    );
  }

  if (couponQuery.isError || !couponQuery.data) {
    return (
      <div
        className="py-12 text-sm text-danger"
        data-testid="coupon-detail-error"
      >
        Could not load coupon. Refresh to retry.
      </div>
    );
  }

  const coupon = couponQuery.data;
  const totalGrants = totalsAllQuery.data?.totalElements ?? 0;
  const activeGrants = totalsActiveQuery.data?.totalElements ?? 0;
  const statusPill = statusOf(coupon);

  function handleDelete() {
    const msg =
      totalGrants === 0
        ? "Delete this coupon? It has no grants, so the record will be removed permanently."
        : "Archive this coupon? It has existing grants and will be marked inactive with a closed redemption window.";
    if (typeof window !== "undefined" && !window.confirm(msg)) return;
    deleteMutation.mutate();
  }

  return (
    <div data-testid="admin-coupon-detail">
      <div className="mb-4 text-xs text-fg-muted">
        <Link
          href="/admin/coupons"
          className="hover:underline"
          data-testid="back-to-list"
        >
          Coupons
        </Link>
        <span className="mx-1.5">/</span>
        <span className="text-fg font-mono">{coupon.code}</span>
      </div>

      <div className="mb-6 flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-3">
            <h1
              className="text-2xl font-semibold font-mono"
              data-testid="coupon-code"
            >
              {coupon.code}
            </h1>
            <span
              className={cn(
                "inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold",
                statusPill.className,
              )}
              data-testid="coupon-status-pill"
            >
              {statusPill.label}
            </span>
          </div>
          {coupon.description && (
            <p className="mt-1 text-sm text-fg-muted">{coupon.description}</p>
          )}
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="secondary"
            size="sm"
            onClick={handleDelete}
            disabled={deleteMutation.isPending}
            loading={deleteMutation.isPending}
            data-testid="coupon-delete-btn"
          >
            {totalGrants === 0 ? "Delete" : "Archive"}
          </Button>
        </div>
      </div>

      <div
        className="mb-4 flex border-b border-border-subtle overflow-x-auto"
        role="tablist"
        data-testid="coupon-tabs-nav"
      >
        {TABS.map(({ id, label }) => (
          <button
            key={id}
            role="tab"
            aria-selected={activeTab === id}
            data-testid={`coupon-tab-${id}`}
            onClick={() => setTab(id)}
            className={cn(
              "px-4 py-2.5 text-sm whitespace-nowrap border-b-2 transition-colors",
              activeTab === id
                ? "border-brand text-brand font-medium"
                : "border-transparent text-fg-muted hover:text-fg",
            )}
          >
            {label}
          </button>
        ))}
      </div>

      {activeTab === "overview" && (
        <AdminCouponDetailOverview
          coupon={coupon}
          totalGrants={totalGrants}
          activeGrants={activeGrants}
        />
      )}
      {activeTab === "grants" && (
        <AdminCouponDetailGrants couponPublicId={coupon.publicId} />
      )}
      {activeTab === "edit" && (
        <AdminCouponDetailEdit coupon={coupon} totalGrants={totalGrants} />
      )}
    </div>
  );
}
