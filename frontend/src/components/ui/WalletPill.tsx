"use client";

import Link from "next/link";
import { AlertTriangle, ArrowRight } from "@/components/ui/icons";
import { useCurrentUser } from "@/lib/user";
import { useWallet } from "@/lib/wallet/use-wallet";
import { useWalletWsSubscription } from "@/lib/wallet/use-wallet-ws";
import { cn } from "@/lib/cn";

function formatLindens(amount: number): string {
  return `L$ ${amount.toLocaleString()}`;
}

/**
 * Persistent wallet pill rendered in the global Header. Verified-only —
 * returns null for guests and unverified users since they have no wallet.
 *
 * Reveal-only-via-Tailwind popover (group/peer mechanics) on hover/focus
 * shows the full Balance / Reserved / Queued / Available breakdown plus a
 * penalty warning row when penaltyOwed > 0. Loading state renders L$ 0
 * to avoid layout shift; useWallet polls every 30 s and the WS topic
 * delivers live updates via useWalletWsSubscription.
 */
export function WalletPill() {
  const { data: user } = useCurrentUser();
  const verified = user?.verified === true;
  const { data: wallet } = useWallet(verified);
  useWalletWsSubscription(verified);

  if (!verified) return null;

  const balance = wallet?.balance ?? 0;
  const reserved = wallet?.reserved ?? 0;
  const available = wallet?.available ?? 0;
  const penaltyOwed = wallet?.penaltyOwed ?? 0;
  const queuedForWithdrawal = wallet?.queuedForWithdrawal ?? 0;

  return (
    <Link
      href="/wallet"
      aria-label="Wallet"
      className={cn(
        "group relative hidden md:inline-flex items-center gap-2",
        "rounded-pill border border-border bg-bg-subtle",
        "px-3 py-1.5 text-sm font-semibold text-fg",
        "transition-colors hover:border-border-strong",
        "focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
      )}
    >
      <span
        aria-hidden
        className={cn(
          "grid h-[18px] w-[18px] place-items-center rounded-full",
          "bg-brand text-white text-[9px] font-bold leading-none"
        )}
      >
        L$
      </span>
      <span className="tabular-nums">{formatLindens(available)}</span>

      <div
        role="tooltip"
        className={cn(
          "absolute right-0 top-full z-50 mt-2 w-64 p-3",
          "rounded-lg border border-border bg-bg shadow-lg",
          "invisible pointer-events-none opacity-0",
          "transition-opacity",
          "group-hover:visible group-hover:pointer-events-auto group-hover:opacity-100",
          "group-focus-visible:visible group-focus-visible:pointer-events-auto group-focus-visible:opacity-100"
        )}
      >
        <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-fg-subtle">
          Wallet
        </div>
        <dl className="space-y-1 text-sm">
          <div className="flex justify-between">
            <dt className="text-fg-muted">Balance</dt>
            <dd className="tabular-nums text-fg">{formatLindens(balance)}</dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-fg-muted">Reserved</dt>
            <dd className="tabular-nums text-fg">{formatLindens(reserved)}</dd>
          </div>
          {queuedForWithdrawal > 0 && (
            <div className="flex justify-between">
              <dt className="text-fg-muted">Queued for withdrawal</dt>
              <dd className="tabular-nums text-fg">{formatLindens(queuedForWithdrawal)}</dd>
            </div>
          )}
          <div className="flex justify-between font-medium">
            <dt className="text-fg">Available</dt>
            <dd className="tabular-nums text-fg">{formatLindens(available)}</dd>
          </div>
        </dl>
        {penaltyOwed > 0 && (
          <div className={cn(
            "mt-3 flex gap-2 rounded-md border border-warning-flat/40 bg-warning-bg p-2 text-xs"
          )}>
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning-flat" aria-hidden />
            <div>
              <div className="text-warning-flat">
                Penalty owed: {formatLindens(penaltyOwed)}
              </div>
              <span className="mt-1 inline-flex items-center gap-1 underline text-warning-flat">
                Pay penalty
                <ArrowRight className="h-3 w-3" aria-hidden />
              </span>
            </div>
          </div>
        )}
        <span className="mt-3 inline-flex items-center gap-1 text-xs text-brand underline">
          View activity
          <ArrowRight className="h-3 w-3" aria-hidden />
        </span>
      </div>
    </Link>
  );
}
