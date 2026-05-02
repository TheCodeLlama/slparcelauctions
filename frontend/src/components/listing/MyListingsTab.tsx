"use client";

import { useState, type ReactNode } from "react";
import Link from "next/link";
import { Button } from "@/components/ui/Button";
import { EmptyState } from "@/components/ui/EmptyState";
import { FormError } from "@/components/ui/FormError";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { ListChecks, Plus } from "@/components/ui/icons";
import {
  useMyListings,
  useMyListingsSuspendedCount,
  type MyListingsFilter,
} from "@/hooks/useMyListings";
import { ApiError, isApiError } from "@/lib/api";
import { useCurrentUser } from "@/lib/user";
import type { CurrentUser } from "@/lib/user/api";
import { FilterChipsRow } from "./FilterChipsRow";
import { ListingSummaryRow } from "./ListingSummaryRow";

/**
 * Spec §8.4 first paragraph: dashboard "Create new listing" CTA must render
 * as a disabled button with a tooltip when the seller cannot create a new
 * listing. The disabled-state copy mirrors the wizard's
 * {@link SuspensionErrorModal} reason copy so the seller sees the same
 * explanation whether they pre-clicked the dashboard CTA or hit the 403
 * backstop in the wizard.
 *
 * <p>Returns {@code null} when the seller can create a listing — the call
 * site renders the existing {@code <Link>} in that branch.
 */
function listingDisabledReason(user: CurrentUser | undefined): string | null {
  if (!user) return null;
  if (user.bannedFromListing) {
    return "You are permanently banned from creating new listings.";
  }
  const owesPenalty = (user.penaltyBalanceOwed ?? 0) > 0;
  if (owesPenalty) {
    return "You have an outstanding penalty balance. Pay at any SLPA terminal to resume listing.";
  }
  const suspensionUntilRaw = user.listingSuspensionUntil;
  const suspensionUntil = suspensionUntilRaw
    ? new Date(suspensionUntilRaw)
    : null;
  const timedSuspended = suspensionUntil != null && suspensionUntil > new Date();
  if (timedSuspended) {
    return "Your listing privileges are temporarily suspended.";
  }
  return null;
}

/**
 * Wraps the create-listing CTA so the disabled-state path lives in one
 * place. When {@link listingDisabledReason} returns {@code null} the CTA
 * renders as the existing {@code <Link>} → {@code <Button>} composite
 * (clean user). Otherwise it renders a disabled {@code <Button>} carrying
 * the suspension-reason copy as a {@code title} tooltip.
 */
function CreateListingCta({
  disabledReason,
  children,
}: {
  disabledReason: string | null;
  children: ReactNode;
}) {
  if (disabledReason) {
    return (
      <Button
        leftIcon={<Plus className="size-4" />}
        disabled
        title={disabledReason}
      >
        {children}
      </Button>
    );
  }
  return (
    <Link href="/listings/create">
      <Button leftIcon={<Plus className="size-4" />}>{children}</Button>
    </Link>
  );
}

/**
 * Top-level content for {@code /dashboard/listings}. Composes:
 *   - header with title, count badge, and [+ Create new listing] CTA
 *   - {@link FilterChipsRow} (Active / Drafts / Ended / Cancelled /
 *     Suspended, plus an always-visible "All")
 *   - scrollable list of {@link ListingSummaryRow}s (filtered by the
 *     selected chip)
 *
 * Data comes from {@link useMyListings} which hits {@code GET
 * /api/v1/users/me/auctions} and filters client-side (the backend
 * currently ignores status query params — see the hook's javadoc).
 *
 * Empty-state split (spec §6.3):
 *   - No listings at all        → full-page EmptyState with "Create your
 *                                  first listing" CTA.
 *   - No listings in this bucket → inline "No listings in this filter"
 *                                  text so sellers still see the chips
 *                                  and other buckets they could click.
 */
export function MyListingsTab() {
  const [filter, setFilter] = useState<MyListingsFilter>("All");
  const { listings, all, isLoading, isError, error } = useMyListings(filter);
  const suspendedCount = useMyListingsSuspendedCount();
  const { data: currentUser } = useCurrentUser();
  const disabledReason = listingDisabledReason(currentUser);

  if (isLoading) {
    return (
      <div className="flex justify-center py-12">
        <LoadingSpinner />
      </div>
    );
  }

  if (isError) {
    const message =
      error instanceof ApiError || isApiError(error)
        ? (error.problem.detail ??
          error.problem.title ??
          "Couldn't load your listings.")
        : error instanceof Error
          ? error.message
          : "Couldn't load your listings.";
    return <FormError message={message} />;
  }

  if (all.length === 0) {
    return (
      <EmptyState
        icon={ListChecks}
        headline="No listings yet"
        description="Put your first parcel up for auction to start selling."
      >
        <CreateListingCta disabledReason={disabledReason}>
          Create your first listing
        </CreateListingCta>
      </EmptyState>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-base font-bold tracking-tight text-fg">
          My Listings{" "}
          <span className="text-sm font-semibold text-fg-muted">
            ({all.length})
          </span>
        </h2>
        <CreateListingCta disabledReason={disabledReason}>
          Create new listing
        </CreateListingCta>
      </div>
      <FilterChipsRow
        value={filter}
        onChange={setFilter}
        suspendedCount={suspendedCount}
        totalCount={all.length}
      />
      {listings.length === 0 ? (
        <p className="py-6 text-center text-sm text-fg-muted">
          No listings in this filter.
        </p>
      ) : (
        <ul className="flex flex-col gap-2">
          {listings.map((a) => (
            <ListingSummaryRow key={a.id} auction={a} />
          ))}
        </ul>
      )}
    </div>
  );
}
