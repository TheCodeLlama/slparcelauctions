"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { ChevronDown, ChevronUp, Tag } from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import { useProspectiveDiscounts } from "@/hooks/useProspectiveDiscounts";
import { useRedeemCoupon } from "@/hooks/useRedeemCoupon";
import { redeemErrorMessageFor } from "@/components/coupons/copy";

export interface CreateListingCouponSummaryProps {
  /**
   * Platform listing-fee default in L$, used as the "before" value when a
   * coupon discount is applied. Defaults to the spec value (L$100); the
   * parent may pass the live `useListingFeeConfig` reading to stay in
   * sync with backend config changes.
   */
  defaultFeeLindens?: number;
  /**
   * Platform commission rate as a BigDecimal-encoded fractional string
   * (e.g. "0.05" = 5%), used as the "before" value when a coupon
   * discount is applied. Defaults to "0.05".
   */
  defaultCommissionRate?: string;
  className?: string;
}

/**
 * Format an L$ amount with thousands separators (e.g. "L$1,000").
 */
function formatLindens(amount: number): string {
  return `L$${amount.toLocaleString()}`;
}

/**
 * Convert a backend fractional rate (e.g. "0.025") into a display
 * percent ("2.5%"). Strips trailing zeros and a dangling decimal point
 * so "0.05" becomes "5%" not "5.0%". Matches `CouponGrantCard`'s
 * internal helper so coupon copy reads consistently across surfaces.
 */
function formatRateAsPercent(fractional: string): string {
  const n = Number(fractional);
  if (!Number.isFinite(n)) return `${fractional}%`;
  const pct = n * 100;
  const s = pct.toFixed(2).replace(/\.?0+$/, "");
  return `${s}%`;
}

/**
 * Create-listing summary block: two before/after pricing rows
 * (listing fee + commission) with strike-through "default" prices when
 * the seller has an eligible coupon grant, plus a collapsible "Have a
 * code?" inline-redemption expander. Redeem success invalidates the
 * prospective-discounts query so the badges refresh in place.
 *
 * Defaults to L$100 + 5% so the parent can render the component
 * without sourcing the live config; pass `defaultFeeLindens` from
 * `useListingFeeConfig()` for full fidelity.
 *
 * Spec: docs/superpowers/specs/2026-05-20-coupon-codes-design.md §8.
 */
export function CreateListingCouponSummary({
  defaultFeeLindens = 100,
  defaultCommissionRate = "0.05",
  className,
}: CreateListingCouponSummaryProps) {
  const [expanded, setExpanded] = useState(false);
  const [code, setCode] = useState("");

  const discounts = useProspectiveDiscounts();
  const redeem = useRedeemCoupon();

  const trimmedCode = code.trim();
  const canSubmit = trimmedCode.length > 0 && !redeem.isPending;

  const submitRedeem = () => {
    if (!canSubmit) return;
    redeem.mutate(trimmedCode, {
      onSuccess: () => setCode(""),
    });
  };

  const inlineError = redeem.isError ? redeemErrorMessageFor(redeem.error) : null;

  // Resolved rows. When the prospective query has no data yet (initial
  // load) we still render the platform defaults so the seller never
  // sees a blank summary — only the badge wins/loses on refresh.
  const data = discounts.data;
  const feeLindens = data?.listingFeeLindens ?? defaultFeeLindens;
  const feeCouponCode = data?.listingFeeCouponCode ?? null;
  const feeDiscounted = feeCouponCode !== null && feeLindens !== defaultFeeLindens;

  const commissionRate = data?.commissionRate ?? defaultCommissionRate;
  const commissionCouponCode = data?.commissionCouponCode ?? null;
  const commissionDiscounted =
    commissionCouponCode !== null && commissionRate !== defaultCommissionRate;

  return (
    <section
      aria-labelledby="listing-coupon-summary-heading"
      className={cn(
        "flex flex-col gap-3 rounded-lg border border-border-subtle bg-bg-subtle p-4",
        className,
      )}
    >
      <h2
        id="listing-coupon-summary-heading"
        className="text-sm font-semibold tracking-tight text-fg"
      >
        Listing fee &amp; commission
      </h2>

      <dl className="flex flex-col gap-2 text-sm">
        <div className="flex items-baseline justify-between gap-2">
          <dt className="text-fg-muted">Listing fee</dt>
          <dd className="flex items-baseline gap-2 text-fg">
            {feeDiscounted ? (
              <>
                <s className="text-fg-muted">{formatLindens(defaultFeeLindens)}</s>
                <span className="font-semibold">{formatLindens(feeLindens)}</span>
                <CodeBadge code={feeCouponCode ?? ""} />
              </>
            ) : (
              <span className="font-semibold">{formatLindens(feeLindens)}</span>
            )}
          </dd>
        </div>

        <div className="flex items-baseline justify-between gap-2">
          <dt className="text-fg-muted">Commission</dt>
          <dd className="flex items-baseline gap-2 text-fg">
            {commissionDiscounted ? (
              <>
                <s className="text-fg-muted">
                  {formatRateAsPercent(defaultCommissionRate)}
                </s>
                <span className="font-semibold">
                  {formatRateAsPercent(commissionRate)}
                </span>
                <CodeBadge code={commissionCouponCode ?? ""} />
              </>
            ) : (
              <span className="font-semibold">
                {formatRateAsPercent(commissionRate)}
              </span>
            )}
          </dd>
        </div>
      </dl>

      <div className="border-t border-border-subtle pt-2">
        <button
          type="button"
          className="flex w-full items-center justify-between text-sm font-medium text-fg-muted hover:text-fg"
          onClick={() => setExpanded((v) => !v)}
          aria-expanded={expanded}
          aria-controls="listing-coupon-redeem-panel"
        >
          <span>Have a code? Click to redeem</span>
          {expanded ? (
            <ChevronUp className="size-4" aria-hidden="true" />
          ) : (
            <ChevronDown className="size-4" aria-hidden="true" />
          )}
        </button>

        {expanded && (
          <div
            id="listing-coupon-redeem-panel"
            className="mt-3 flex flex-col gap-2 sm:flex-row sm:items-start"
          >
            <div className="flex-1">
              <Input
                type="text"
                value={code}
                onChange={(e) => setCode(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    e.preventDefault();
                    submitRedeem();
                  }
                }}
                placeholder="Enter a coupon code"
                aria-label="Coupon code"
                autoCapitalize="characters"
                error={inlineError ?? undefined}
                disabled={redeem.isPending}
              />
            </div>
            <Button
              variant="primary"
              onClick={submitRedeem}
              disabled={!canSubmit}
              loading={redeem.isPending}
            >
              Apply
            </Button>
          </div>
        )}
      </div>
    </section>
  );
}

/**
 * Small inline badge showing which coupon code drove a discounted
 * value. Kept inline because no other surface renders this exact shape.
 */
function CodeBadge({ code }: { code: string }) {
  return (
    <span className="inline-flex items-center gap-1 rounded-full bg-brand/10 px-2 py-0.5 text-xs font-medium text-brand">
      <Tag className="size-3" aria-hidden="true" />
      {code}
    </span>
  );
}
