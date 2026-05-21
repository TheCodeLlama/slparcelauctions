"use client";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useAdminCoupons } from "@/hooks/admin/useAdminCoupons";
import { Pagination } from "@/components/ui/Pagination";
import { Button } from "@/components/ui/Button";
import type {
  CouponDiscountDto,
  CouponSummaryDto,
  DiscountTarget,
} from "@/types/coupon";

const PAGE_SIZE = 25;

type StatusFilter = "all" | "active" | "inactive";
type TargetFilter = "ALL" | DiscountTarget;

function parseStatus(raw: string | null): StatusFilter {
  if (raw === "active" || raw === "inactive") return raw;
  return "all";
}

function parseTarget(raw: string | null): TargetFilter {
  if (raw === "LISTING_FEE" || raw === "COMMISSION_RATE") return raw;
  return "ALL";
}

function statusOf(row: CouponSummaryDto): {
  label: string;
  className: string;
} {
  if (!row.active) {
    return { label: "Inactive", className: "bg-bg-muted text-fg-muted" };
  }
  if (
    row.redeemableUntil &&
    new Date(row.redeemableUntil).getTime() <= Date.now()
  ) {
    return { label: "Expired", className: "bg-danger-bg text-danger" };
  }
  return { label: "Active", className: "bg-success-bg text-success" };
}

function formatDiscount(d: CouponDiscountDto): string {
  const target = d.target === "LISTING_FEE" ? "Listing fee" : "Commission";
  switch (d.op) {
    case "OVERRIDE": {
      // For LISTING_FEE the value is L$; for COMMISSION_RATE it's a
      // fraction. Both are emitted as strings — preserve as-is to
      // avoid floating-point reformatting on a number the backend
      // intentionally kept as BigDecimal.
      if (d.target === "LISTING_FEE") return `${target}: L$ ${d.value}`;
      return `${target}: ${d.value}`;
    }
    case "PERCENT_OFF":
      return `${target}: -${d.value}%`;
    case "FLAT_OFF":
      return `${target}: -L$ ${d.value}`;
  }
}

function formatRedemptions(row: CouponSummaryDto): string {
  const cap =
    row.maxTotalRedemptions === null ? "unlimited" : row.maxTotalRedemptions;
  return `${row.totalGrants} / ${cap}`;
}

function formatExpires(iso: string | null): string {
  if (!iso) return "(never)";
  const d = new Date(iso);
  return d.toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
  });
}

function DiscountPills({ discounts }: { discounts: CouponDiscountDto[] }) {
  if (discounts.length === 0) {
    return <span className="text-[11px] text-fg-muted">(none)</span>;
  }
  const visible = discounts.slice(0, 2);
  const remaining = discounts.length - visible.length;
  return (
    <div className="flex flex-wrap gap-1">
      {visible.map((d, i) => (
        <span
          key={`${d.target}-${d.op}-${i}`}
          className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-medium bg-info-bg text-info"
        >
          {formatDiscount(d)}
        </span>
      ))}
      {remaining > 0 && (
        <span
          className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-medium bg-bg-muted text-fg-muted"
          title={discounts
            .slice(2)
            .map((d) => formatDiscount(d))
            .join(", ")}
        >
          +{remaining} more
        </span>
      )}
    </div>
  );
}

function SkeletonRows() {
  return (
    <div className="space-y-2 py-4" aria-busy="true">
      {Array.from({ length: 5 }).map((_, i) => (
        <div key={i} className="h-12 rounded-lg bg-bg-muted animate-pulse" />
      ))}
    </div>
  );
}

export function AdminCouponList() {
  const router = useRouter();
  const searchParams = useSearchParams();

  const q = searchParams?.get("q") ?? "";
  const status = parseStatus(searchParams?.get("status") ?? null);
  const target = parseTarget(searchParams?.get("discount_target") ?? null);
  const page = Math.max(0, parseInt(searchParams?.get("page") ?? "0", 10) || 0);

  const params = {
    q: q || undefined,
    active:
      status === "all" ? undefined : status === "active" ? true : false,
    discount_target: target === "ALL" ? undefined : target,
    page,
    size: PAGE_SIZE,
  };

  const { data, isLoading, isError } = useAdminCoupons(params);

  function buildUrl(overrides: {
    q?: string;
    status?: StatusFilter;
    target?: TargetFilter;
    page?: number;
  }): string {
    const sp = new URLSearchParams();
    const nextQ = overrides.q !== undefined ? overrides.q : q;
    const nextStatus = overrides.status ?? status;
    const nextTarget = overrides.target ?? target;
    const nextPage = overrides.page ?? 0;
    if (nextQ) sp.set("q", nextQ);
    if (nextStatus !== "all") sp.set("status", nextStatus);
    if (nextTarget !== "ALL") sp.set("discount_target", nextTarget);
    if (nextPage > 0) sp.set("page", String(nextPage));
    const qs = sp.toString();
    return qs ? `/admin/coupons?${qs}` : "/admin/coupons";
  }

  return (
    <div>
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-2xl font-semibold">Coupons</h1>
        <Link href="/admin/coupons/new">
          <Button
            variant="primary"
            size="sm"
            data-testid="create-coupon-btn"
          >
            + Create coupon
          </Button>
        </Link>
      </div>

      <div className="flex flex-wrap items-center gap-3 mb-4">
        <input
          key={q}
          defaultValue={q}
          placeholder="Search by code"
          data-testid="coupon-search-input"
          aria-label="Search coupons by code"
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              const v = (e.target as HTMLInputElement).value.trim();
              router.replace(buildUrl({ q: v || "", page: 0 }), {
                scroll: false,
              });
            }
          }}
          className="flex-1 min-w-[240px] rounded-lg bg-bg-muted px-4 py-2 text-sm text-fg placeholder:text-fg-muted ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand"
        />

        <label className="flex items-center gap-2 text-xs text-fg-muted">
          Status
          <select
            value={status}
            data-testid="status-select"
            aria-label="Filter by status"
            onChange={(e) =>
              router.replace(
                buildUrl({
                  status: e.target.value as StatusFilter,
                  page: 0,
                }),
                { scroll: false },
              )
            }
            className="rounded-md bg-bg-muted px-2 py-1.5 text-sm text-fg ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand"
          >
            <option value="all">All</option>
            <option value="active">Active</option>
            <option value="inactive">Inactive</option>
          </select>
        </label>

        <label className="flex items-center gap-2 text-xs text-fg-muted">
          Discount
          <select
            value={target}
            data-testid="target-select"
            aria-label="Filter by discount target"
            onChange={(e) =>
              router.replace(
                buildUrl({
                  target: e.target.value as TargetFilter,
                  page: 0,
                }),
                { scroll: false },
              )
            }
            className="rounded-md bg-bg-muted px-2 py-1.5 text-sm text-fg ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand"
          >
            <option value="ALL">Any</option>
            <option value="LISTING_FEE">Listing fee</option>
            <option value="COMMISSION_RATE">Commission</option>
          </select>
        </label>
      </div>

      {isLoading && <SkeletonRows />}

      {isError && (
        <div className="text-sm text-danger py-8">
          Could not load coupons. Refresh to retry.
        </div>
      )}

      {data && data.content.length === 0 && (
        <div
          className="py-12 text-center text-sm text-fg-muted"
          data-testid="coupons-empty"
        >
          No coupons match these filters.
        </div>
      )}

      {data && data.content.length > 0 && (
        <>
          <div
            className="overflow-x-auto rounded-lg border border-border-subtle"
            data-testid="coupons-table"
          >
            <table className="w-full text-sm">
              <thead className="bg-bg-subtle border-b border-border-subtle">
                <tr>
                  <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">
                    Code
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">
                    Description
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">
                    Status
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">
                    Discounts
                  </th>
                  <th className="px-3 py-2.5 text-right text-[11px] font-medium text-fg-muted">
                    Redemptions
                  </th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">
                    Expires
                  </th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((row) => {
                  const st = statusOf(row);
                  return (
                    <tr
                      key={row.publicId}
                      data-testid={`coupon-row-${row.publicId}`}
                      className="border-b border-border-subtle/50 hover:bg-bg-muted/50"
                    >
                      <td className="px-3 py-2.5">
                        <Link
                          href={`/admin/coupons/${row.publicId}`}
                          className="text-fg font-mono hover:underline"
                          data-testid={`coupon-code-${row.publicId}`}
                        >
                          {row.code}
                        </Link>
                      </td>
                      <td className="px-3 py-2.5 text-fg-muted text-[12px] max-w-[280px] truncate">
                        {row.description ?? (
                          <span className="text-fg-muted/60">(none)</span>
                        )}
                      </td>
                      <td className="px-3 py-2.5">
                        <span
                          className={`inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold ${st.className}`}
                        >
                          {st.label}
                        </span>
                      </td>
                      <td className="px-3 py-2.5">
                        <DiscountPills discounts={row.discounts} />
                      </td>
                      <td className="px-3 py-2.5 text-right font-mono text-fg text-[11px]">
                        {formatRedemptions(row)}
                      </td>
                      <td className="px-3 py-2.5 text-fg-muted text-[11px]">
                        {formatExpires(row.redeemableUntil)}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {data.totalPages > 1 && (
            <div className="mt-4">
              <Pagination
                page={data.number}
                totalPages={data.totalPages}
                onPageChange={(p) =>
                  router.replace(buildUrl({ page: p }), { scroll: false })
                }
              />
            </div>
          )}
        </>
      )}
    </div>
  );
}
