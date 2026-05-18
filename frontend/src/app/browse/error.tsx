"use client"; // Error boundaries must be Client Components (Next.js App Router).

import { useEffect } from "react";
import Link from "next/link";
import { Button } from "@/components/ui/Button";
import { AlertTriangle, RefreshCw } from "@/components/ui/icons";

/**
 * Route-scoped error boundary for /browse.
 *
 * Next.js 16 file-convention contract (see
 * node_modules/next/dist/docs/01-app/03-api-reference/03-file-conventions/error.md):
 * a Client Component receiving `{ error, reset }` (16.2 also forwards
 * `unstable_retry`; this boundary uses the stable `reset` per spec). It
 * wraps `page.tsx` and its nested tree in a React error boundary without
 * touching the layout above it, so a thrown SSR error degrades to this
 * calm fallback instead of a generic Next 500.
 *
 * Part 1 already swallows 4xx filter mistakes (e.g. an unknown
 * near_region) and renders the normal shell with an inline message. This
 * boundary is the defense-in-depth layer for the genuinely-broken case:
 * a 5xx, a network failure, or any other unexpected throw rethrown from
 * resolveBrowseInitialData. Those stay visibly distinct from a filter
 * typo because only they reach this screen.
 */
export default function BrowseError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    // Surface the error for log correlation. Server-thrown errors arrive
    // with a `digest` that matches the server-side log entry.
    console.error("[browse] route error boundary caught:", error);
  }, [error]);

  return (
    <div className="flex min-h-[60vh] flex-1 flex-col items-center justify-center gap-6 p-8 text-center">
      <div className="flex size-12 items-center justify-center rounded-full bg-bg-subtle text-fg-muted">
        <AlertTriangle className="size-6" />
      </div>
      <div className="flex max-w-md flex-col gap-2">
        <h1 className="text-xl font-semibold text-fg">
          Couldn&apos;t load Browse.
        </h1>
        <p className="text-sm text-fg-muted">
          Something went wrong while loading auctions. This is on our side,
          not your filters. Try again in a moment, or start over with no
          filters.
        </p>
      </div>
      <div className="flex flex-wrap items-center justify-center gap-3">
        <Button
          variant="primary"
          onClick={() => reset()}
          leftIcon={<RefreshCw className="size-4" />}
        >
          Try again
        </Button>
        <Link
          href="/browse"
          className="inline-flex h-9 items-center justify-center rounded-sm border border-border bg-surface-raised px-4 text-sm font-medium text-fg transition-colors hover:bg-bg-hover hover:border-border-strong focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
        >
          Browse with no filters
        </Link>
      </div>
    </div>
  );
}
