"use client";

import { Wallet } from "@/components/ui/icons";
import { Card } from "@/components/ui/Card";

function formatLindens(amount: number): string {
  return `L$${amount.toLocaleString()}`;
}

export interface GroupWalletBalanceCardProps {
  balance: number;
  reserved: number;
  available: number;
  /** Called when the user activates the withdraw action. */
  onWithdraw?: () => void;
  /** Whether the caller has the WITHDRAW_FROM_GROUP_WALLET permission. */
  canWithdraw?: boolean;
}

/**
 * Displays the group wallet balance summary: available (dominant), balance, and
 * reserved (hidden when zero per spec — reservation is always zero in D scope).
 *
 * The "Withdraw" button calls {@code onWithdraw} when present and the caller
 * has the required permission.
 */
export function GroupWalletBalanceCard({
  balance,
  reserved,
  available,
  onWithdraw,
  canWithdraw = false,
}: GroupWalletBalanceCardProps) {
  return (
    <Card data-testid="group-wallet-balance-card">
      <Card.Body>
        <div className="flex items-start gap-3 mb-4">
          <Wallet className="h-5 w-5 text-brand mt-0.5 shrink-0" aria-hidden="true" />
          <h2 className="text-sm font-semibold tracking-tight text-fg">
            Group Wallet
          </h2>
        </div>

        <div className="mb-4">
          <div className="text-xs uppercase tracking-wide text-fg-muted mb-1">
            Available
          </div>
          <div
            className="text-3xl font-semibold tabular-nums text-fg"
            data-testid="balance-available"
          >
            {formatLindens(available)}
          </div>
        </div>

        <div className="flex flex-wrap gap-x-6 gap-y-2 text-sm mb-5">
          <div>
            <span className="text-fg-muted">Balance </span>
            <span className="tabular-nums text-fg" data-testid="balance-total">
              {formatLindens(balance)}
            </span>
          </div>
          {reserved > 0 && (
            <div>
              <span className="text-fg-muted">Reserved </span>
              <span
                className="tabular-nums text-fg"
                data-testid="balance-reserved"
              >
                {formatLindens(reserved)}
              </span>
            </div>
          )}
        </div>

        {canWithdraw && onWithdraw && (
          <button
            type="button"
            onClick={onWithdraw}
            disabled={available <= 0}
            className="inline-flex items-center justify-center gap-1.5 rounded-sm border font-medium transition-colors disabled:opacity-50 disabled:pointer-events-none focus:outline-none focus-visible:ring-2 focus-visible:ring-brand h-9 px-4 text-sm bg-surface-raised text-fg border-border hover:bg-bg-hover hover:border-border-strong"
            data-testid="withdraw-button"
          >
            Withdraw
          </button>
        )}
      </Card.Body>
    </Card>
  );
}
