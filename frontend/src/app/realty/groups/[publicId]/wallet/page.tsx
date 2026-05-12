export const dynamic = "force-dynamic";

import { GroupWalletPage } from "@/components/realty/wallet/GroupWalletPage";

interface PageProps {
  params: Promise<{ publicId: string }>;
}

/**
 * Group wallet page. Entirely client-rendered — the balance is per-user,
 * the terms-block banner depends on the leader's live user state, and the
 * JWT needed to call the wallet endpoint is not available in Server Components.
 *
 * {@code export const dynamic = "force-dynamic"} prevents Amplify from
 * prerendering a stale snapshot at build time (see Frontend SSR caveats in
 * CLAUDE.md).
 */
export default async function Page({ params }: PageProps) {
  const { publicId } = await params;
  return (
    <div className="mx-auto max-w-4xl px-4 py-8 flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-bold tracking-tight font-display">
          Group Wallet
        </h1>
        <p className="text-sm text-fg-muted mt-1">
          Balance, withdrawals, and transaction history for this realty group.
        </p>
      </div>
      <GroupWalletPage publicId={publicId} />
    </div>
  );
}
