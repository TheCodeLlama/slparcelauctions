"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { Plus, Minus } from "@/components/ui/icons";
import { isApiError } from "@/lib/api";
import { adjustGroupWallet } from "@/lib/api/adminRealtyGroupWallet";
import type { GroupWallet } from "@/types/realty";

const REASON_MAX = 500;

type Direction = "credit" | "debit";

export interface AdminWalletAdjustCardProps {
  /** Group public UUID. */
  publicId: string;
  /**
   * Invoked with the updated {@link GroupWallet} snapshot returned by the
   * backend after a successful adjustment. Callers wire this to a TanStack
   * Query invalidation so the on-page balance card refreshes inline.
   */
  onAdjusted: (wallet: GroupWallet) => void;
}

/**
 * Sub-project G §7.2 — admin card on the existing admin realty-group detail
 * page. Posts a signed amount + reason to the new admin wallet adjust endpoint
 * and surfaces the resulting wallet DTO to the parent so the balance card on
 * the same page refreshes inline.
 *
 * <p>Form shape:
 * <ul>
 *   <li>Direction toggle ({@code Credit (+)} / {@code Debit (-)}) — drives
 *       the sign of {@code amount} on the wire.</li>
 *   <li>Amount input — positive integer L$ value; combined with the direction
 *       toggle to produce the signed wire value.</li>
 *   <li>Reason textarea — required, {@code <= 500} chars, with a live
 *       character counter.</li>
 * </ul>
 *
 * <p>Error codes surfaced with specific copy:
 * <ul>
 *   <li>{@code INSUFFICIENT_GROUP_BALANCE} — debit would push balance below
 *       zero.</li>
 *   <li>{@code ADMIN_ADJUST_AMOUNT_OUT_OF_RANGE} — amount exceeds the
 *       configured sanity ceiling.</li>
 *   <li>{@code REALTY_GROUP_NOT_FOUND} — group does not exist (rare; the
 *       parent page wouldn't have loaded).</li>
 * </ul>
 * Other failures (403, generic 4xx/5xx) surface the problem-details
 * {@code detail} or {@code title} verbatim.
 */
export function AdminWalletAdjustCard({
  publicId,
  onAdjusted,
}: AdminWalletAdjustCardProps) {
  const [direction, setDirection] = useState<Direction>("credit");
  const [amountText, setAmountText] = useState<string>("");
  const [reason, setReason] = useState<string>("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reasonTrimmed = reason.trim();
  const parsedAmount = parseInt(amountText, 10);
  const validAmount = Number.isFinite(parsedAmount) && parsedAmount > 0;
  const reasonValid =
    reasonTrimmed.length > 0 && reason.length <= REASON_MAX;
  const canSubmit = validAmount && reasonValid && !submitting;

  const handleSubmit = async () => {
    setError(null);
    setSubmitting(true);
    const signed = direction === "credit" ? parsedAmount : -parsedAmount;
    try {
      const dto = await adjustGroupWallet(publicId, {
        amount: signed,
        reason: reasonTrimmed,
      });
      onAdjusted(dto);
      setAmountText("");
      setReason("");
    } catch (e) {
      if (isApiError(e)) {
        const code = e.problem.code as string | undefined;
        if (code === "INSUFFICIENT_GROUP_BALANCE") {
          setError("Debit would push balance below zero.");
        } else if (code === "ADMIN_ADJUST_AMOUNT_OUT_OF_RANGE") {
          setError("Amount exceeds the configured sanity ceiling.");
        } else if (code === "REALTY_GROUP_NOT_FOUND") {
          setError("This group no longer exists.");
        } else {
          const detail = e.problem.detail as string | undefined;
          const title = e.problem.title as string | undefined;
          setError(detail ?? title ?? e.message);
        }
      } else {
        setError("Adjustment failed. Please try again.");
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <section
      className="rounded-lg border border-border bg-surface-raised p-4"
      data-testid="admin-wallet-adjust-card"
    >
      <header className="mb-3">
        <h3 className="text-sm font-semibold text-fg">
          Admin wallet adjustment
        </h3>
        <p className="mt-1 text-xs text-fg-muted">
          Writes an audit row and a ledger entry; broadcasts the new balance to
          the group.
        </p>
      </header>
      <div className="flex flex-col gap-3">
        <div
          className="flex gap-2"
          role="group"
          aria-label="Adjustment direction"
        >
          <Button
            variant={direction === "credit" ? "primary" : "secondary"}
            size="sm"
            onClick={() => setDirection("credit")}
            aria-pressed={direction === "credit"}
            leftIcon={<Plus className="size-4" aria-hidden="true" />}
            data-testid="admin-wallet-adjust-direction-credit"
          >
            Credit
          </Button>
          <Button
            variant={direction === "debit" ? "primary" : "secondary"}
            size="sm"
            onClick={() => setDirection("debit")}
            aria-pressed={direction === "debit"}
            leftIcon={<Minus className="size-4" aria-hidden="true" />}
            data-testid="admin-wallet-adjust-direction-debit"
          >
            Debit
          </Button>
        </div>
        <Input
          type="text"
          inputMode="numeric"
          value={amountText}
          onChange={(e) => setAmountText(e.target.value)}
          placeholder="L$ amount"
          aria-label="Amount in L$"
          label="Amount in L$"
          data-testid="admin-wallet-adjust-amount"
        />
        <label
          className="flex flex-col gap-1 text-xs font-medium text-fg-muted"
          htmlFor={`admin-wallet-adjust-reason-${publicId}`}
        >
          Reason
          <textarea
            id={`admin-wallet-adjust-reason-${publicId}`}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="Why is this adjustment being made? (required, max 500 chars)"
            aria-label="Reason"
            maxLength={REASON_MAX}
            rows={3}
            className="rounded-lg bg-bg-subtle px-3 py-2 text-sm text-fg ring-1 ring-transparent focus:bg-surface-raised focus:outline-none focus:ring-brand"
            data-testid="admin-wallet-adjust-reason"
          />
          <span
            className="self-end text-[10px] text-fg-muted"
            data-testid="admin-wallet-adjust-reason-counter"
          >
            {reason.length} / {REASON_MAX}
          </span>
        </label>
        {error && (
          <p
            className="text-sm text-danger"
            role="alert"
            data-testid="admin-wallet-adjust-error"
          >
            {error}
          </p>
        )}
        <Button
          variant="primary"
          onClick={handleSubmit}
          disabled={!canSubmit}
          loading={submitting}
          data-testid="admin-wallet-adjust-submit"
        >
          Submit adjustment
        </Button>
      </div>
    </section>
  );
}
