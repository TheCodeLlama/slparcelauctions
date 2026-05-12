"use client";

import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import type { GroupWallet } from "@/types/realty";
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
 * {@code /admin/realty-groups/[publicId]}. Houses the
 * {@link AdminWalletAdjustCard} and surfaces the wallet snapshot returned by
 * each successful adjustment so the admin sees the new balance inline.
 *
 * <p>There is no admin GET wallet endpoint -- the leader-tier
 * {@code GET /api/v1/realty/groups/{publicId}/wallet} requires
 * {@code VIEW_GROUP_TRANSACTIONS} on the caller, which an admin without
 * group membership does not have. So this tab does not pre-fetch the
 * balance; it shows whatever the most recent adjustment returned, and
 * invalidates the leader-side wallet query so any open leader view in the
 * same browser session refreshes after the admin acts.
 */
export function AdminGroupWalletTab({ groupPublicId }: AdminGroupWalletTabProps) {
  const queryClient = useQueryClient();
  const [latestWallet, setLatestWallet] = useState<GroupWallet | null>(null);

  function handleAdjusted(wallet: GroupWallet) {
    setLatestWallet(wallet);
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
      {latestWallet && (
        <section
          className="rounded-lg border border-border bg-surface-raised p-4"
          data-testid="admin-group-wallet-tab-balance"
        >
          <h3 className="text-sm font-semibold text-fg">
            Latest balance
          </h3>
          <p className="mt-1 text-xs text-fg-muted">
            Snapshot from the most recent adjustment in this session.
          </p>
          <dl className="mt-3 grid grid-cols-3 gap-2 text-sm">
            <div>
              <dt className="text-xs uppercase tracking-wide text-fg-muted">
                Available
              </dt>
              <dd
                className="font-semibold tabular-nums text-fg"
                data-testid="admin-group-wallet-tab-available"
              >
                {formatLindens(latestWallet.available)}
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
                {formatLindens(latestWallet.balance)}
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
                {formatLindens(latestWallet.reserved)}
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
