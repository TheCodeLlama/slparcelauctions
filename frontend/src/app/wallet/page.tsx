"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useCurrentUser } from "@/lib/user";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { WalletPanel } from "@/components/wallet/WalletPanel";

export default function WalletPage() {
  const router = useRouter();
  const { data: user, isPending, isError } = useCurrentUser();

  useEffect(() => {
    if (isPending || isError) return;
    if (!user?.verified) router.replace("/dashboard/verify");
  }, [isPending, isError, user?.verified, router]);

  if (isPending || !user) return <LoadingSpinner label="Loading wallet..." />;
  if (!user.verified) return <LoadingSpinner label="Redirecting..." />;

  return (
    <div className="mx-auto max-w-6xl px-4 py-8 flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-bold tracking-tight font-display">Wallet</h1>
        <p className="text-sm text-fg-muted mt-1">
          Deposit, withdraw, and view your SLPA wallet activity.
        </p>
      </div>
      <WalletPanel />
    </div>
  );
}
