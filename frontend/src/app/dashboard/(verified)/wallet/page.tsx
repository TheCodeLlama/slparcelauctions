"use client";

import { WalletPanel } from "@/components/wallet/WalletPanel";

export default function WalletPage() {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-bold">Wallet</h1>
        <p className="text-sm text-neutral-600 mt-1">
          Deposit, withdraw, and view your SLPA wallet activity.
        </p>
      </div>
      <WalletPanel />
    </div>
  );
}
