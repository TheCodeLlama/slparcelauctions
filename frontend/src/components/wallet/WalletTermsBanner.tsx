"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Wallet } from "@/components/ui/icons";
import { useCurrentUser } from "@/lib/user";
import { useWallet } from "@/lib/wallet/use-wallet";
import {
  WalletTermsModal,
  WALLET_TERMS_VERSION,
} from "@/components/wallet/WalletTermsModal";
import {
  isTermsBannerDismissed,
  dismissTermsBanner,
} from "@/lib/wallet/terms-banner-dismissed";

/**
 * Site-wide nudge banner under the global header. Verified users who have
 * not accepted the wallet Terms of Use see a single persistent prompt with
 * a one-click path to the acceptance modal, plus a per-browser "Don't show
 * again" suppressor. Returns null in every other case (guest, unverified,
 * wallet still loading, terms accepted, dismissed).
 *
 * The dismissal only hides this passive nudge. Actions that genuinely
 * cannot proceed without accepted terms (the listing-fee hard gate) still
 * open the same modal -- see ActivateListingPanel.
 *
 * Spec: docs/superpowers/specs/2026-05-18-wallet-terms-modal-everywhere-design.md
 */
export function WalletTermsBanner() {
  const { data: user } = useCurrentUser();
  const verified = user?.verified === true;
  const { data: wallet } = useWallet(verified);

  const [dismissed, setDismissed] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);

  // Read the per-browser dismissal only after mount. localStorage is
  // unavailable during SSR / the Amplify build, and reading it during
  // render would desync hydration.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setDismissed(isTermsBannerDismissed(WALLET_TERMS_VERSION));
  }, []);

  if (!verified) return null;
  if (!wallet) return null; // loading -- no flash, no layout jump
  if (wallet.termsAccepted) return null;
  if (dismissed) return null;

  return (
    <>
      <div
        role="region"
        aria-label="Wallet terms"
        className="border-b border-border bg-bg-subtle"
      >
        <div className="mx-auto flex w-full max-w-[var(--container-w)] flex-wrap items-center gap-3 px-6 py-2.5">
          <Wallet className="h-4 w-4 shrink-0 text-brand" aria-hidden />
          <p className="flex-1 text-sm text-fg">
            Accept the SLParcels Wallet Terms of Use to pay listing fees and
            use your wallet.
          </p>
          <Button
            variant="primary"
            size="sm"
            onClick={() => setModalOpen(true)}
          >
            Accept Wallet Terms
          </Button>
          <Button
            variant="tertiary"
            size="sm"
            onClick={() => {
              dismissTermsBanner(WALLET_TERMS_VERSION);
              setDismissed(true);
            }}
          >
            Don&apos;t show again
          </Button>
        </div>
      </div>
      <WalletTermsModal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
      />
    </>
  );
}
