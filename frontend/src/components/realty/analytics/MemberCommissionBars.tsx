"use client";

import { cn } from "@/lib/cn";

/**
 * One row of the bar chart. Matches the {@code MemberCommissionRow} backend
 * shape but kept structurally typed here so this component stays decoupled
 * from the wire type and can be reused for forecast-style mocks.
 */
export type MemberCommissionBarRow = {
  memberPublicId: string;
  displayName: string;
  lifetimeLindens: number;
  last30DaysLindens: number;
};

export type MemberCommissionBarsProps = {
  rows: MemberCommissionBarRow[];
};

/**
 * Bar widths are bucketed to 5% increments so we can render each bar with a
 * static Tailwind utility class (`w-[NN%]`). The frontend `verify` guards
 * forbid inline {@code style} props in {@code src/components} except for an
 * allowlist (see {@code scripts/verify-no-inline-styles.sh}), so dynamic
 * width must come from a finite class set rather than {@code style.width}.
 * Bucketing to 5% is plenty for visual comparison and avoids growing the
 * Tailwind class output linearly with the data.
 */
const BAR_WIDTH_CLASSES = [
  "w-[0%]",
  "w-[5%]",
  "w-[10%]",
  "w-[15%]",
  "w-[20%]",
  "w-[25%]",
  "w-[30%]",
  "w-[35%]",
  "w-[40%]",
  "w-[45%]",
  "w-[50%]",
  "w-[55%]",
  "w-[60%]",
  "w-[65%]",
  "w-[70%]",
  "w-[75%]",
  "w-[80%]",
  "w-[85%]",
  "w-[90%]",
  "w-[95%]",
  "w-[100%]",
] as const;

/**
 * Map a raw ratio in {@code [0, 1]} to one of the 21 bucketed width classes.
 * Exported for unit-test coverage of the boundary math without scraping
 * the DOM.
 */
export function bucketWidthClass(ratio: number): string {
  if (!Number.isFinite(ratio) || ratio <= 0) return BAR_WIDTH_CLASSES[0];
  const clamped = Math.max(0, Math.min(1, ratio));
  const idx = Math.round(clamped * 20);
  return BAR_WIDTH_CLASSES[idx];
}

function formatLindens(amount: number): string {
  return `L$ ${amount.toLocaleString()}`;
}

/**
 * Horizontal bar chart of per-member lifetime + last-30-day commission
 * totals. One row per member; each bar is rendered as plain Tailwind divs
 * (no chart library per spec §15.2 — "simple visual indicator, not a
 * full chart"). The lifetime bar fills the row's track; the last-30-day
 * bar is drawn on top as an overlay so the recent slice visually nests
 * inside the lifetime slice.
 *
 * <p>Empty input renders nothing — the parent decides whether to swap in
 * a global empty-state component.
 */
export function MemberCommissionBars({ rows }: MemberCommissionBarsProps) {
  if (rows.length === 0) return null;

  const maxLifetime = rows.reduce(
    (acc, r) => (r.lifetimeLindens > acc ? r.lifetimeLindens : acc),
    0,
  );

  return (
    <ul
      className="flex flex-col gap-3"
      data-testid="member-commission-bars"
      aria-label="Per-member commission bars"
    >
      {rows.map((r) => {
        const lifetimeRatio = maxLifetime > 0 ? r.lifetimeLindens / maxLifetime : 0;
        const recentRatio = maxLifetime > 0 ? r.last30DaysLindens / maxLifetime : 0;
        const lifetimeWidth = bucketWidthClass(lifetimeRatio);
        const recentWidth = bucketWidthClass(recentRatio);
        return (
          <li
            key={r.memberPublicId}
            className="flex items-center gap-3"
            data-testid={`member-commission-bar-row-${r.memberPublicId}`}
          >
            <span
              className="text-xs text-fg w-40 shrink-0 truncate"
              title={r.displayName}
            >
              {r.displayName}
            </span>
            <div
              className="relative h-3 flex-1 rounded-full bg-bg-muted overflow-hidden"
              role="img"
              aria-label={`${r.displayName}: lifetime ${formatLindens(
                r.lifetimeLindens,
              )}, last 30 days ${formatLindens(r.last30DaysLindens)}`}
            >
              <div
                className={cn(
                  "absolute inset-y-0 left-0 bg-brand/40 rounded-full",
                  lifetimeWidth,
                )}
                data-testid={`member-commission-bar-lifetime-${r.memberPublicId}`}
                data-bar-width={lifetimeWidth}
              />
              <div
                className={cn(
                  "absolute inset-y-0 left-0 bg-brand rounded-full",
                  recentWidth,
                )}
                data-testid={`member-commission-bar-recent-${r.memberPublicId}`}
                data-bar-width={recentWidth}
              />
            </div>
            <span
              className="text-[11px] font-mono tabular-nums text-fg-muted w-28 text-right shrink-0"
              data-testid={`member-commission-bar-totals-${r.memberPublicId}`}
            >
              {formatLindens(r.lifetimeLindens)}
            </span>
          </li>
        );
      })}
    </ul>
  );
}
