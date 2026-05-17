"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/Button";
import { FormError } from "@/components/ui/FormError";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { AlertTriangle, CheckCircle2, Loader2 } from "@/components/ui/icons";
import { useToast } from "@/components/ui/Toast";
import { ActivateStatusStepper } from "@/components/listing/ActivateStatusStepper";
import { CancelListingModal } from "@/components/listing/CancelListingModal";
import {
  activateAuctionKey,
  useActivateAuction,
} from "@/hooks/useActivateAuction";
import { ApiError, isApiError } from "@/lib/api";
import { triggerVerify } from "@/lib/api/auctions";
import type { SellerAuctionResponse } from "@/types/auction";
import { DraftEditorClient } from "./DraftEditorClient";

export interface ActivateClientProps {
  auctionPublicId: string;
}

const TERMINAL_REDIRECT_STATUSES = new Set([
  "CANCELLED",
  "SUSPENDED",
  "EXPIRED",
  "COMPLETED",
  "FROZEN",
  "TRANSFER_PENDING",
  "DISPUTED",
]);

/**
 * Client-side orchestrator for the activate flow (spec §5).
 *
 * Status dispatch:
 *   - DRAFT                                    → review preview + ActivateListingPanel
 *   - DRAFT_PAID                               → Verify-ownership button
 *   - VERIFICATION_FAILED                      → failure note + Retry button
 *   - VERIFICATION_PENDING                     → transient spinner (legacy state only)
 *   - ACTIVE                                   → success screen
 *   - Anything in TERMINAL_REDIRECT_STATUSES   → toast + /dashboard/listings
 *
 * Ownership verification is a single synchronous call: clicking the
 * Verify button fires {@code PUT /api/v1/auctions/{publicId}/verify}
 * with no body. The backend reads the parcel via the SL World API and
 * returns the auction with status flipped to ACTIVE or VERIFICATION_FAILED.
 *
 * The terminal-redirect effect runs via useEffect rather than an inline
 * router.replace during render — calling router.replace from the render
 * phase is a React 19 concurrent-rendering footgun (the old "can't update
 * component while rendering another" warning, amplified under Next 16).
 */
export function ActivateClient({ auctionPublicId }: ActivateClientProps) {
  const router = useRouter();
  const toast = useToast();
  const qc = useQueryClient();
  const [cancelOpen, setCancelOpen] = useState(false);
  const [verifyError, setVerifyError] = useState<string | null>(null);
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

  const verifyMutation = useMutation<SellerAuctionResponse, unknown, void>({
    mutationFn: () => triggerVerify(auctionPublicId),
    onMutate: () => setVerifyError(null),
    onSuccess: (next) => {
      qc.setQueryData(activateAuctionKey(auctionPublicId), next);
    },
    onError: (e) => {
      if (e instanceof ApiError || isApiError(e)) {
        setVerifyError(
          e.problem.detail ??
            e.problem.title ??
            "Verification could not be completed.",
        );
        return;
      }
      setVerifyError(
        e instanceof Error ? e.message : "Verification could not be completed.",
      );
    },
  });

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

  if (auction.status === "DRAFT") {
    return <DraftEditorClient auction={auction} />;
  }

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6 p-6">
      <ActivateStatusStepper status={auction.status} />
      {(auction.status === "DRAFT_PAID" ||
        auction.status === "VERIFICATION_FAILED") && (
        <VerifyOwnershipPanel
          status={auction.status}
          failureNotes={auction.verificationNotes}
          inlineError={verifyError}
          isPending={verifyMutation.isPending}
          onVerify={() => verifyMutation.mutate()}
        />
      )}
      {auction.status === "VERIFICATION_PENDING" && (
        <section
          aria-live="polite"
          className="flex flex-col items-center gap-3 rounded-lg bg-bg-subtle p-6 text-center"
        >
          <Loader2
            aria-hidden="true"
            className="size-7 animate-spin text-brand"
          />
          <p className="text-sm font-semibold text-fg">Verifying ownership…</p>
        </section>
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

interface VerifyOwnershipPanelProps {
  status: "DRAFT_PAID" | "VERIFICATION_FAILED";
  failureNotes: string | null;
  inlineError: string | null;
  isPending: boolean;
  onVerify: () => void;
}

/**
 * Single-button verification panel rendered on DRAFT_PAID and
 * VERIFICATION_FAILED. On failure we surface {@code verificationNotes}
 * so the seller can see why the previous attempt missed (e.g., observed
 * owner differs) before retrying.
 */
function VerifyOwnershipPanel({
  status,
  failureNotes,
  inlineError,
  isPending,
  onVerify,
}: VerifyOwnershipPanelProps) {
  const isRetry = status === "VERIFICATION_FAILED";
  return (
    <section
      aria-labelledby="verify-ownership-heading"
      className="flex flex-col gap-4 rounded-lg bg-bg-subtle p-6"
    >
      <header className="flex flex-col gap-1">
        <h2
          id="verify-ownership-heading"
          className="text-sm font-semibold tracking-tight text-fg"
        >
          Verify ownership
        </h2>
        <p className="text-xs text-fg-muted">
          We check the parcel owner via the Second Life World API. This
          usually completes in a second or two.
        </p>
      </header>
      {isRetry && failureNotes && (
        <div
          role="status"
          className="flex items-start gap-3 rounded-lg bg-danger-bg p-4 text-danger"
        >
          <AlertTriangle aria-hidden="true" className="size-5 shrink-0" />
          <div className="flex flex-1 flex-col gap-1">
            <p className="text-sm font-medium">
              Your last verification attempt failed
            </p>
            <p className="text-xs">{failureNotes}</p>
            <p className="text-xs">
              Adjust the parcel in-world if needed, then try again. No
              additional fee is required.
            </p>
          </div>
        </div>
      )}
      {inlineError && (
        <div
          role="alert"
          className="rounded-lg bg-danger-bg px-4 py-3 text-xs font-medium text-danger"
        >
          {inlineError}
        </div>
      )}
      <div className="flex flex-wrap items-center justify-end gap-2">
        <Button
          onClick={onVerify}
          disabled={isPending}
          loading={isPending}
          data-testid="verify-ownership-button"
        >
          {isPending
            ? "Checking…"
            : isRetry
              ? "Retry verify"
              : "Verify ownership"}
        </Button>
      </div>
    </section>
  );
}
