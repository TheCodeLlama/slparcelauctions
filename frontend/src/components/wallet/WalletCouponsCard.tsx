"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { EmptyState } from "@/components/ui/EmptyState";
import { ChevronDown, ChevronUp, Tag } from "@/components/ui/icons";
import { useMyCoupons } from "@/hooks/useMyCoupons";
import { useRedeemCoupon } from "@/hooks/useRedeemCoupon";
import { CouponGrantCard } from "@/components/wallet/CouponGrantCard";
import { redeemErrorMessageFor } from "@/components/coupons/copy";

/**
 * Wallet-page coupons card. Three sections:
 *
 * <ol>
 *   <li>A redeem form (code input + button) that POSTs to
 *       `/api/v1/me/coupons/redeem` via `useRedeemCoupon` and renders
 *       inline error copy for each `CouponRedemptionErrorCode`.</li>
 *   <li>The list of ACTIVE grants (FIFO by `grantedAt`).</li>
 *   <li>A collapsible "History" expander with non-ACTIVE grants
 *       (most-recent-first).</li>
 * </ol>
 *
 * Spec: docs/superpowers/specs/2026-05-20-coupon-codes-design.md §8.
 */
export function WalletCouponsCard() {
  const [code, setCode] = useState("");
  const [historyOpen, setHistoryOpen] = useState(false);

  const { data: active = [], isPending: activePending } =
    useMyCoupons("active");
  const { data: history = [] } = useMyCoupons("history");
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

  return (
    <div className="bg-surface-raised rounded-lg shadow-sm p-6">
      <h3 className="text-lg font-semibold text-fg mb-3">Coupons</h3>

      <div className="flex flex-col gap-2 sm:flex-row sm:items-start">
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
          Redeem
        </Button>
      </div>

      <div className="mt-5">
        <h4 className="text-sm font-medium text-fg mb-2">
          Active coupons
        </h4>
        {activePending ? (
          <p className="text-sm text-fg-muted py-4 text-center">
            Loading coupons...
          </p>
        ) : active.length === 0 ? (
          <EmptyState
            icon={Tag}
            headline="No active coupons"
            description="Redeem a code above to start saving on listing fees and commission."
          />
        ) : (
          <ul className="flex flex-col gap-2">
            {active.map((grant) => (
              <li key={grant.publicId}>
                <CouponGrantCard grant={grant} />
              </li>
            ))}
          </ul>
        )}
      </div>

      {history.length > 0 && (
        <div className="mt-5 border-t border-border pt-4">
          <button
            type="button"
            className="flex w-full items-center justify-between text-sm font-medium text-fg-muted hover:text-fg"
            onClick={() => setHistoryOpen((v) => !v)}
            aria-expanded={historyOpen}
            aria-controls="wallet-coupons-history"
          >
            <span>
              History ({history.length})
            </span>
            {historyOpen ? (
              <ChevronUp className="size-4" aria-hidden="true" />
            ) : (
              <ChevronDown className="size-4" aria-hidden="true" />
            )}
          </button>
          {historyOpen && (
            <ul
              id="wallet-coupons-history"
              className="mt-3 flex flex-col gap-2"
            >
              {history.map((grant) => (
                <li key={grant.publicId}>
                  <CouponGrantCard grant={grant} />
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
