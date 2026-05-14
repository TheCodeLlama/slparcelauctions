"use client";

import { useParams } from "next/navigation";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { GroupWalletPage } from "@/components/realty/wallet/GroupWalletPage";
import { useRealtyGroupBySlug } from "@/hooks/realty/useRealtyGroups";

/**
 * Wallet page for a realty group. Migrated from
 * `/realty/groups/[publicId]/wallet`. The body component is unchanged;
 * this wrapper resolves slug -> publicId so the existing publicId-keyed
 * service surface keeps working.
 *
 * Page-level permission gating lives in the parent `/groups/[slug]/layout`
 * (spec §5.4): non-members and members without `VIEW_GROUP_TRANSACTIONS`
 * (and who are not the leader) are redirected to `/groups/[slug]` before
 * this page renders.
 */
export default function GroupWalletPageRoute() {
  const params = useParams<{ slug: string }>();
  const slug = params?.slug;
  const group = useRealtyGroupBySlug(slug);

  if (group.isPending) {
    return <LoadingSpinner label="Loading wallet..." />;
  }
  if (!group.data) return null;

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-bold tracking-tight font-display">
          Group Wallet
        </h1>
        <p className="text-sm text-fg-muted mt-1">
          Balance, withdrawals, and transaction history for this realty group.
        </p>
      </div>
      <GroupWalletPage publicId={group.data.publicId} />
    </div>
  );
}
