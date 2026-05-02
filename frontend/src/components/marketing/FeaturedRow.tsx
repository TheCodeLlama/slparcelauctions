import Link from "next/link";
import { ArrowRight } from "@/components/ui/icons";
import { SectionHeading } from "@/components/ui";
import { ListingCard } from "@/components/auction/ListingCard";
import type { AuctionSearchResultDto } from "@/types/search";

export type FeaturedRowResult = PromiseSettledResult<{
  content: AuctionSearchResultDto[];
}>;

export interface FeaturedRowProps {
  title: string;
  /** Optional sub-copy under the title (e.g. "Auctions closing in the next few hours."). */
  sub?: string;
  /** Destination for the "View all →" link — typically `/browse?sort=...`. */
  sortLink: string;
  /** Pre-awaited settled result from the server component's Promise.allSettled. */
  result: FeaturedRowResult;
  /** Optional override for the fulfilled-empty placeholder copy. */
  emptyMessage?: string;
  /** Grid column count at desktop. Default 4. */
  columns?: 3 | 4;
}

export function FeaturedRow({ title, sub, sortLink, result, emptyMessage, columns = 4 }: FeaturedRowProps) {
  return (
    <section className="mx-auto w-full max-w-[var(--container-w)] px-6 py-10" data-testid="featured-row">
      <SectionHeading
        title={title}
        sub={sub}
        right={
          <Link
            href={sortLink}
            className="inline-flex items-center gap-1 rounded-sm px-3 py-2 text-sm font-medium text-fg-muted transition-colors hover:bg-bg-hover hover:text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
          >
            View all
            <ArrowRight className="size-3.5" aria-hidden="true" />
          </Link>
        }
      />
      <FeaturedRowBody
        title={title}
        result={result}
        emptyMessage={emptyMessage}
        columns={columns}
      />
    </section>
  );
}

function FeaturedRowBody({ title, result, emptyMessage, columns }: Pick<FeaturedRowProps, "title" | "result" | "emptyMessage"> & { columns: 3 | 4 }) {
  if (result.status === "rejected") {
    return (
      <p className="text-sm text-fg-muted" data-testid="featured-row-unavailable">
        {title} auctions are temporarily unavailable.
      </p>
    );
  }

  const { content } = result.value;
  if (content.length === 0) {
    return (
      <p className="text-sm text-fg-muted" data-testid="featured-row-empty">
        {emptyMessage ?? `No listings ${title.toLowerCase()} right now.`}
      </p>
    );
  }

  const gridClass = columns === 3
    ? "grid grid-cols-1 gap-5 sm:grid-cols-2 md:grid-cols-3"
    : "grid grid-cols-1 gap-5 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4";

  return (
    <div className={gridClass} data-testid="featured-row-grid">
      {content.map((listing) => (
        <ListingCard key={listing.id} listing={listing} variant="default" />
      ))}
    </div>
  );
}
