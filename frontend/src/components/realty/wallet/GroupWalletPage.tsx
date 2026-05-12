"use client";

import { useState } from "react";
import { Card } from "@/components/ui/Card";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { useGroupWallet } from "@/hooks/realty/useGroupWallet";
import { GroupWalletBalanceCard } from "./GroupWalletBalanceCard";
import { GroupWalletLedgerTable } from "./GroupWalletLedgerTable";
import { GroupWithdrawModal } from "./GroupWithdrawModal";
import { LeaderTermsBlockBanner } from "./LeaderTermsBlockBanner";

export interface GroupWalletPageProps {
  /** Group public UUID from the URL segment. */
  publicId: string;
}

/**
 * Top-level group wallet client component. Orchestrates:
 *
 *  - {@link GroupWalletBalanceCard} — balance summary.
 *  - {@link LeaderTermsBlockBanner} — informational banner when leader ToS is pending.
 *  - {@link GroupWalletLedgerTable} — paginated transaction history.
 *  - {@link GroupWithdrawModal} — opened by the Withdraw button.
 *
 * Permission-denied (403 from GET wallet) renders a notice rather than crashing.
 * The withdraw CTA is gated via the backend response: the wallet endpoint returns
 * 403 when the caller lacks {@code VIEW_GROUP_TRANSACTIONS}. When the wallet loads
 * successfully we infer the caller can view; withdraw permission is held by anyone
 * who can reach the withdraw endpoint (the backend enforces it separately).
 *
 * In D scope there is no separate "can I withdraw" check on the wallet DTO —
 * we show the Withdraw button to all viewers and let the backend return 403 if
 * the caller lacks {@code WITHDRAW_FROM_GROUP_WALLET}. The modal's error handler
 * surfaces that case clearly.
 */
export function GroupWalletPage({ publicId }: GroupWalletPageProps) {
  const { data: wallet, isPending, error } = useGroupWallet(publicId);
  const [withdrawOpen, setWithdrawOpen] = useState(false);

  if (isPending) {
    return <LoadingSpinner label="Loading wallet..." />;
  }

  // 403 → permission denied notice
  if (error) {
    const status =
      error instanceof Error &&
      "status" in error &&
      typeof (error as { status?: number }).status === "number"
        ? (error as { status: number }).status
        : null;

    if (status === 403) {
      return (
        <div
          className="bg-surface-raised rounded-lg shadow-sm p-6"
          data-testid="permission-denied"
        >
          <h2 className="text-base font-semibold text-fg mb-2">
            Access restricted
          </h2>
          <p className="text-sm text-fg-muted">
            You do not have permission to view this group&apos;s wallet
            transactions. Ask the group leader to grant you the{" "}
            <strong>View Group Transactions</strong> permission.
          </p>
        </div>
      );
    }

    return (
      <div className="bg-surface-raised rounded-lg shadow-sm p-6" data-testid="wallet-error">
        <p className="text-fg">
          {error instanceof Error ? error.message : "Failed to load wallet."}
        </p>
      </div>
    );
  }

  if (!wallet) return null;

  return (
    <div className="flex flex-col gap-4" data-testid="group-wallet-page">
      {/* Leader ToS block banner — only shown when the wallet DTO indicates
          the leader hasn't accepted terms. In D scope the DTO doesn't include
          a leaderTermsAcceptedAt field directly, so we pass undefined which
          suppresses the banner. If a future DTO revision adds this field the
          banner will start working automatically. */}
      <LeaderTermsBlockBanner
        leaderTermsAcceptedAt={
          (wallet as unknown as { leaderTermsAcceptedAt?: string | null })
            .leaderTermsAcceptedAt
        }
      />

      <GroupWalletBalanceCard
        balance={wallet.balance}
        reserved={wallet.reserved}
        available={wallet.available}
        canWithdraw
        onWithdraw={() => setWithdrawOpen(true)}
      />

      <Card>
        <Card.Header>
          <h2 className="text-sm font-semibold tracking-tight text-fg">
            Transaction history
          </h2>
        </Card.Header>
        <Card.Body>
          <GroupWalletLedgerTable publicId={publicId} />
        </Card.Body>
      </Card>

      <GroupWithdrawModal
        open={withdrawOpen}
        onClose={() => setWithdrawOpen(false)}
        publicId={publicId}
        available={wallet.available}
      />
    </div>
  );
}
