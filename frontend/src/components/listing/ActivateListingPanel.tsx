"use client";

import { useState } from "react";
import Link from "next/link";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/Button";
import { FormError } from "@/components/ui/FormError";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { isApiError } from "@/lib/api";
import { payListingFee } from "@/lib/api/wallet";
import { useListingFeeConfig } from "@/hooks/useListingFeeConfig";
import { useWallet, walletQueryKey } from "@/lib/wallet/use-wallet";
import { activateAuctionKey } from "@/hooks/useActivateAuction";
import { WalletTermsModal } from "@/components/wallet/WalletTermsModal";

function genIdempotencyKey(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function formatLindens(amount: number): string {
  return `L$${amount.toLocaleString()}`;
}

export interface ActivateListingPanelProps {
  auctionPublicId: number | string;
}

/**
 * DRAFT-state listing-fee gate. The seller funds their SLPA wallet at any
 * in-world terminal; clicking Activate Listing debits the fee from the
 * wallet's available balance via {@code POST /me/auctions/{id}/pay-listing-fee}.
 * The activate page's polling hook picks up the resulting status flip on
 * its next tick and re-renders the verification picker.
 *
 * Render branches (precedence top-to-bottom):
 *   1. fee or wallet still loading -> spinner
 *   2. wallet ToU not accepted -> "open wallet" CTA
 *   3. outstanding penalty -> "pay penalty first" CTA
 *   4. available < fee -> "top up at any terminal" + refresh balance
 *   5. ready -> Activate Listing button
 */
export function ActivateListingPanel({ auctionPublicId }: ActivateListingPanelProps) {
  const qc = useQueryClient();
  const feeQ = useListingFeeConfig();
  const walletQ = useWallet(true);
  const [error, setError] = useState<string | null>(null);
  const [termsOpen, setTermsOpen] = useState(false);

  const mutation = useMutation({
    mutationFn: () => payListingFee(auctionPublicId, genIdempotencyKey()),
    onMutate: () => setError(null),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: activateAuctionKey(auctionPublicId) });
      qc.invalidateQueries({ queryKey: walletQueryKey });
    },
    onError: (e) => {
      // Code-driven error branches re-fetch the wallet so the panel
      // re-derives its render state from fresh data; only generic errors
      // surface inline copy.
      if (isApiError(e)) {
        const code = typeof e.problem.code === "string" ? e.problem.code : null;
        if (
          code === "WALLET_TERMS_NOT_ACCEPTED" ||
          code === "INSUFFICIENT_AVAILABLE_BALANCE" ||
          code === "PENALTY_OUTSTANDING"
        ) {
          qc.invalidateQueries({ queryKey: walletQueryKey });
          return;
        }
        setError(
          e.problem.detail ?? e.problem.title ?? "Could not activate listing.",
        );
        return;
      }
      setError(
        e instanceof Error ? e.message : "Could not activate listing.",
      );
    },
  });

  if (feeQ.isLoading || walletQ.isLoading || !feeQ.data || !walletQ.data) {
    return (
      <section className="rounded-lg bg-bg-subtle p-6">
        <LoadingSpinner label="Loading fee details…" />
      </section>
    );
  }

  const fee = feeQ.data.amountLindens;
  const wallet = walletQ.data;

  if (!wallet.termsAccepted) {
    return (
      <>
        <section
          aria-labelledby="activate-fee-heading"
          className="flex flex-col gap-3 rounded-lg bg-bg-subtle p-6"
        >
          <h2 id="activate-fee-heading" className="text-base font-bold tracking-tight text-fg">
            Accept wallet terms first
          </h2>
          <p className="text-sm text-fg">
            Listing fees are paid from your SLPA wallet. Accept the wallet
            terms of use to continue.
          </p>
          <Button
            variant="secondary"
            className="self-start"
            onClick={() => setTermsOpen(true)}
          >
            Accept wallet terms
          </Button>
        </section>
        <WalletTermsModal
          open={termsOpen}
          onClose={() => setTermsOpen(false)}
        />
      </>
    );
  }

  if (wallet.penaltyOwed > 0) {
    return (
      <section
        aria-labelledby="activate-fee-heading"
        className="flex flex-col gap-3 rounded-lg bg-bg-subtle p-6"
      >
        <h2 id="activate-fee-heading" className="text-base font-bold tracking-tight text-fg">
          Pay penalty before activating
        </h2>
        <p className="text-sm text-fg">
          You have an outstanding penalty of{" "}
          <strong>{formatLindens(wallet.penaltyOwed)}</strong>. Pay it from
          your wallet, then come back to activate.
        </p>
        <Link
          href="/wallet"
          className="self-start text-sm font-medium text-brand underline underline-offset-4 hover:opacity-80"
        >
          Open wallet
        </Link>
      </section>
    );
  }

  if (wallet.available < fee) {
    const short = fee - wallet.available;
    return (
      <section
        aria-labelledby="activate-fee-heading"
        className="flex flex-col gap-3 rounded-lg bg-bg-subtle p-6"
      >
        <h2 id="activate-fee-heading" className="text-base font-bold tracking-tight text-fg">
          Top up your wallet to activate
        </h2>
        <p className="text-sm text-fg">
          Listing fee is <strong>{formatLindens(fee)}</strong>. Your wallet
          has <strong>{formatLindens(wallet.available)}</strong> available —
          you need <strong>{formatLindens(short)}</strong> more. Pay any SLPA
          terminal in-world and your balance updates automatically.
        </p>
        <Button
          variant="secondary"
          onClick={() => walletQ.refetch()}
          loading={walletQ.isFetching}
        >
          Refresh balance
        </Button>
      </section>
    );
  }

  return (
    <section
      aria-labelledby="activate-fee-heading"
      className="flex flex-col gap-3 rounded-lg bg-bg-subtle p-6"
    >
      <h2 id="activate-fee-heading" className="text-base font-bold tracking-tight text-fg">
        Activate this listing
      </h2>
      <p className="text-sm text-fg">
        Listing fee is <strong>{formatLindens(fee)}</strong> — debited from
        your SLPA wallet. Available balance:{" "}
        <strong>{formatLindens(wallet.available)}</strong>.
      </p>
      <FormError message={error ?? undefined} />
      <Button
        onClick={() => mutation.mutate()}
        loading={mutation.isPending}
        disabled={mutation.isPending}
      >
        Activate Listing
      </Button>
    </section>
  );
}
