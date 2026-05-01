"use client";

import Link from "next/link";
import { AlertTriangle, ArrowRight, Wallet } from "@/components/ui/icons";
import { useCurrentUser } from "@/lib/user";
import { useWallet } from "@/lib/wallet/use-wallet";
import { useWalletWsSubscription } from "@/lib/wallet/use-wallet-ws";
import { cn } from "@/lib/cn";

function formatLindens(amount: number): string {
  return `L$ ${amount.toLocaleString()}`;
}

/**
 * Persistent wallet pill rendered in the global {@link Header} between the
 * curator-tray slot and {@link NotificationBell}. Verified-only — returns
 * {@code null} for guests and unverified users since they have no wallet.
 *
 * Desktop (md and up): icon + tabular available L$ + hover/focus popover
 * with full Balance / Reserved / Available breakdown and a penalty warning
 * row when {@code penaltyOwed > 0}. The popover is reveal-only-via-Tailwind
 * (group/peer mechanics) so SSR + hydration stay deterministic — no
 * useState gating for hover.
 *
 * Mobile (below md): icon-only tap target that navigates to /wallet. No
 * popover, no inline text — the breakdown lives on the dedicated route.
 *
 * Loading state: renders {@code L$ 0} until the first {@link useWallet}
 * fetch resolves. No skeleton, no spinner — the indicator is always
 * present-and-readable to avoid layout shift on every page load. The
 * 30 s polling cadence on {@link useWallet} keeps it fresh until Phase 8
 * wires the WS topic.
 */
export function HeaderWalletIndicator() {
  const { data: user } = useCurrentUser();
  const verified = user?.verified === true;
  const { data: wallet } = useWallet(verified);
  // Live updates via /user/queue/wallet — empty destination short-circuits
  // the subscription for guests / unverified users so the STOMP client doesn't
  // hold a frame open for them.
  useWalletWsSubscription(verified);

  if (!verified) return null;

  const available = wallet?.available ?? 0;
  const balance = wallet?.balance ?? 0;
  const reserved = wallet?.reserved ?? 0;
  const penaltyOwed = wallet?.penaltyOwed ?? 0;
  const queuedForWithdrawal = wallet?.queuedForWithdrawal ?? 0;

  return (
    <Link
      href="/wallet"
      aria-label="Wallet"
      className={cn(
        "group relative flex items-center rounded-md transition-colors",
        "px-2 py-1 hover:bg-surface-container-low focus:outline-none",
        "focus-visible:ring-2 focus-visible:ring-primary",
      )}
    >
      <Wallet className="h-4 w-4 text-primary md:mr-1.5" aria-hidden />
      <span className="hidden md:inline text-sm font-medium tabular-nums text-on-surface">
        {formatLindens(available)}
      </span>
      <div
        role="tooltip"
        className={cn(
          "hidden md:block",
          "absolute right-0 top-full mt-1 w-64 z-50",
          "bg-surface-container rounded-lg shadow-elevated p-3",
          "opacity-0 invisible pointer-events-none",
          "group-hover:opacity-100 group-hover:visible group-hover:pointer-events-auto",
          "group-focus-visible:opacity-100 group-focus-visible:visible group-focus-visible:pointer-events-auto",
          "transition-opacity",
        )}
      >
        <div className="text-xs uppercase tracking-wide text-on-surface-variant mb-2">
          Wallet
        </div>
        <dl className="text-sm space-y-1">
          <div className="flex justify-between">
            <dt className="text-on-surface-variant">Balance</dt>
            <dd className="tabular-nums text-on-surface">{formatLindens(balance)}</dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-on-surface-variant">Reserved</dt>
            <dd className="tabular-nums text-on-surface">{formatLindens(reserved)}</dd>
          </div>
          {queuedForWithdrawal > 0 && (
            <div className="flex justify-between">
              <dt className="text-on-surface-variant">Queued for Withdrawal</dt>
              <dd className="tabular-nums text-on-surface">
                {formatLindens(queuedForWithdrawal)}
              </dd>
            </div>
          )}
          <div className="flex justify-between font-medium">
            <dt className="text-on-surface">Available</dt>
            <dd className="tabular-nums text-on-surface">{formatLindens(available)}</dd>
          </div>
        </dl>
        {penaltyOwed > 0 && (
          <div className="mt-3 flex gap-2 rounded-md bg-warning-container/40 border border-warning p-2 text-xs">
            <AlertTriangle className="h-4 w-4 text-warning shrink-0 mt-0.5" aria-hidden />
            <div>
              <div className="text-on-warning-container">
                Penalty owed: {formatLindens(penaltyOwed)}
              </div>
              <div className="mt-1 inline-flex items-center gap-1 underline text-on-warning-container">
                Pay penalty
                <ArrowRight className="h-3 w-3" aria-hidden />
              </div>
            </div>
          </div>
        )}
        <div className="mt-3 inline-flex items-center gap-1 text-xs text-primary underline">
          View activity
          <ArrowRight className="h-3 w-3" aria-hidden />
        </div>
      </div>
    </Link>
  );
}
