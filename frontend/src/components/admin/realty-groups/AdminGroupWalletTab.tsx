"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import type { GroupWallet } from "@/types/realty";
import { getAdminGroupWallet } from "@/lib/api/adminRealtyGroupWallet";
import { AdminWalletAdjustCard } from "./AdminWalletAdjustCard";

export interface AdminGroupWalletTabProps {
  /** Group public UUID. */
  groupPublicId: string;
}

function formatLindens(amount: number): string {
  const sign = amount < 0 ? "-" : "";
  return `${sign}L$${Math.abs(amount).toLocaleString()}`;
}

/**
 * Sub-project G §7.2 — admin Wallet tab on
 * {@code /admin/realty-groups/[publicId]}. Pre-fetches the current wallet
 * snapshot via the admin-tier {@code GET /api/v1/admin/realty-groups/...
 * /wallet} endpoint (which bypasses the leader-tier
 * {@code VIEW_GROUP_TRANSACTIONS} permission) so the balance card renders
 * immediately. After each {@link AdminWalletAdjustCard} adjustment, the
 * card's returned wallet snapshot supersedes the cached read.
 *
 * <p>Invalidates the leader-side wallet/ledger queries after an adjustment
 * so any concurrent leader view in the same session refreshes.
 */
export function AdminGroupWalletTab({ groupPublicId }: AdminGroupWalletTabProps) {
  const queryClient = useQueryClient();
  const [latestWallet, setLatestWallet] = useState<GroupWallet | null>(null);

  const walletQuery = useQuery({
    queryKey: ["admin", "realty-groups", groupPublicId, "wallet"],
    queryFn: () => getAdminGroupWallet(groupPublicId),
    staleTime: 5_000,
  });

  // The latest adjustment's response wins if the admin has acted in-session;
  // otherwise show the freshly-fetched read.
  const wallet = latestWallet ?? walletQuery.data ?? null;

  function handleAdjusted(updated: GroupWallet) {
    setLatestWallet(updated);
    queryClient.invalidateQueries({
      queryKey: ["admin", "realty-groups", groupPublicId, "wallet"],
    });
    // Best-effort invalidation: refreshes any leader-side wallet/ledger view
    // mounted in the same browser session. The keys mirror the existing
    // useGroupWallet / useGroupLedger query keys.
    queryClient.invalidateQueries({
      queryKey: ["realty", "group", groupPublicId, "wallet"],
    });
    queryClient.invalidateQueries({
      queryKey: ["realty", "group", groupPublicId, "ledger"],
    });
  }

  return (
    <div
      className="flex flex-col gap-4"
      data-testid="admin-group-wallet-tab"
    >
      {wallet && (
        <section
          className="rounded-lg border border-border bg-surface-raised p-4"
          data-testid="admin-group-wallet-tab-balance"
        >
          <h3 className="text-sm font-semibold text-fg">Current balance</h3>
          <dl className="mt-3 grid grid-cols-3 gap-2 text-sm">
            <div>
              <dt className="text-xs uppercase tracking-wide text-fg-muted">
                Available
              </dt>
              <dd
                className="font-semibold tabular-nums text-fg"
                data-testid="admin-group-wallet-tab-available"
              >
                {formatLindens(wallet.available)}
              </dd>
            </div>
            <div>
              <dt className="text-xs uppercase tracking-wide text-fg-muted">
                Balance
              </dt>
              <dd
                className="font-semibold tabular-nums text-fg"
                data-testid="admin-group-wallet-tab-balance-value"
              >
                {formatLindens(wallet.balance)}
              </dd>
            </div>
            <div>
              <dt className="text-xs uppercase tracking-wide text-fg-muted">
                Reserved
              </dt>
              <dd
                className="font-semibold tabular-nums text-fg"
                data-testid="admin-group-wallet-tab-reserved"
              >
                {formatLindens(wallet.reserved)}
              </dd>
            </div>
          </dl>
        </section>
      )}
      <AdminWalletAdjustCard
        publicId={groupPublicId}
        onAdjusted={handleAdjusted}
      />
    </div>
  );
}
