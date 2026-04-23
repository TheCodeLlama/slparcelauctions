"use client";

import { useState } from "react";
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
import { FilterChipsRow } from "./FilterChipsRow";
import { ListingSummaryRow } from "./ListingSummaryRow";

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
        <Link href="/listings/create">
          <Button leftIcon={<Plus className="size-4" />}>
            Create your first listing
          </Button>
        </Link>
      </EmptyState>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-title-lg text-on-surface">
          My Listings{" "}
          <span className="text-title-sm text-on-surface-variant">
            ({all.length})
          </span>
        </h2>
        <Link href="/listings/create">
          <Button leftIcon={<Plus className="size-4" />}>
            Create new listing
          </Button>
        </Link>
      </div>
      <FilterChipsRow
        value={filter}
        onChange={setFilter}
        suspendedCount={suspendedCount}
        totalCount={all.length}
      />
      {listings.length === 0 ? (
        <p className="py-6 text-center text-body-md text-on-surface-variant">
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
