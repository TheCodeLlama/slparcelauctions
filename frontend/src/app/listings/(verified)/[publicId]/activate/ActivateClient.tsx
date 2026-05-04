"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { Button } from "@/components/ui/Button";
import { FormError } from "@/components/ui/FormError";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { CheckCircle2 } from "@/components/ui/icons";
import { useToast } from "@/components/ui/Toast";
import { ActivateListingPanel } from "@/components/listing/ActivateListingPanel";
import { ActivateStatusStepper } from "@/components/listing/ActivateStatusStepper";
import { CancelListingModal } from "@/components/listing/CancelListingModal";
import { ListingPreviewCard } from "@/components/listing/ListingPreviewCard";
import { VerificationInProgressPanel } from "@/components/listing/VerificationInProgressPanel";
import { VerificationMethodPicker } from "@/components/listing/VerificationMethodPicker";
import { useActivateAuction } from "@/hooks/useActivateAuction";
import { isApiError } from "@/lib/api";

export interface ActivateClientProps {
  auctionPublicId: string;
}

const TERMINAL_REDIRECT_STATUSES = new Set([
  "CANCELLED",
  "SUSPENDED",
  "ENDED",
  "EXPIRED",
  "COMPLETED",
  "ESCROW_PENDING",
  "ESCROW_FUNDED",
  "TRANSFER_PENDING",
  "DISPUTED",
]);

/**
 * Client-side orchestrator for the activate flow (spec §5).
 *
 * Status dispatch:
 *   - DRAFT                                    → review preview + ActivateListingPanel
 *   - DRAFT_PAID | VERIFICATION_FAILED         → VerificationMethodPicker
 *   - VERIFICATION_PENDING                     → VerificationInProgressPanel
 *   - ACTIVE                                   → success screen
 *   - Anything in TERMINAL_REDIRECT_STATUSES   → toast + /dashboard/listings
 *
 * The terminal-redirect effect runs via useEffect rather than an inline
 * router.replace during render — calling router.replace from the render
 * phase is a React 19 concurrent-rendering footgun (the old "can't update
 * component while rendering another" warning, amplified under Next 16).
 */
export function ActivateClient({ auctionPublicId }: ActivateClientProps) {
  const router = useRouter();
  const toast = useToast();
  const [cancelOpen, setCancelOpen] = useState(false);
  const { data: auction, isLoading, error } = useActivateAuction(auctionPublicId);
  const status = auction?.status;

  useEffect(() => {
    if (!status) return;
    if (TERMINAL_REDIRECT_STATUSES.has(status)) {
      toast.success(`Listing is ${status.toLowerCase()}.`);
      router.replace("/dashboard/listings");
    }
    // We intentionally depend on status + the refs to router/toast so
    // the effect only fires once the polling hook has observed the
    // terminal status.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status]);

  if (isLoading) {
    return (
      <div className="mx-auto max-w-3xl p-6">
        <LoadingSpinner label="Loading listing…" />
      </div>
    );
  }

  if (error || !auction) {
    const message = isApiError(error)
      ? error.problem.detail ?? error.problem.title ?? "Could not load listing."
      : error instanceof Error
        ? error.message
        : "Could not load listing.";
    return (
      <div className="mx-auto max-w-3xl p-6">
        <FormError message={message} />
      </div>
    );
  }

  if (auction.status === "ACTIVE") {
    return (
      <div className="mx-auto flex max-w-3xl flex-col items-center gap-6 p-6 text-center">
        <CheckCircle2
          aria-hidden="true"
          className="size-12 text-brand"
        />
        <h1 className="text-lg font-bold tracking-tight text-fg">
          Your listing is live.
        </h1>
        <p className="text-sm text-fg-muted">
          The public browse page picks this up on its next refresh.
        </p>
        <div className="flex flex-wrap justify-center gap-3">
          <Button
            variant="secondary"
            onClick={() => router.push("/dashboard/listings")}
          >
            Back to My Listings
          </Button>
          <Link href={`/auction/${auction.publicId}`}>
            <Button>View public listing</Button>
          </Link>
        </div>
      </div>
    );
  }

  if (TERMINAL_REDIRECT_STATUSES.has(auction.status)) {
    // The effect above will navigate; render a transient spinner so the
    // page isn't briefly blank.
    return (
      <div className="mx-auto max-w-3xl p-6">
        <LoadingSpinner label="Redirecting…" />
      </div>
    );
  }

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6 p-6">
      <ActivateStatusStepper status={auction.status} />
      {auction.status === "DRAFT" && (
        <>
          <ListingPreviewCard auction={auction} isPreview />
          <ActivateListingPanel auctionPublicId={auction.publicId} />
        </>
      )}
      {(auction.status === "DRAFT_PAID" ||
        auction.status === "VERIFICATION_FAILED") && (
        <VerificationMethodPicker
          auctionPublicId={auction.publicId}
          lastFailureNotes={
            auction.status === "VERIFICATION_FAILED"
              ? auction.verificationNotes
              : null
          }
        />
      )}
      {auction.status === "VERIFICATION_PENDING" && (
        <VerificationInProgressPanel auction={auction} />
      )}
      <div className="border-t border-border-subtle pt-4">
        <button
          type="button"
          onClick={() => setCancelOpen(true)}
          className="text-xs font-medium text-danger underline underline-offset-4 hover:opacity-80"
        >
          Cancel this listing
        </button>
      </div>
      <CancelListingModal
        open={cancelOpen}
        onClose={() => setCancelOpen(false)}
        auction={auction}
      />
    </div>
  );
}
