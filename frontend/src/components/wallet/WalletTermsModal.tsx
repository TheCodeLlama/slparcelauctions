"use client";

import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/Button";
import { FormError } from "@/components/ui/FormError";
import { Modal } from "@/components/ui/Modal";
import { isApiError } from "@/lib/api";
import { acceptTerms } from "@/lib/api/wallet";
import { walletQueryKey } from "@/lib/wallet/use-wallet";

/**
 * Current wallet ToU version. Bumped server-side when the terms are
 * materially revised; the user must re-accept on the next interaction.
 */
export const WALLET_TERMS_VERSION = "1.0";

export interface WalletTermsModalProps {
  open: boolean;
  onClose: () => void;
  /**
   * Called after the seller accepts and the wallet cache has been
   * invalidated. Use to chain a follow-up action — e.g. opening a deposit
   * dialog on the wallet page, or no-op on the activate page since the
   * panel re-renders into the ready state on its own.
   */
  onAccepted?: () => void;
}

/**
 * Reusable wallet-terms acceptance modal. Renders the ToU bullet list and
 * an "I Accept" button that POSTs {@code /me/wallet/accept-terms} and
 * invalidates the wallet query so any consumer (the wallet page, the
 * activate-listing panel, future deposit/withdraw triggers) flips out of
 * its "terms not accepted" branch on the next render.
 */
export function WalletTermsModal({
  open,
  onClose,
  onAccepted,
}: WalletTermsModalProps) {
  const qc = useQueryClient();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleAccept() {
    setSubmitting(true);
    setError(null);
    try {
      await acceptTerms({ termsVersion: WALLET_TERMS_VERSION });
      await qc.invalidateQueries({ queryKey: walletQueryKey });
      onClose();
      onAccepted?.();
    } catch (e: unknown) {
      setError(
        isApiError(e)
          ? e.problem.detail ?? e.problem.title ?? "Could not accept terms."
          : e instanceof Error
            ? e.message
            : "Could not accept terms.",
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Modal
      open={open}
      title="SLParcels Wallet Terms of Use"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={submitting}>
            Cancel
          </Button>
          <Button
            variant="primary"
            onClick={handleAccept}
            loading={submitting}
            disabled={submitting}
          >
            I Accept
          </Button>
        </>
      }
    >
      <p>By using the SLParcels wallet, you acknowledge:</p>
      <ul className="list-disc pl-5 space-y-1">
        <li>
          <strong>Non-interest-bearing.</strong> L$ held in your wallet do not
          earn interest, dividends, or any return.
        </li>
        <li>
          <strong>L$ status.</strong> L$ are a Linden Lab limited-license token,
          not currency. SLParcels holds L$ on your behalf as a transactional convenience.
        </li>
        <li>
          <strong>No L$&harr;USD conversion.</strong> SLParcels does not exchange L$
          for USD or any other currency.
        </li>
        <li>
          <strong>Recoverable on shutdown.</strong> If SLParcels ceases operations,
          all positive wallet balances will be returned to your verified SL avatar.
        </li>
        <li>
          <strong>Freezable for fraud.</strong> SLParcels may freeze a wallet balance
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
      <FormError message={error ?? undefined} />
    </Modal>
  );
}
