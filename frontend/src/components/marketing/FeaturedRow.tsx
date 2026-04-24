// frontend/src/components/marketing/FeaturedRow.tsx
import Link from "next/link";
import { ArrowRight } from "@/components/ui/icons";
import { ListingCard } from "@/components/auction/ListingCard";
import type { AuctionSearchResultDto } from "@/types/search";

export type FeaturedRowResult = PromiseSettledResult<{
  content: AuctionSearchResultDto[];
}>;

export interface FeaturedRowProps {
  /** Section header copy (e.g., "Ending Soon"). */
  title: string;
  /** Destination for the "View all →" link — typically `/browse?sort=...`. */
  sortLink: string;
  /** Pre-awaited settled result from the server component's Promise.allSettled. */
  result: FeaturedRowResult;
  /** Optional override for the fulfilled-empty placeholder copy. */
  emptyMessage?: string;
}

/**
 * Labeled horizontal rail of {@link ListingCard} (compact variant). Accepts a
 * pre-awaited {@link PromiseSettledResult} so the homepage server component
 * can fan out three featured fetches under a single {@code Promise.allSettled}
 * and hand each rail its own slice — one rail's 5xx doesn't block the other
 * two.
 *
 * Three render branches:
 *
 *   - {@code rejected} — the fetch errored (network, 5xx, etc). Renders a
 *     neutral "temporarily unavailable" placeholder. Deliberately does NOT
 *     surface the underlying error — the homepage is a public marketing
 *     surface, and a raw stack trace here would be worse than a soft miss.
 *
 *   - {@code fulfilled} with empty {@code content} — the endpoint responded
 *     cleanly with no rows. This is expected for brand-new categories
 *     (just-listed with no recent actives; most-active with no 24h activity).
 *     Copy leans positive ("No listings ending soon right now.") so it reads
 *     as a state rather than a failure.
 *
 *   - {@code fulfilled} with content — horizontal scrolling rail of cards.
 *     CSS scroll-snap keeps the mobile gesture ergonomic; no hover arrows
 *     because the swipe affordance already covers the primary surface.
 */
export function FeaturedRow({
  title,
  sortLink,
  result,
  emptyMessage,
}: FeaturedRowProps) {
  return (
    <section className="py-10 px-6 md:px-8" data-testid="featured-row">
      <div className="mx-auto max-w-7xl">
        <div className="mb-6 flex items-baseline justify-between gap-4">
          <h2 className="font-display text-display-sm font-bold tracking-tight text-on-surface">
            {title}
          </h2>
          <Link
            href={sortLink}
            className="inline-flex items-center gap-1 text-body-md font-medium text-primary hover:underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
          >
            View all
            <ArrowRight className="size-4" aria-hidden="true" />
          </Link>
        </div>
        <FeaturedRowBody
          title={title}
          result={result}
          emptyMessage={emptyMessage}
        />
      </div>
    </section>
  );
}

function FeaturedRowBody({
  title,
  result,
  emptyMessage,
}: Pick<FeaturedRowProps, "title" | "result" | "emptyMessage">) {
  if (result.status === "rejected") {
    return (
      <p
        className="text-body-md text-on-surface-variant"
        data-testid="featured-row-unavailable"
      >
        {title} auctions are temporarily unavailable.
      </p>
    );
  }

  const { content } = result.value;
  if (content.length === 0) {
    return (
      <p
        className="text-body-md text-on-surface-variant"
        data-testid="featured-row-empty"
      >
        {emptyMessage ?? `No listings ${title.toLowerCase()} right now.`}
      </p>
    );
  }

  return (
    <div
      className="flex snap-x snap-mandatory gap-4 overflow-x-auto pb-2"
      data-testid="featured-row-rail"
    >
      {content.map((listing) => (
        <div
          key={listing.id}
          className="w-[280px] shrink-0 snap-start"
        >
          <ListingCard listing={listing} variant="compact" />
        </div>
      ))}
    </div>
  );
}
