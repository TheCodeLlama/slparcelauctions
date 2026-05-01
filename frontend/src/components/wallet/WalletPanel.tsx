"use client";

import { useEffect, useMemo, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { Modal } from "@/components/ui/Modal";
import { Pagination } from "@/components/ui/Pagination";
import { AlertTriangle } from "@/components/ui/icons";
import {
  withdraw,
  payPenalty,
  acceptTerms,
  getLedger,
  ledgerExportUrl,
} from "@/lib/api/wallet";
import { useWallet, walletQueryKey } from "@/lib/wallet/use-wallet";
import { useWalletWsSubscription } from "@/lib/wallet/use-wallet-ws";
import { LedgerTable } from "@/components/wallet/LedgerTable";
import { LedgerFilterBar } from "@/components/wallet/LedgerFilterBar";
import type {
  LedgerFilter,
  UserLedgerEntryType,
} from "@/types/wallet";

function formatLindens(amount: number): string {
  return `L$${amount.toLocaleString()}`;
}

function genIdempotencyKey(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

const LEDGER_ENTRY_TYPES: UserLedgerEntryType[] = [
  "DEPOSIT",
  "WITHDRAW_QUEUED",
  "WITHDRAW_COMPLETED",
  "WITHDRAW_REVERSED",
  "BID_RESERVED",
  "BID_RELEASED",
  "ESCROW_DEBIT",
  "ESCROW_REFUND",
  "LISTING_FEE_DEBIT",
  "LISTING_FEE_REFUND",
  "PENALTY_DEBIT",
  "ADJUSTMENT",
];

function isLedgerEntryType(s: string): s is UserLedgerEntryType {
  return (LEDGER_ENTRY_TYPES as string[]).includes(s);
}

/**
 * Decode the wallet ledger filter from the URL search params. Inverse of
 * {@link filterToUrlParams}. Unknown / malformed values are silently
 * dropped so a hand-edited URL never crashes the page.
 */
function readFilterFromParams(params: URLSearchParams): LedgerFilter {
  const filter: LedgerFilter = {};
  const types = params.getAll("entryType").filter(isLedgerEntryType);
  if (types.length > 0) filter.entryTypes = types;
  const from = params.get("from");
  if (from) filter.from = from;
  const to = params.get("to");
  if (to) filter.to = to;
  const min = params.get("amountMin");
  if (min !== null) {
    const n = parseInt(min, 10);
    if (Number.isFinite(n) && n >= 0) filter.amountMin = n;
  }
  const max = params.get("amountMax");
  if (max !== null) {
    const n = parseInt(max, 10);
    if (Number.isFinite(n) && n >= 0) filter.amountMax = n;
  }
  return filter;
}

/**
 * Encode a {@link LedgerFilter} into URL search params. Order is stable
 * (entryType chips emit in their array order; scalars in a fixed order)
 * so React Query's structural-equality cache key behaves predictably as
 * the user toggles chips.
 */
function filterToUrlParams(filter: LedgerFilter): URLSearchParams {
  const params = new URLSearchParams();
  if (filter.entryTypes?.length) {
    filter.entryTypes.forEach((t) => params.append("entryType", t));
  }
  if (filter.from) params.set("from", filter.from);
  if (filter.to) params.set("to", filter.to);
  if (filter.amountMin !== undefined) {
    params.set("amountMin", String(filter.amountMin));
  }
  if (filter.amountMax !== undefined) {
    params.set("amountMax", String(filter.amountMax));
  }
  return params;
}

export function WalletPanel() {
  const queryClient = useQueryClient();
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const { data: wallet, isPending, error } = useWallet();
  // Live updates via /user/queue/wallet. The /wallet route is verified-gated
  // by the page-level guard so we can hard-enable here.
  useWalletWsSubscription(true);
  const [showWithdraw, setShowWithdraw] = useState(false);
  const [showPenalty, setShowPenalty] = useState(false);
  const [showTerms, setShowTerms] = useState(false);
  const [showDeposit, setShowDeposit] = useState(false);

  // Read filter + page out of the URL so the panel survives refresh and
  // shares deep-linkable views. Memoized so the {@code filter} reference
  // is stable across renders that don't change its inputs — keeps React
  // Query's structural-equality cache key from shifting and prevents
  // child memo busting in {@link LedgerFilterBar}.
  const filter = useMemo(
    () => readFilterFromParams(searchParams),
    [searchParams],
  );
  const page = Math.max(0, parseInt(searchParams.get("page") ?? "0", 10) || 0);
  const size = Math.min(
    100,
    Math.max(1, parseInt(searchParams.get("size") ?? "25", 10) || 25),
  );

  const { data: ledgerPage, isFetching: ledgerFetching } = useQuery({
    queryKey: ["me", "wallet", "ledger", filter, page, size] as const,
    queryFn: () => getLedger(filter, page, size),
    enabled: !!wallet,
  });

  const handleFilterChange = (next: LedgerFilter) => {
    const params = filterToUrlParams(next);
    if (size !== 25) params.set("size", String(size));
    // Always reset to page 0 when the filter changes — the previous page
    // index has no meaning over the new filtered set.
    const qs = params.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  };

  const handlePageChange = (nextPage: number) => {
    const params = new URLSearchParams(searchParams.toString());
    if (nextPage <= 0) params.delete("page");
    else params.set("page", String(nextPage));
    const qs = params.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  };

  const handleExport = () => {
    const url = ledgerExportUrl(filter);
    const a = document.createElement("a");
    a.href = url;
    a.rel = "noopener";
    a.download = "";
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
  };

  /**
   * Auto-open the Pay Penalty dialog when the page is reached via the
   * `?penalty=open` deep link (e.g. the header pill's penalty CTA), and
   * strip the param so a refresh doesn't re-open the dialog. Spec §4.5.
   *
   * setState-in-effect is intentional here: the trigger is a one-shot URL
   * deep link, not derivable state.
   */
  useEffect(() => {
    if (searchParams.get("penalty") !== "open") return;
    if (wallet && wallet.penaltyOwed > 0) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setShowPenalty(true);
    }
    const params = new URLSearchParams(searchParams.toString());
    params.delete("penalty");
    const qs = params.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  }, [searchParams, pathname, router, wallet]);

  /**
   * Invalidate the shared wallet cache so this component, the
   * {@link HeaderWalletIndicator}, and the {@link MobileMenu} all refetch
   * after a successful dialog action (withdraw / pay-penalty / accept-terms).
   * Also invalidates the ledger query so a brand-new entry appears immediately.
   */
  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: walletQueryKey });
    await queryClient.invalidateQueries({ queryKey: ["me", "wallet", "ledger"] });
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

      <LedgerFilterBar
        filter={filter}
        onChange={handleFilterChange}
        onExport={handleExport}
      />

      <div className="bg-surface-container-lowest rounded-default shadow-soft p-6">
        <h3 className="text-lg font-semibold text-on-surface mb-3">
          Activity
        </h3>
        <LedgerTable
          entries={ledgerPage?.content ?? []}
          isLoading={ledgerFetching && !ledgerPage}
        />
        {ledgerPage && ledgerPage.totalPages > 1 && (
          <div className="mt-4">
            <Pagination
              page={ledgerPage.number}
              totalPages={ledgerPage.totalPages}
              onPageChange={handlePageChange}
            />
          </div>
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
