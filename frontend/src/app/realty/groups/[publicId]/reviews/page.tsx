import Link from "next/link";
import { notFound } from "next/navigation";

import { GroupRatingBadge } from "@/components/realty/GroupRatingBadge";
import { isApiError } from "@/lib/api";
import { fetchGroupReviews } from "@/lib/api/realtyGroupReviews";
import { realtyGroupsApi } from "@/lib/api/realtyGroups";
import { formatRelativeTime } from "@/lib/time/relativeTime";

/**
 * Sub-project G §13 — dedicated group reviews page.
 *
 * <p>Server component. The backend endpoint
 * {@code GET /api/v1/realty/groups/{publicId}/reviews} is anonymous-accessible
 * (the {@code permitAll} matcher mirrors the user-side public reviews
 * endpoint), so SSR fetches without a JWT just work.
 *
 * <p>{@code force-dynamic} because the review list and the rating aggregate
 * change as reviews land; build-time prerendering would couple the Amplify
 * build to whatever the API returned at build time and freeze the badge on
 * every visitor's first hit until the next deploy.
 */
export const dynamic = "force-dynamic";

interface PageProps {
  params: Promise<{ publicId: string }>;
  searchParams?: Promise<{ page?: string }>;
}

export default async function GroupReviewsPage({
  params,
  searchParams,
}: PageProps) {
  const { publicId } = await params;
  if (!publicId) notFound();

  const { page: pageParam } = (await searchParams) ?? {};
  const pageIndex = Math.max(0, Number(pageParam ?? "0") || 0);

  const group = await fetchGroupOrNull(publicId);
  if (!group) notFound();

  const reviews = await fetchReviewsOrEmpty(publicId, pageIndex);

  return (
    <main className="mx-auto max-w-3xl px-4 py-8 flex flex-col gap-6">
      <header className="flex flex-col gap-2">
        <Link
          href={`/group/${encodeURIComponent(group.slug)}`}
          className="text-xs text-fg-muted hover:underline w-fit"
        >
          Back to {group.name}
        </Link>
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-bold tracking-tight font-display">
            {group.name} — reviews
          </h1>
          <GroupRatingBadge rating={group.rating ?? null} />
        </div>
      </header>

      {reviews.content.length === 0 ? (
        <p className="text-sm text-fg-muted">
          This group has no reviews yet.
        </p>
      ) : (
        <ul className="divide-y divide-border">
          {reviews.content.map((r) => (
            <li
              key={`${r.reviewerPublicId}-${r.auctionPublicId}-${r.createdAt}`}
              className="py-4 flex flex-col gap-1"
            >
              <div className="flex items-center justify-between gap-3">
                <Link
                  href={`/users/${encodeURIComponent(r.reviewerPublicId)}`}
                  className="text-sm font-medium hover:underline"
                >
                  {r.reviewerDisplayName}
                </Link>
                <time
                  className="text-xs text-fg-muted"
                  dateTime={r.createdAt}
                  title={r.createdAt}
                >
                  {formatRelativeTime(r.createdAt)}
                </time>
              </div>
              <div className="text-sm" aria-label={`${r.rating} out of 5 stars`}>
                {renderStars(r.rating)}{" "}
                <span className="text-fg-muted">{r.rating}/5</span>
              </div>
              {r.comment && (
                <p className="text-sm whitespace-pre-line">{r.comment}</p>
              )}
              <Link
                href={`/auction/${encodeURIComponent(r.auctionPublicId)}`}
                className="text-xs text-fg-muted hover:underline w-fit"
              >
                {r.auctionTitle}
              </Link>
            </li>
          ))}
        </ul>
      )}

      {reviews.totalPages > 1 && (
        <nav
          className="flex items-center justify-between text-sm"
          aria-label="Pagination"
        >
          {pageIndex > 0 ? (
            <Link
              href={`/realty/groups/${encodeURIComponent(publicId)}/reviews?page=${pageIndex - 1}`}
              className="hover:underline"
              rel="prev"
            >
              Previous
            </Link>
          ) : (
            <span className="text-fg-muted">Previous</span>
          )}
          <span className="text-fg-muted">
            Page {pageIndex + 1} of {reviews.totalPages}
          </span>
          {pageIndex + 1 < reviews.totalPages ? (
            <Link
              href={`/realty/groups/${encodeURIComponent(publicId)}/reviews?page=${pageIndex + 1}`}
              className="hover:underline"
              rel="next"
            >
              Next
            </Link>
          ) : (
            <span className="text-fg-muted">Next</span>
          )}
        </nav>
      )}
    </main>
  );
}

async function fetchGroupOrNull(publicId: string) {
  try {
    return await realtyGroupsApi.getGroup(publicId);
  } catch (err) {
    if (isApiError(err) && (err.status === 404 || err.status === 410)) {
      return null;
    }
    throw err;
  }
}

async function fetchReviewsOrEmpty(publicId: string, page: number) {
  try {
    return await fetchGroupReviews(publicId, page);
  } catch (err) {
    if (isApiError(err) && err.status === 404) {
      return {
        content: [],
        totalElements: 0,
        totalPages: 0,
        number: page,
        size: 20,
      };
    }
    throw err;
  }
}

/**
 * Render five "stars" as text glyphs so the page works without the lucide
 * icon mounting layer. The {@link GroupRatingBadge} above already renders
 * accessible SVG stars for the aggregate; per-row stars only need to
 * convey magnitude.
 */
function renderStars(rating: number): string {
  const clamped = Math.max(0, Math.min(5, Math.round(rating)));
  return "★".repeat(clamped) + "☆".repeat(5 - clamped);
}
