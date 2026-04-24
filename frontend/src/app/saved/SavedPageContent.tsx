"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Heart } from "@/components/ui/icons";
import { EmptyState } from "@/components/ui/EmptyState";
import { Button } from "@/components/ui/Button";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { CuratorTrayContent } from "@/components/curator/CuratorTrayContent";
import { useAuth } from "@/lib/auth";
import {
  defaultAuctionSearchQuery,
  queryFromSearchParams,
  searchParamsFromQuery,
} from "@/lib/search/url-codec";
import type { AuctionSearchQuery } from "@/types/search";

const INITIAL_QUERY: AuctionSearchQuery = {
  ...defaultAuctionSearchQuery,
  statusFilter: "active_only",
};

/**
 * Client body for the /saved page. Thin wrapper that:
 *   1. Short-circuits to a sign-in CTA when the caller is unauthenticated.
 *   2. Reads {@code useSearchParams} into a URL-synced query state and
 *      threads every change back out via {@code router.replace}.
 *   3. Delegates the actual list + pagination surface to
 *      {@link CuratorTrayContent} (shared with the drawer + sheet shells).
 */
export function SavedPageContent() {
  const session = useAuth();
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const [query, setQuery] = useState<AuctionSearchQuery>(() => {
    const sp = new URLSearchParams(searchParams?.toString() ?? "");
    const parsed = queryFromSearchParams(sp);
    return {
      ...INITIAL_QUERY,
      ...parsed,
    };
  });

  useEffect(() => {
    const urlQuery = queryFromSearchParams(
      new URLSearchParams(searchParams?.toString() ?? ""),
    );
    // eslint-disable-next-line react-hooks/set-state-in-effect -- URL is the external source of truth; mirroring it into local state on change is the whole point of this effect.
    setQuery({ ...INITIAL_QUERY, ...urlQuery });
  }, [searchParams]);

  const applyQuery = (next: AuctionSearchQuery) => {
    setQuery(next);
    const qs = searchParamsFromQuery(next).toString();
    const url = qs ? `${pathname}?${qs}` : pathname;
    router.replace(url, { scroll: false });
  };

  if (session.status === "loading") {
    return (
      <div className="flex justify-center py-12">
        <LoadingSpinner label="Loading your saved parcels..." />
      </div>
    );
  }

  if (session.status !== "authenticated") {
    return (
      <div className="mx-auto max-w-3xl px-4 py-8">
        <EmptyState
          icon={Heart}
          headline="Sign in to see your saved parcels"
          description="The Curator Tray is available to registered, signed-in users."
        >
          <Link href="/login?next=/saved">
            <Button variant="primary">Sign in</Button>
          </Link>
        </EmptyState>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-3xl px-4 py-8">
      <CuratorTrayContent query={query} onQueryChange={applyQuery} />
    </div>
  );
}
