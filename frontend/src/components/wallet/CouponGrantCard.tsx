"use client";

import { Card } from "@/components/ui/Card";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { Tag } from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import type {
  CouponDiscountDto,
  CouponGrantDto,
  CouponGrantState,
} from "@/types/coupon";

interface CouponGrantCardProps {
  grant: CouponGrantDto;
  className?: string;
}

/**
 * Human summary of a single discount line. `value` arrives as a
 * BigDecimal-encoded string from the backend; for OVERRIDE on
 * COMMISSION_RATE the spec stores fractional rates (e.g. "0.025" =
 * 2.5%) so we multiply by 100 for display. LISTING_FEE uses raw L$.
 *
 * Exported so the create-listing summary card (Task 16) can reuse it.
 */
export function summarizeDiscount(d: CouponDiscountDto): string {
  if (d.target === "LISTING_FEE") {
    if (d.op === "OVERRIDE") {
      return d.value === "0" ? "Free listings" : `L$${d.value} listings`;
    }
    if (d.op === "PERCENT_OFF") {
      return `${d.value}% off listing fees`;
    }
    return `L$${d.value} off listing fee`;
  }
  // COMMISSION_RATE
  if (d.op === "OVERRIDE") {
    const pct = formatCommissionFraction(d.value);
    return pct === "0%" ? "Zero commission" : `${pct} commission`;
  }
  if (d.op === "PERCENT_OFF") {
    return `${d.value}% off commission`;
  }
  // FLAT_OFF on COMMISSION_RATE = percentage-points off
  return `${formatCommissionFraction(d.value)} off commission`;
}

/**
 * Convert a backend fractional rate (e.g. "0.025") into a display
 * percent ("2.5%"). Strips trailing zeros and a dangling decimal
 * point so "0.05" becomes "5%" not "5.0%".
 */
function formatCommissionFraction(fractional: string): string {
  const n = Number(fractional);
  if (!Number.isFinite(n)) return `${fractional}%`;
  const pct = n * 100;
  // Up to 2 decimal places, strip trailing zeros
  const s = pct.toFixed(2).replace(/\.?0+$/, "");
  return `${s}%`;
}

/**
 * Render "no expiry", "expires in 13 days", "expires in 4 hours",
 * "expires soon", or "expired" for a single grant. Coarse buckets are
 * fine because the wallet UI does not need minute granularity.
 *
 * Returns `expiry` text (e.g. "expires in 4 hours") and an `expired`
 * flag the caller uses to choose tone.
 */
export function expiryLabel(expiresAt: string | null): {
  text: string;
  expired: boolean;
} {
  if (expiresAt === null) return { text: "Never expires", expired: false };
  const then = new Date(expiresAt).getTime();
  if (Number.isNaN(then)) return { text: "Never expires", expired: false };
  const now = Date.now();
  const diffMs = then - now;
  if (diffMs <= 0) return { text: "Expired", expired: true };
  const minute = 60_000;
  const hour = 60 * minute;
  const day = 24 * hour;
  if (diffMs >= day) {
    const days = Math.floor(diffMs / day);
    return {
      text: `Expires in ${days} day${days === 1 ? "" : "s"}`,
      expired: false,
    };
  }
  if (diffMs >= hour) {
    const hours = Math.floor(diffMs / hour);
    return {
      text: `Expires in ${hours} hour${hours === 1 ? "" : "s"}`,
      expired: false,
    };
  }
  return { text: "Expires soon", expired: false };
}

const STATE_TONE: Record<
  CouponGrantState,
  "default" | "success" | "warning" | "danger"
> = {
  ACTIVE: "success",
  EXHAUSTED: "default",
  EXPIRED: "default",
  REVOKED: "danger",
};

const STATE_LABEL: Record<CouponGrantState, string> = {
  ACTIVE: "Active",
  EXHAUSTED: "Used up",
  EXPIRED: "Expired",
  REVOKED: "Revoked",
};

/**
 * Single-grant presentation card: the code, a one-line discount
 * summary per discount in the bundle, expiry, remaining count, and a
 * status badge for non-ACTIVE grants.
 *
 * Stateless — the consumer (WalletCouponsCard) decides which list to
 * render the card in (active vs history).
 */
export function CouponGrantCard({ grant, className }: CouponGrantCardProps) {
  const expiry = expiryLabel(grant.expiresAt);
  const showStateBadge = grant.state !== "ACTIVE";

  return (
    <Card className={cn("border border-border", className)}>
      <Card.Body className="flex flex-col gap-2">
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-center gap-2">
            <Tag
              className="size-4 text-brand shrink-0"
              aria-hidden="true"
            />
            <span className="text-base font-semibold tracking-tight text-fg">
              {grant.code}
            </span>
          </div>
          {showStateBadge && (
            <StatusBadge tone={STATE_TONE[grant.state]}>
              {STATE_LABEL[grant.state]}
            </StatusBadge>
          )}
        </div>

        <ul className="flex flex-col gap-0.5">
          {grant.discounts.map((d, i) => (
            <li
              key={`${d.target}-${d.op}-${i}`}
              className="text-sm text-fg"
            >
              {summarizeDiscount(d)}
            </li>
          ))}
        </ul>

        <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-fg-muted">
          <span className={cn(expiry.expired && "text-danger")}>
            {expiry.text}
          </span>
          {grant.remainingCount !== null && (
            <span>
              {grant.remainingCount} use
              {grant.remainingCount === 1 ? "" : "s"} remaining
            </span>
          )}
        </div>
      </Card.Body>
    </Card>
  );
}
