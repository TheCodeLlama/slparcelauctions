"use client";

import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Card } from "@/components/ui/Card";
import { Button } from "@/components/ui/Button";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import {
  withdraw,
  payPenalty,
  acceptTerms,
} from "@/lib/api/wallet";
import { useWallet, walletQueryKey } from "@/lib/wallet/use-wallet";
import type { LedgerEntry } from "@/types/wallet";

function formatLindens(amount: number): string {
  return `L$${amount.toLocaleString()}`;
}

function entryTypeLabel(t: LedgerEntry["entryType"]): string {
  switch (t) {
    case "DEPOSIT": return "Deposit";
    case "WITHDRAW_QUEUED": return "Withdraw queued";
    case "WITHDRAW_COMPLETED": return "Withdraw completed";
    case "WITHDRAW_REVERSED": return "Withdraw reversed";
    case "BID_RESERVED": return "Bid reserved";
    case "BID_RELEASED": return "Bid released";
    case "ESCROW_DEBIT": return "Escrow funded";
    case "ESCROW_REFUND": return "Escrow refund";
    case "LISTING_FEE_DEBIT": return "Listing fee paid";
    case "LISTING_FEE_REFUND": return "Listing fee refund";
    case "PENALTY_DEBIT": return "Penalty paid";
    case "ADJUSTMENT": return "Adjustment";
  }
}

function genIdempotencyKey(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export function WalletPanel() {
  const queryClient = useQueryClient();
  const { data: wallet, isPending, error } = useWallet();
  const [showWithdraw, setShowWithdraw] = useState(false);
  const [showPenalty, setShowPenalty] = useState(false);
  const [showTerms, setShowTerms] = useState(false);
  const [showDeposit, setShowDeposit] = useState(false);

  /**
   * Invalidate the shared wallet cache so this component, the
   * {@link HeaderWalletIndicator}, and the {@link MobileMenu} all refetch
   * after a successful dialog action (withdraw / pay-penalty / accept-terms).
   */
  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: walletQueryKey });
  };

  if (isPending) return <LoadingSpinner label="Loading wallet..." />;
  if (error) return <Card><p>Error: {error instanceof Error ? error.message : "Failed to load wallet"}</p></Card>;
  if (!wallet) return null;

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <div className="flex flex-col gap-4">
          <h2 className="text-xl font-semibold">Your SLPA Wallet</h2>

          <div className="grid grid-cols-3 gap-4">
            <div>
              <div className="text-sm text-neutral-500">Balance</div>
              <div className="text-2xl font-semibold">{formatLindens(wallet.balance)}</div>
            </div>
            <div>
              <div className="text-sm text-neutral-500">Reserved (active bids)</div>
              <div className="text-2xl font-semibold">{formatLindens(wallet.reserved)}</div>
            </div>
            <div>
              <div className="text-sm text-neutral-500">Available</div>
              <div className="text-2xl font-semibold">{formatLindens(wallet.available)}</div>
            </div>
          </div>

          {wallet.penaltyOwed > 0 && (
            <div className="rounded border border-amber-400 bg-amber-50 p-3">
              <div className="font-medium">Outstanding penalty: {formatLindens(wallet.penaltyOwed)}</div>
              <div className="text-sm text-neutral-700">
                Clear this to publish new listings or place new bids.
                {wallet.available < wallet.penaltyOwed && (
                  <> Deposit {formatLindens(wallet.penaltyOwed - wallet.available)} more or wait for active bids to resolve.</>
                )}
              </div>
              <div className="mt-2">
                <Button
                  variant="secondary"
                  onClick={() => setShowPenalty(true)}
                  disabled={wallet.available <= 0}
                >
                  Pay Penalty
                </Button>
              </div>
            </div>
          )}

          <div className="flex gap-3">
            <Button
              variant="primary"
              onClick={() => {
                if (!wallet.termsAccepted) setShowTerms(true);
                else setShowDeposit(true);
              }}
            >
              How to Deposit
            </Button>
            <Button
              variant="secondary"
              onClick={() => setShowWithdraw(true)}
              disabled={wallet.available <= 0}
            >
              Withdraw
            </Button>
          </div>
        </div>
      </Card>

      <Card>
        <h3 className="text-lg font-semibold mb-3">Recent Activity</h3>
        {wallet.recentLedger.length === 0 ? (
          <p className="text-sm text-neutral-500">No wallet activity yet.</p>
        ) : (
          <ul className="space-y-1 text-sm">
            {wallet.recentLedger.map(e => (
              <li key={e.id} className="flex justify-between border-b border-neutral-200 py-2">
                <div>
                  <div className="font-medium">{entryTypeLabel(e.entryType)}</div>
                  <div className="text-xs text-neutral-500">{new Date(e.createdAt).toLocaleString()}</div>
                </div>
                <div className="text-right">
                  <div>{formatLindens(e.amount)}</div>
                  <div className="text-xs text-neutral-500">
                    Bal {formatLindens(e.balanceAfter)} / Res {formatLindens(e.reservedAfter)}
                  </div>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {showTerms && (
        <SimpleDialog title="SLPA Wallet Terms of Use" onClose={() => setShowTerms(false)}>
          <div className="text-sm space-y-2">
            <p>By using the SLPA wallet, you acknowledge:</p>
            <ul className="list-disc pl-5 space-y-1">
              <li><strong>Non-interest-bearing.</strong> L$ held in your wallet do not earn interest, dividends, or any return.</li>
              <li><strong>L$ status.</strong> L$ are a Linden Lab limited-license token, not currency. SLPA holds L$ on your behalf as a transactional convenience.</li>
              <li><strong>No L$↔USD conversion.</strong> SLPA does not exchange L$ for USD or any other currency.</li>
              <li><strong>Recoverable on shutdown.</strong> If SLPA ceases operations, all positive wallet balances will be returned to your verified SL avatar.</li>
              <li><strong>Freezable for fraud.</strong> SLPA may freeze a wallet balance pending fraud investigation, max 30 days absent legal process.</li>
              <li><strong>Dormancy.</strong> Wallets inactive for 30 days are flagged; after 4 weekly notifications, balance auto-returns to your SL avatar.</li>
              <li><strong>Banned-Resident handling.</strong> If your SL account loses good standing, your wallet balance returns to your last-verified SL avatar.</li>
            </ul>
            <div className="pt-3 flex justify-end gap-2">
              <Button variant="secondary" onClick={() => setShowTerms(false)}>Cancel</Button>
              <Button
                variant="primary"
                onClick={async () => {
                  await acceptTerms({ termsVersion: "1.0" });
                  await refresh();
                  setShowTerms(false);
                  setShowDeposit(true);
                }}
              >I Accept</Button>
            </div>
          </div>
        </SimpleDialog>
      )}

      {showDeposit && (
        <SimpleDialog title="How to Deposit" onClose={() => setShowDeposit(false)}>
          <div className="text-sm space-y-2">
            <ol className="list-decimal pl-5 space-y-2">
              <li>Visit any SLPA Terminal in-world (SLPA HQ or an auction venue).</li>
              <li>Right-click the terminal → Pay → enter the L$ amount.</li>
              <li>Funds will be credited to this wallet within seconds.</li>
            </ol>
            <p className="text-xs text-neutral-600">
              Multiple users can deposit simultaneously. There&apos;s no menu choice — every payment to the terminal is a deposit to your SLPA wallet.
            </p>
            <div className="pt-3 flex justify-end">
              <Button variant="primary" onClick={() => setShowDeposit(false)}>Got it</Button>
            </div>
          </div>
        </SimpleDialog>
      )}

      {showWithdraw && (
        <WithdrawDialog
          available={wallet.available}
          onClose={() => setShowWithdraw(false)}
          onSuccess={async () => {
            await refresh();
            setShowWithdraw(false);
          }}
        />
      )}

      {showPenalty && (
        <PayPenaltyDialog
          available={wallet.available}
          owed={wallet.penaltyOwed}
          onClose={() => setShowPenalty(false)}
          onSuccess={async () => {
            await refresh();
            setShowPenalty(false);
          }}
        />
      )}
    </div>
  );
}

function SimpleDialog({
  title,
  children,
  onClose,
}: {
  title: string;
  children: React.ReactNode;
  onClose: () => void;
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="bg-white rounded-lg p-6 max-w-md w-full max-h-[80vh] overflow-y-auto">
        <h3 className="text-lg font-semibold mb-3">{title}</h3>
        {children}
      </div>
    </div>
  );
}

function WithdrawDialog({
  available,
  onClose,
  onSuccess,
}: {
  available: number;
  onClose: () => void;
  onSuccess: () => Promise<void>;
}) {
  const [amount, setAmount] = useState<string>("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    const n = parseInt(amount, 10);
    if (!Number.isFinite(n) || n <= 0) {
      setError("Enter a positive integer.");
      return;
    }
    if (n > available) {
      setError(`Available is ${formatLindens(available)}.`);
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await withdraw({ amount: n, idempotencyKey: genIdempotencyKey() });
      await onSuccess();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Withdraw failed.");
      setSubmitting(false);
    }
  };

  return (
    <SimpleDialog title="Withdraw L$" onClose={onClose}>
      <div className="space-y-3 text-sm">
        <p>Available: <strong>{formatLindens(available)}</strong></p>
        <p className="text-xs text-neutral-600">
          Funds will be sent to your verified SL avatar via the in-world SLPA terminal pool.
        </p>
        <input
          type="text"
          value={amount}
          onChange={e => setAmount(e.target.value)}
          placeholder="Amount in L$"
          className="border border-neutral-300 rounded px-3 py-2 w-full"
        />
        {error && <p className="text-red-600 text-sm">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose} disabled={submitting}>Cancel</Button>
          <Button variant="primary" onClick={submit} disabled={submitting}>
            {submitting ? "Submitting..." : "Withdraw"}
          </Button>
        </div>
      </div>
    </SimpleDialog>
  );
}

function PayPenaltyDialog({
  available,
  owed,
  onClose,
  onSuccess,
}: {
  available: number;
  owed: number;
  onClose: () => void;
  onSuccess: () => Promise<void>;
}) {
  const [amount, setAmount] = useState<string>(String(Math.min(available, owed)));
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    const n = parseInt(amount, 10);
    if (!Number.isFinite(n) || n <= 0) {
      setError("Enter a positive integer.");
      return;
    }
    if (n > owed) {
      setError(`Owed is ${formatLindens(owed)}.`);
      return;
    }
    if (n > available) {
      setError(`Available is ${formatLindens(available)}.`);
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await payPenalty({ amount: n, idempotencyKey: genIdempotencyKey() });
      await onSuccess();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Pay penalty failed.");
      setSubmitting(false);
    }
  };

  return (
    <SimpleDialog title="Pay Penalty" onClose={onClose}>
      <div className="space-y-3 text-sm">
        <p>Outstanding penalty: <strong>{formatLindens(owed)}</strong></p>
        <p>Available balance: <strong>{formatLindens(available)}</strong></p>
        <p className="text-xs text-neutral-600">Partial payments allowed up to the owed amount.</p>
        <input
          type="text"
          value={amount}
          onChange={e => setAmount(e.target.value)}
          placeholder="Amount in L$"
          className="border border-neutral-300 rounded px-3 py-2 w-full"
        />
        {error && <p className="text-red-600 text-sm">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose} disabled={submitting}>Cancel</Button>
          <Button variant="primary" onClick={submit} disabled={submitting}>
            {submitting ? "Submitting..." : "Pay"}
          </Button>
        </div>
      </div>
    </SimpleDialog>
  );
}
