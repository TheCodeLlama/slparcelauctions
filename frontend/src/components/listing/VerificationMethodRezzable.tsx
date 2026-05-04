"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/Button";
import { CodeDisplay } from "@/components/ui/CodeDisplay";
import { CountdownTimer } from "@/components/ui/CountdownTimer";
import { triggerVerify } from "@/lib/api/auctions";
import type {
  PendingVerification,
  SellerAuctionResponse,
} from "@/types/auction";
import { activateAuctionKey } from "@/hooks/useActivateAuction";

export interface VerificationMethodRezzableProps {
  auctionPublicId: number | string;
  pending: PendingVerification;
}

/**
 * REZZABLE in-progress panel. Shows the parcel verification code for
 * the seller to paste into an SLPA Parcel Terminal object they rez
 * on-parcel.
 *
 * Code expiry:
 *   - While the backend-issued {@code codeExpiresAt} is in the future
 *     we render a CountdownTimer next to the code.
 *   - Once the timer has crossed zero (either because the seller let
 *     it elapse or because they came back later) we hide the timer
 *     and expose a "Regenerate code" button that re-calls
 *     {@code PUT /verify} with method=REZZABLE to mint a fresh code.
 *     The backend's {@code ParcelCodeExpiryJob} independently flips
 *     the auction to VERIFICATION_FAILED a short while after expiry,
 *     at which point the activate page pivots back to the method
 *     picker — but the in-page regenerate short-circuit is smoother
 *     for sellers who realize they're late before the job fires.
 */
export function VerificationMethodRezzable({
  auctionPublicId,
  pending,
}: VerificationMethodRezzableProps) {
  const qc = useQueryClient();
  const regen = useMutation<SellerAuctionResponse, Error, void>({
    mutationFn: () => triggerVerify(auctionPublicId, { method: "REZZABLE" }),
    onSuccess: (auction) =>
      qc.setQueryData(activateAuctionKey(auctionPublicId), auction),
  });

  const expiresAt = useMemo(
    () => (pending.codeExpiresAt ? new Date(pending.codeExpiresAt) : null),
    [pending.codeExpiresAt],
  );

  // Purity-safe expiry tracking: compute the initial value once in
  // useState's initializer (runs before render, not during). The state
  // advances to true via CountdownTimer.onExpire (which fires from a
  // passive tick, not during a parent render) or by a one-shot timeout
  // scheduled in a layout effect. Calling Date.now() during render
  // violates the react-hooks/purity rule in React 19 strict.
  const [expired, setExpired] = useState<boolean>(() =>
    expiresAt ? expiresAt.getTime() <= Date.now() : false,
  );

  useEffect(() => {
    if (!expiresAt) return;
    const remaining = expiresAt.getTime() - Date.now();
    if (remaining <= 0) return;
    const id = setTimeout(() => setExpired(true), remaining);
    return () => clearTimeout(id);
  }, [expiresAt]);

  const handleTimerExpire = useCallback(() => setExpired(true), []);

  return (
    <div className="flex flex-col gap-4">
      {pending.code ? (
        <section className="flex flex-col gap-3 rounded-lg bg-bg-subtle p-6">
          <p className="text-xs font-medium text-fg-muted uppercase tracking-wide">
            Parcel verification code
          </p>
          <CodeDisplay code={pending.code} />
          {expiresAt && !expired && (
            <p className="text-xs text-fg-muted flex items-center gap-2">
              <span>Expires in</span>
              <CountdownTimer
                expiresAt={expiresAt}
                onExpire={handleTimerExpire}
              />
            </p>
          )}
          {expired && (
            <div className="flex flex-col gap-2">
              <p className="text-xs text-fg-muted">
                This code has expired.
              </p>
              <Button
                onClick={() => regen.mutate()}
                disabled={regen.isPending}
                loading={regen.isPending}
              >
                Regenerate code
              </Button>
            </div>
          )}
        </section>
      ) : null}
      <ol className="list-decimal list-inside flex flex-col gap-2 rounded-lg bg-bg-subtle p-6 text-sm text-fg">
        <li>
          Open your SL inventory and find the &quot;SLPA Parcel Terminal&quot;
          object.
        </li>
        <li>Rez it on the parcel you&apos;re listing.</li>
        <li>
          The terminal automatically verifies ownership and advances your
          listing.
        </li>
      </ol>
    </div>
  );
}
