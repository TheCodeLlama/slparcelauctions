"use client";

import { useState, type ComponentType } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { Modal } from "@/components/ui/Modal";
import {
  AlertTriangle,
  ArrowDownToLine,
  ArrowUpFromLine,
  Clock,
  Lock,
  Pencil,
  Tag,
  Undo2,
  Unlock,
  Wallet,
} from "@/components/ui/icons";
import { cn } from "@/lib/cn";
import {
  withdraw,
  payPenalty,
  acceptTerms,
} from "@/lib/api/wallet";
import { useWallet, walletQueryKey } from "@/lib/wallet/use-wallet";
import { formatRelativeTime } from "@/lib/time/relativeTime";
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

type EntryVisual = {
  Icon: ComponentType<{ className?: string }>;
  tone: string;
};

/**
 * Maps ledger entry types to a lucide icon + a Material-3 colour token
 * pair. Inflows (deposit / refund) use {@code text-success}; outflows that
 * are user-initiated and final use the neutral {@code text-on-surface};
 * "in-progress" or warning-tinted entries (queued withdraw, reserved bid,
 * penalty) use {@code text-warning}.
 */
function entryVisual(t: LedgerEntry["entryType"]): EntryVisual {
  switch (t) {
    case "DEPOSIT":
      return { Icon: ArrowDownToLine, tone: "text-success" };
    case "WITHDRAW_QUEUED":
      return { Icon: Clock, tone: "text-on-surface-variant" };
    case "WITHDRAW_COMPLETED":
      return { Icon: ArrowUpFromLine, tone: "text-on-surface" };
    case "WITHDRAW_REVERSED":
      return { Icon: Undo2, tone: "text-warning" };
    case "BID_RESERVED":
      return { Icon: Lock, tone: "text-warning" };
    case "BID_RELEASED":
      return { Icon: Unlock, tone: "text-on-surface-variant" };
    case "ESCROW_DEBIT":
      return { Icon: ArrowUpFromLine, tone: "text-on-surface" };
    case "ESCROW_REFUND":
      return { Icon: ArrowDownToLine, tone: "text-success" };
    case "LISTING_FEE_DEBIT":
      return { Icon: Tag, tone: "text-on-surface" };
    case "LISTING_FEE_REFUND":
      return { Icon: ArrowDownToLine, tone: "text-success" };
    case "PENALTY_DEBIT":
      return { Icon: AlertTriangle, tone: "text-warning" };
    case "ADJUSTMENT":
      return { Icon: Pencil, tone: "text-on-surface-variant" };
  }
}

/**
 * Recent-ledger row date label. Within the last 24 hours we render
 * a relative string ({@code "3m ago"}); older entries fall back to a
 * compact absolute date-time. Title attribute always holds the
 * absolute-locale string so a hover surfaces the precise instant.
 */
function formatLedgerDate(iso: string): string {
  const d = new Date(iso);
  if (!Number.isFinite(d.getTime())) return iso;
  const hoursAgo = (Date.now() - d.getTime()) / (1000 * 60 * 60);
  if (hoursAgo < 24) {
    return formatRelativeTime(d);
  }
  return d.toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
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
  if (error) {
    return (
      <div className="bg-surface-container-lowest rounded-default shadow-soft p-6">
        <p className="text-on-surface">
          Error: {error instanceof Error ? error.message : "Failed to load wallet"}
        </p>
      </div>
    );
  }
  if (!wallet) return null;

  return (
    <div className="flex flex-col gap-4">
      {/* Hero balance card — Available is dominant; Balance + Reserved are
          secondary breakdown rows. Replaces the earlier 3-equal-cell grid. */}
      <div className="bg-surface-container rounded-2xl p-6">
        <div className="text-xs uppercase tracking-wide text-on-surface-variant mb-1">
          Available
        </div>
        <div className="text-4xl font-semibold tabular-nums text-on-surface">
          {formatLindens(wallet.available)}
        </div>
        <div className="mt-4 flex flex-wrap gap-x-6 gap-y-2 text-sm">
          <div>
            <span className="text-on-surface-variant">Balance </span>
            <span className="tabular-nums text-on-surface">
              {formatLindens(wallet.balance)}
            </span>
          </div>
          <div>
            <span className="text-on-surface-variant">Reserved (active bids) </span>
            <span className="tabular-nums text-on-surface">
              {formatLindens(wallet.reserved)}
            </span>
          </div>
        </div>

        <div className="mt-5 flex flex-wrap gap-3">
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

      {wallet.penaltyOwed > 0 && (
        <div className="rounded-2xl border border-warning bg-warning-container/40 p-4 flex gap-3">
          <AlertTriangle className="h-5 w-5 text-warning shrink-0 mt-0.5" />
          <div className="flex-1">
            <h3 className="font-medium text-on-warning-container">
              Outstanding penalty: {formatLindens(wallet.penaltyOwed)}
            </h3>
            <p className="text-sm text-on-surface-variant mt-1">
              Clear this to publish new listings or place new bids.
            </p>
            {wallet.available < wallet.penaltyOwed && (
              <p className="text-sm text-on-surface-variant mt-1">
                Deposit {formatLindens(wallet.penaltyOwed - wallet.available)} more
                or wait for active bids to resolve.
              </p>
            )}
            <Button
              variant="primary"
              size="sm"
              className="mt-3"
              onClick={() => setShowPenalty(true)}
              disabled={wallet.available <= 0}
            >
              Pay Penalty
            </Button>
          </div>
        </div>
      )}

      <div className="bg-surface-container-lowest rounded-default shadow-soft p-6">
        <h3 className="text-lg font-semibold text-on-surface mb-3">
          Recent Activity
        </h3>
        {wallet.recentLedger.length === 0 ? (
          <div className="flex flex-col items-center text-center py-8 gap-3">
            <Wallet className="h-12 w-12 text-on-surface-variant/40" />
            <h3 className="font-medium text-on-surface">No activity yet</h3>
            <p className="text-sm text-on-surface-variant max-w-sm">
              Visit any SLPA Terminal in-world to make your first deposit.
              Locations: SLPA HQ and partner auction venues.
            </p>
          </div>
        ) : (
          <ul className="text-sm">
            {wallet.recentLedger.map((e) => {
              const { Icon, tone } = entryVisual(e.entryType);
              return (
                <li
                  key={e.id}
                  className={cn(
                    "flex justify-between items-start gap-3 px-2 py-2 rounded-md",
                    "border-b border-outline-variant last:border-b-0",
                    "hover:bg-surface-container-low",
                  )}
                >
                  <div className="flex items-start min-w-0">
                    <Icon
                      className={cn("h-4 w-4 mr-2 mt-0.5 shrink-0", tone)}
                      aria-hidden="true"
                    />
                    <div className="min-w-0">
                      <div className="font-medium text-on-surface truncate">
                        {entryTypeLabel(e.entryType)}
                      </div>
                      <div
                        className="text-xs text-on-surface-variant"
                        title={new Date(e.createdAt).toLocaleString()}
                      >
                        {formatLedgerDate(e.createdAt)}
                      </div>
                    </div>
                  </div>
                  <div className="text-right shrink-0">
                    <div className={cn("tabular-nums", tone)}>
                      {formatLindens(e.amount)}
                    </div>
                    <div className="text-xs text-on-surface-variant tabular-nums">
                      Bal {formatLindens(e.balanceAfter)} / Res {formatLindens(e.reservedAfter)}
                    </div>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </div>

      <Modal
        open={showTerms}
        title="SLPA Wallet Terms of Use"
        onClose={() => setShowTerms(false)}
        footer={
          <>
            <Button variant="secondary" onClick={() => setShowTerms(false)}>
              Cancel
            </Button>
            <Button
              variant="primary"
              onClick={async () => {
                await acceptTerms({ termsVersion: "1.0" });
                await refresh();
                setShowTerms(false);
                setShowDeposit(true);
              }}
            >
              I Accept
            </Button>
          </>
        }
      >
        <p>By using the SLPA wallet, you acknowledge:</p>
        <ul className="list-disc pl-5 space-y-1">
          <li>
            <strong>Non-interest-bearing.</strong> L$ held in your wallet do not
            earn interest, dividends, or any return.
          </li>
          <li>
            <strong>L$ status.</strong> L$ are a Linden Lab limited-license token,
            not currency. SLPA holds L$ on your behalf as a transactional convenience.
          </li>
          <li>
            <strong>No L$&harr;USD conversion.</strong> SLPA does not exchange L$
            for USD or any other currency.
          </li>
          <li>
            <strong>Recoverable on shutdown.</strong> If SLPA ceases operations,
            all positive wallet balances will be returned to your verified SL avatar.
          </li>
          <li>
            <strong>Freezable for fraud.</strong> SLPA may freeze a wallet balance
            pending fraud investigation, max 30 days absent legal process.
          </li>
          <li>
            <strong>Dormancy.</strong> Wallets inactive for 30 days are flagged;
            after 4 weekly notifications, balance auto-returns to your SL avatar.
          </li>
          <li>
            <strong>Banned-Resident handling.</strong> If your SL account loses
            good standing, your wallet balance returns to your last-verified SL avatar.
          </li>
        </ul>
      </Modal>

      <Modal
        open={showDeposit}
        title="How to Deposit"
        onClose={() => setShowDeposit(false)}
        footer={
          <Button variant="primary" onClick={() => setShowDeposit(false)}>
            Got it
          </Button>
        }
      >
        <ol className="list-decimal pl-5 space-y-2">
          <li>Visit any SLPA Terminal in-world (SLPA HQ or an auction venue).</li>
          <li>Right-click the terminal &rarr; Pay &rarr; enter the L$ amount.</li>
          <li>Funds will be credited to this wallet within seconds.</li>
        </ol>
        <p className="text-xs text-on-surface-variant">
          Multiple users can deposit simultaneously. There&apos;s no menu choice
          &mdash; every payment to the terminal is a deposit to your SLPA wallet.
        </p>
      </Modal>

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
    <Modal
      open
      title="Withdraw L$"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={submitting}>
            Cancel
          </Button>
          <Button variant="primary" onClick={submit} disabled={submitting}>
            {submitting ? "Submitting..." : "Withdraw"}
          </Button>
        </>
      }
    >
      <p>
        Available: <strong>{formatLindens(available)}</strong>
      </p>
      <p className="text-xs text-on-surface-variant">
        Funds will be sent to your verified SL avatar via the in-world SLPA
        terminal pool.
      </p>
      <Input
        type="text"
        inputMode="numeric"
        value={amount}
        onChange={(e) => setAmount(e.target.value)}
        placeholder="Amount in L$"
        aria-label="Withdrawal amount in L$"
        error={error ?? undefined}
      />
    </Modal>
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
  const [amount, setAmount] = useState<string>(
    String(Math.min(available, owed)),
  );
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
    <Modal
      open
      title="Pay Penalty"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={submitting}>
            Cancel
          </Button>
          <Button variant="primary" onClick={submit} disabled={submitting}>
            {submitting ? "Submitting..." : "Pay"}
          </Button>
        </>
      }
    >
      <p>
        Outstanding penalty: <strong>{formatLindens(owed)}</strong>
      </p>
      <p>
        Available balance: <strong>{formatLindens(available)}</strong>
      </p>
      <p className="text-xs text-on-surface-variant">
        Partial payments allowed up to the owed amount.
      </p>
      <Input
        type="text"
        inputMode="numeric"
        value={amount}
        onChange={(e) => setAmount(e.target.value)}
        placeholder="Amount in L$"
        aria-label="Penalty payment amount in L$"
        error={error ?? undefined}
      />
    </Modal>
  );
}
