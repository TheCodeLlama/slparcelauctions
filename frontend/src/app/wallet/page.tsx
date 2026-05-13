"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { useCurrentUser } from "@/lib/user";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { WalletPanel } from "@/components/wallet/WalletPanel";

export default function WalletPage() {
  const router = useRouter();
  const session = useAuth();
  const { data: user, isPending, isError } = useCurrentUser();

  // Auth-state guard runs first: an unauthenticated viewer must never see the
  // wallet, not even cached content from a prior session. The session check
  // dispatches the redirect before useCurrentUser's enabled-gated state has a
  // chance to render a stale-cache balance.
  useEffect(() => {
    if (session.status === "unauthenticated") {
      router.replace("/login?next=/wallet");
      return;
    }
    if (isPending || isError) return;
    if (!user?.verified) router.replace("/dashboard/verify");
  }, [session.status, isPending, isError, user?.verified, router]);

  if (session.status === "loading") {
    return <LoadingSpinner label="Loading wallet..." />;
  }
  if (session.status === "unauthenticated") {
    return <LoadingSpinner label="Redirecting to sign in..." />;
  }
  if (isPending || !user) return <LoadingSpinner label="Loading wallet..." />;
  if (!user.verified) return <LoadingSpinner label="Redirecting..." />;

  return (
    <div className="mx-auto max-w-6xl px-4 py-8 flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-bold tracking-tight font-display">Wallet</h1>
        <p className="text-sm text-fg-muted mt-1">
          Deposit, withdraw, and view your SLParcels wallet activity.
        </p>
      </div>
      <WalletPanel />
    </div>
  );
}
