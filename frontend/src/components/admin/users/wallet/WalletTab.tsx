"use client";
import { useState } from "react";
import { useAdminWallet, useAdminWalletLedger } from "@/hooks/admin/useAdminWallet";
import { Pagination } from "@/components/ui/Pagination";
import { Button } from "@/components/ui/Button";
import { ForceFinalizeWithdrawalModal } from "./ForceFinalizeWithdrawalModal";
import type { AdminWalletPendingWithdrawal } from "@/lib/admin/types";

const PAGE_SIZE = 25;

function formatDateTime(iso: string | null): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("en-US", {
    month: "short", day: "numeric", year: "numeric",
    hour: "2-digit", minute: "2-digit",
  });
}

function formatLindens(amount: number): string {
  return `L$ ${amount.toLocaleString()}`;
}

type Props = { publicId: string };

export function WalletTab({ publicId }: Props) {
  const [page, setPage] = useState(0);
  const [pendingAction, setPendingAction] = useState<{
    withdrawal: AdminWalletPendingWithdrawal;
    mode: "complete" | "fail";
  } | null>(null);

  const { data: wallet, isLoading: walletLoading, isError: walletError } = useAdminWallet(publicId);
  const { data: ledger, isLoading: ledgerLoading } = useAdminWalletLedger(publicId, page, PAGE_SIZE);

  if (walletLoading) {
    return <div className="py-6 text-sm text-fg-muted">Loading wallet…</div>;
  }
  if (walletError || !wallet) {
    return (
      <div className="py-6 text-sm text-danger" data-testid="wallet-error">
        Could not load wallet. Refresh to retry.
      </div>
    );
  }

  return (
    <div data-testid="wallet-tab" className="flex flex-col gap-6">
      {/* Header strip */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-2">
        <div className="rounded-lg bg-bg-muted p-3">
          <div className="text-[10px] uppercase tracking-wide text-fg-muted/70">Balance</div>
          <div className="text-fg font-mono">{formatLindens(wallet.balanceLindens)}</div>
        </div>
        <div className="rounded-lg bg-bg-muted p-3">
          <div className="text-[10px] uppercase tracking-wide text-fg-muted/70">Reserved</div>
          <div className="text-fg font-mono">{formatLindens(wallet.reservedLindens)}</div>
        </div>
        <div className="rounded-lg bg-bg-muted p-3">
          <div className="text-[10px] uppercase tracking-wide text-fg-muted/70">Available</div>
          <div className="text-fg font-mono">{formatLindens(wallet.availableLindens)}</div>
        </div>
        <div className="rounded-lg bg-bg-muted p-3">
          <div className="text-[10px] uppercase tracking-wide text-fg-muted/70">Penalty owed</div>
          <div className={`font-mono ${wallet.penaltyBalanceOwed > 0 ? "text-danger" : "text-fg"}`}>
            {formatLindens(wallet.penaltyBalanceOwed)}
          </div>
        </div>
      </div>

      {/* State badges + view-as-user link */}
      <div className="flex flex-wrap items-center gap-2 text-[11px]">
        {wallet.walletFrozenAt && (
          <span className="inline-flex items-center px-2 py-0.5 rounded-full bg-danger text-white font-semibold">
            FROZEN since {formatDateTime(wallet.walletFrozenAt)}
          </span>
        )}
        {wallet.walletDormancyPhase != null && (
          <span className="inline-flex items-center px-2 py-0.5 rounded-full bg-bg-hover text-fg-muted">
            Dormancy phase {wallet.walletDormancyPhase}
          </span>
        )}
        {wallet.walletTermsAcceptedAt ? (
          <span className="inline-flex items-center px-2 py-0.5 rounded-full bg-info-bg text-info">
            Terms v{wallet.walletTermsVersion ?? "—"}
          </span>
        ) : (
          <span className="inline-flex items-center px-2 py-0.5 rounded-full bg-bg-hover text-fg-muted">
            Terms not accepted
          </span>
        )}
      </div>

      {wallet.walletFrozenReason && (
        <div className="rounded-lg bg-danger-bg border border-danger/20 p-3 text-[11px] text-danger">
          <span className="font-semibold">Freeze reason:</span> {wallet.walletFrozenReason}
        </div>
      )}

      {/* Pending withdrawals */}
      <section>
        <h3 className="text-sm font-semibold text-fg mb-2">Pending withdrawals</h3>
        {wallet.pendingWithdrawals.length === 0 ? (
          <div className="text-[11px] text-fg-muted">No pending withdrawals.</div>
        ) : (
          <div className="overflow-x-auto rounded-lg border border-border-subtle">
            <table className="w-full text-sm">
              <thead className="bg-bg-subtle border-b border-border-subtle">
                <tr>
                  <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Amount</th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Queued at</th>
                  <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Status</th>
                  <th className="px-3 py-2.5 text-right text-[11px] font-medium text-fg-muted w-[260px]"> </th>
                </tr>
              </thead>
              <tbody>
                {wallet.pendingWithdrawals.map((w) => (
                  <tr
                    key={w.terminalCommandId}
                    className="border-b border-border-subtle/50"
                    data-testid={`pending-withdrawal-${w.terminalCommandId}`}
                  >
                    <td className="px-3 py-2.5 font-mono text-fg">{formatLindens(w.amount)}</td>
                    <td className="px-3 py-2.5 text-fg-muted text-[11px]">{formatDateTime(w.queuedAt)}</td>
                    <td className="px-3 py-2.5">
                      <span className={`text-[11px] font-semibold ${w.status === "QUEUED" ? "text-fg-muted" : "text-danger"}`}>
                        {w.status}
                        {w.status === "IN_FLIGHT" && w.dispatchedAt && (
                          <span className="ml-1 font-normal text-fg-muted/70">
                            (claimed {formatDateTime(w.dispatchedAt)})
                          </span>
                        )}
                      </span>
                    </td>
                    <td className="px-3 py-2.5 text-right">
                      <div className="inline-flex gap-1.5">
                        <Button
                          variant="secondary"
                          size="sm"
                          disabled={!w.canForceFinalize}
                          onClick={() => setPendingAction({ withdrawal: w, mode: "complete" })}
                          data-testid={`force-complete-${w.terminalCommandId}`}
                          title={!w.canForceFinalize ? "Bot is mid-payout — wait for callback or lease expiry" : undefined}
                        >
                          Force complete
                        </Button>
                        <Button
                          variant="destructive"
                          size="sm"
                          disabled={!w.canForceFinalize}
                          onClick={() => setPendingAction({ withdrawal: w, mode: "fail" })}
                          data-testid={`force-fail-${w.terminalCommandId}`}
                          title={!w.canForceFinalize ? "Bot is mid-payout — wait for callback or lease expiry" : undefined}
                        >
                          Force fail
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* Ledger */}
      <section>
        <h3 className="text-sm font-semibold text-fg mb-2">Ledger</h3>
        {ledgerLoading && <div className="text-[11px] text-fg-muted">Loading ledger…</div>}
        {ledger && ledger.content.length === 0 && (
          <div className="text-[11px] text-fg-muted">No ledger entries.</div>
        )}
        {ledger && ledger.content.length > 0 && (
          <>
            <div className="overflow-x-auto rounded-lg border border-border-subtle">
              <table className="w-full text-sm">
                <thead className="bg-bg-subtle border-b border-border-subtle">
                  <tr>
                    <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">When</th>
                    <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Type</th>
                    <th className="px-3 py-2.5 text-right text-[11px] font-medium text-fg-muted">Amount</th>
                    <th className="px-3 py-2.5 text-right text-[11px] font-medium text-fg-muted">Balance after</th>
                    <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Description</th>
                    <th className="px-3 py-2.5 text-left text-[11px] font-medium text-fg-muted">Admin</th>
                  </tr>
                </thead>
                <tbody>
                  {ledger.content.map((row) => (
                    <tr key={row.entryId} className="border-b border-border-subtle/50" data-testid={`ledger-row-${row.entryId}`}>
                      <td className="px-3 py-2.5 text-fg-muted text-[11px]">{formatDateTime(row.createdAt)}</td>
                      <td className="px-3 py-2.5 text-[10px] uppercase tracking-wide text-fg-muted/70">{row.entryType}</td>
                      <td className={`px-3 py-2.5 text-right font-mono ${row.amount < 0 ? "text-danger" : "text-fg"}`}>
                        {row.amount >= 0 ? "+" : ""}{formatLindens(row.amount)}
                      </td>
                      <td className="px-3 py-2.5 text-right font-mono text-fg">{formatLindens(row.balanceAfter)}</td>
                      <td className="px-3 py-2.5 text-fg-muted text-[11px]">{row.description ?? "—"}</td>
                      <td className="px-3 py-2.5 text-fg-muted text-[11px]">
                        {row.createdByAdminId ? `#${row.createdByAdminId}` : "—"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {ledger.totalPages > 1 && (
              <div className="mt-4">
                <Pagination
                  page={ledger.number}
                  totalPages={ledger.totalPages}
                  onPageChange={setPage}
                />
              </div>
            )}
          </>
        )}
      </section>

      {pendingAction && (
        <ForceFinalizeWithdrawalModal
          open={true}
          publicId={publicId}
          withdrawal={pendingAction.withdrawal}
          mode={pendingAction.mode}
          onClose={() => setPendingAction(null)}
        />
      )}
    </div>
  );
}
