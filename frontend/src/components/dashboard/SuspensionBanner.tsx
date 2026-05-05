"use client";

import { Card } from "@/components/ui/Card";
import { useCurrentUser } from "@/lib/user";

/**
 * Date formatter for the timed-suspension copy. Uses the user's locale via
 * the browser default and a sensible "Apr 25, 2026" format. Hour-precision
 * isn't shown — the suspension reads naturally on a calendar day boundary.
 */
function formatSuspensionDate(date: Date): string {
  return date.toLocaleDateString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}

/**
 * L$ amount formatter shared between the timed+debt and debt-only variants.
 * Uses the standard locale grouping so {@code 2500} reads as {@code 2,500}.
 */
function formatPenalty(amountL: number): string {
  return `L$${amountL.toLocaleString()}`;
}

/**
 * Dashboard banner that surfaces the seller's listing-suspension state
 * (Epic 08 sub-spec 2 §8.2). Renders one of four variants based on the
 * current {@code /me} payload, in priority order:
 *
 * <ol>
 *   <li>{@code bannedFromListing} — permanent ban; takes precedence over
 *       any other state.</li>
 *   <li>Timed suspension AND outstanding penalty — combined copy directing
 *       the seller to a terminal.</li>
 *   <li>Timed suspension only.</li>
 *   <li>Outstanding penalty only.</li>
 * </ol>
 *
 * <p>Returns {@code null} when the seller can create listings (no ban,
 * no active timed suspension, no outstanding L$ debt). The banner does
 * NOT include a "Pay now" button — sub-spec 2 ships the walk-in payment
 * model only; payment happens at any SLParcels terminal in-world.
 *
 * <p>Banner state freshness depends on the existing window-focus refetch
 * on {@code useCurrentUser} plus a 60s {@code staleTime} on the {@code /me}
 * query. No WS push for {@code PENALTY_CLEARED} ships in sub-spec 2.
 */
export function SuspensionBanner() {
  const { data: user } = useCurrentUser();
  if (!user) return null;

  const banned = user.bannedFromListing;
  const suspendedUntilRaw = user.listingSuspensionUntil;
  const suspendedUntil = suspendedUntilRaw ? new Date(suspendedUntilRaw) : null;
  const isTimedSuspended = suspendedUntil != null && suspendedUntil > new Date();
  const owesPenalty = (user.penaltyBalanceOwed ?? 0) > 0;

  if (banned) {
    return (
      <Card
        className="bg-danger-bg text-danger"
        data-testid="suspension-banner"
        data-variant="banned"
        role="alert"
      >
        <Card.Body>
          <p className="text-sm">
            Your listing privileges have been permanently suspended. Contact
            support to request a review.
          </p>
        </Card.Body>
      </Card>
    );
  }

  if (isTimedSuspended && owesPenalty && suspendedUntil) {
    return (
      <Card
        className="bg-info-bg text-info"
        data-testid="suspension-banner"
        data-variant="timed-and-debt"
        role="alert"
      >
        <Card.Body>
          <p className="text-sm">
            Listing suspended until {formatSuspensionDate(suspendedUntil)}. You
            also owe{" "}
            <strong className="font-bold">
              {formatPenalty(user.penaltyBalanceOwed)}
            </strong>
            . Visit any SLParcels terminal to pay.
          </p>
        </Card.Body>
      </Card>
    );
  }

  if (isTimedSuspended && suspendedUntil) {
    return (
      <Card
        className="bg-info-bg text-info"
        data-testid="suspension-banner"
        data-variant="timed-only"
        role="alert"
      >
        <Card.Body>
          <p className="text-sm">
            Listing suspended until {formatSuspensionDate(suspendedUntil)}.
          </p>
        </Card.Body>
      </Card>
    );
  }

  if (owesPenalty) {
    return (
      <Card
        className="bg-info-bg text-info"
        data-testid="suspension-banner"
        data-variant="debt-only"
        role="alert"
      >
        <Card.Body>
          <p className="text-sm">
            You owe{" "}
            <strong className="font-bold">
              {formatPenalty(user.penaltyBalanceOwed)}
            </strong>{" "}
            in cancellation penalties. Visit any SLParcels terminal to pay and
            resume listing.
          </p>
        </Card.Body>
      </Card>
    );
  }

  return null;
}
