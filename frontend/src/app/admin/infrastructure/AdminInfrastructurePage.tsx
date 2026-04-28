"use client";
import { BotPoolSection } from "./BotPoolSection";
import { TerminalsSection } from "./TerminalsSection";
import { ReconciliationSection } from "./ReconciliationSection";
import { AvailableToWithdrawCard } from "./AvailableToWithdrawCard";
import { WithdrawalsHistorySection } from "./WithdrawalsHistorySection";

export function AdminInfrastructurePage() {
  return (
    <div className="space-y-4">
      <header>
        <h1 className="text-xl font-semibold">Infrastructure</h1>
        <p className="text-xs opacity-60">Bot pool, terminals, reconciliation, and admin withdrawals</p>
      </header>
      <BotPoolSection />
      <TerminalsSection />
      <AvailableToWithdrawCard />
      <ReconciliationSection />
      <WithdrawalsHistorySection />
    </div>
  );
}
