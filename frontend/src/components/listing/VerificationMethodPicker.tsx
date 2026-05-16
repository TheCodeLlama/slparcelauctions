"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/Button";
import { AlertTriangle, XCircle } from "@/components/ui/icons";
import { ApiError, isApiError } from "@/lib/api";
import { triggerVerify } from "@/lib/api/auctions";
import type {
  SellerAuctionResponse,
  VerificationMethod,
} from "@/types/auction";
import { activateAuctionKey } from "@/hooks/useActivateAuction";

type MethodBody = string | React.ReactNode;

interface MethodCard {
  key: VerificationMethod;
  title: string;
  body: MethodBody;
  /** Button label. Defaults to "Use this method"; method 3 overrides to
   *  "Verify" because the user has to set up the parcel sale in SL
   *  BEFORE clicking. */
  actionLabel?: string;
}

const METHODS: readonly MethodCard[] = [
  {
    key: "UUID_ENTRY",
    title: "Manual UUID check",
    body:
      "Fastest verification. We check your avatar UUID against the parcel's owner via the SL World API. Works for individually-owned land only.",
  },
  {
    key: "REZZABLE",
    title: "Rezzable terminal",
    body:
      "We give you a one-time code. Rez an SLParcels parcel terminal on your land and it verifies ownership on your behalf. Works for individually-owned land.",
  },
  {
    key: "SALE_TO_BOT",
    title: "Sale-to-bot",
    actionLabel: "Verify",
    body: (
      <>
        <p className="text-xs text-fg-muted">
          Required for group-owned land. Set the parcel for sale to our
          escrow account first, THEN click Verify so the bot starts
          watching.
        </p>
        <ol className="list-decimal pl-4 flex flex-col gap-0.5 text-xs text-fg">
          <li>Open the SL Land menu on the parcel.</li>
          <li>
            Choose <em>Set Land for Sale&hellip;</em>
          </li>
          <li>
            Buyer: <strong>SLPAEscrow Resident</strong>
          </li>
          <li>
            Price: <strong>L$999,999,999</strong>
          </li>
          <li>
            Click <em>Sell</em> to confirm in-world.
          </li>
          <li>Come back here and click Verify.</li>
        </ol>
      </>
    ),
  },
];

export interface VerificationMethodPickerProps {
  auctionPublicId: number | string;
  /**
   * Reason shown in the retry banner when the previous verify attempt
   * failed. Null/undefined hides the banner.
   */
  lastFailureNotes?: string | null;
}

/**
 * DRAFT_PAID / VERIFICATION_FAILED panel: three method cards, each one
 * a POST to {@code PUT /auctions/{id}/verify}. The Activate page picks
 * up the resulting status change on its next poll tick and re-renders
 * the in-progress panel.
 *
 * Error handling: a 422 on a group-owned parcel is remapped to a
 * prescriptive "pick Sale-to-bot" message (spec §4.3.2). Any other
 * error surfaces the server's detail message, or a generic fallback.
 *
 * Dismissible failure banner: if the last attempt failed, render the
 * amber notes above the cards. The seller can dismiss the banner
 * locally — it just re-appears on the next poll if the status is still
 * VERIFICATION_FAILED, but dismissing is useful after clicking a
 * method and while the UI waits a beat for the status to flip.
 */
export function VerificationMethodPicker({
  auctionPublicId,
  lastFailureNotes,
}: VerificationMethodPickerProps) {
  const qc = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [bannerDismissed, setBannerDismissed] = useState(false);

  const mutation = useMutation<
    SellerAuctionResponse,
    unknown,
    VerificationMethod
  >({
    mutationFn: (method) => triggerVerify(auctionPublicId, { method }),
    onMutate: () => setError(null),
    onSuccess: (auction) => {
      qc.setQueryData(activateAuctionKey(auctionPublicId), auction);
    },
    onError: (e) => {
      if (isApiError(e) && e.status === 422) {
        setError(
          "This method doesn't work for group-owned land. Pick Sale-to-bot instead.",
        );
        return;
      }
      if (e instanceof ApiError) {
        setError(
          e.problem.detail ??
            e.problem.title ??
            "Verification could not be started.",
        );
        return;
      }
      setError(
        e instanceof Error ? e.message : "Verification could not be started.",
      );
    },
  });

  const showFailureBanner =
    !!lastFailureNotes && !bannerDismissed;

  return (
    <div className="flex flex-col gap-4">
      {showFailureBanner && (
        <div
          role="status"
          className="flex items-start gap-3 rounded-lg bg-danger-bg p-4 text-danger"
        >
          <AlertTriangle aria-hidden="true" className="size-5 shrink-0" />
          <div className="flex-1 flex flex-col gap-1">
            <p className="text-sm font-medium">Your last verification attempt failed</p>
            <p className="text-xs">{lastFailureNotes}</p>
            <p className="text-xs">
              Pick a method to try again. No additional fee needed.
            </p>
          </div>
          <button
            type="button"
            aria-label="Dismiss failure notice"
            onClick={() => setBannerDismissed(true)}
            className="text-danger hover:opacity-80"
          >
            <XCircle aria-hidden="true" className="size-5" />
          </button>
        </div>
      )}
      {error && (
        <div
          role="alert"
          className="rounded-lg bg-danger-bg px-4 py-3 text-xs font-medium text-danger"
        >
          {error}
        </div>
      )}
      <div className="grid gap-4 md:grid-cols-3">
        {METHODS.map((method) => {
          const label = method.actionLabel ?? "Use this method";
          return (
            <article
              key={method.key}
              className="flex flex-col gap-3 rounded-lg bg-bg-subtle p-4"
            >
              <h3 className="text-sm font-semibold tracking-tight text-fg">{method.title}</h3>
              {typeof method.body === "string" ? (
                <p className="text-xs text-fg-muted">{method.body}</p>
              ) : (
                <div className="flex flex-col gap-2">{method.body}</div>
              )}
              <div className="mt-auto">
                <Button
                  onClick={() => mutation.mutate(method.key)}
                  disabled={mutation.isPending}
                  loading={mutation.isPending && mutation.variables === method.key}
                >
                  {mutation.isPending && mutation.variables === method.key
                    ? "Starting…"
                    : label}
                </Button>
              </div>
            </article>
          );
        })}
      </div>
    </div>
  );
}
