import Link from "next/link";
import { ArrowRight } from "@/components/ui/icons";
import { Avatar } from "@/components/ui/Avatar";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { NewSellerBadge } from "@/components/user/NewSellerBadge";
import { ReputationStars } from "@/components/user/ReputationStars";
import { cn } from "@/lib/cn";

/**
 * Seller summary card for the auction detail page.
 *
 * Consumes the enriched {@code seller} block returned on the extended
 * {@code GET /api/v1/auctions/{id}} response (Epic 07 sub-spec 1 Task 2
 * added {@code PublicAuctionResponse.SellerSummary} server-side):
 *
 *   - {@code averageRating} + {@code reviewCount} feed {@link ReputationStars}
 *   - {@code completedSales} is the primary trust signal — "N completed
 *     sale{s}"; the {@link NewSellerBadge} surfaces when this is {@code
 *     < 3}.
 *   - {@code completionRate} is the server-computed ratio of
 *     completed / (completed + cancelledWithBids); rendered as a rounded
 *     percentage (e.g. 92%). Null ({@code cancelledWithBids == 0 &&
 *     completedSales == 0}) renders "Too new to calculate" and ALSO
 *     triggers the New Seller badge.
 *   - {@code memberSince} renders as "Member since {Mon YYYY}" (locale-
 *     aware abbreviated month).
 *
 * Whole card wraps in a {@code <Link href="/users/{id}">} so clicking
 * anywhere on it lands on the full public profile.
 */
export interface SellerProfileCardSeller {
  publicId: string;
  displayName: string;
  avatarUrl?: string | null;
  averageRating?: number | string | null;
  reviewCount?: number | null;
  completedSales: number;
  completionRate?: number | string | null;
  memberSince?: string | null;
}

interface Props {
  seller: SellerProfileCardSeller;
  className?: string;
}

/**
 * "Member since Mon YYYY". Returns null when the input is null/empty so
 * the caller can skip the paragraph entirely instead of rendering a
 * placeholder. Keeps the formatter UTC-stable so SSR and client outputs
 * match — otherwise the hydration step would warn on builds that render
 * in different timezones.
 */
function formatMemberSince(iso: string | null | undefined): string | null {
  if (!iso) return null;
  // The backend emits a LocalDate ("2025-11-03") — Date.parse accepts it
  // as UTC, which is exactly what we want for the Mon/YYYY label.
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  const month = d.toLocaleString("en-US", { month: "short", timeZone: "UTC" });
  const year = d.getUTCFullYear();
  return `Member since ${month} ${year}`;
}

function formatCompletionRate(rate: number | string | null | undefined): string {
  if (rate == null) return "Too new to calculate";
  const n = typeof rate === "string" ? Number(rate) : rate;
  if (!Number.isFinite(n)) return "Too new to calculate";
  return `${Math.round(n * 100)}%`;
}

function normaliseRating(value: number | string | null | undefined): number | null {
  if (value == null) return null;
  const n = typeof value === "string" ? Number(value) : value;
  if (!Number.isFinite(n)) return null;
  return n;
}

export function SellerProfileCard({ seller, className }: Props) {
  const completedSales = seller.completedSales;
  const reviewCount = seller.reviewCount ?? 0;
  const rating = normaliseRating(seller.averageRating);
  const memberSinceLabel = formatMemberSince(seller.memberSince);
  const completionLabel = formatCompletionRate(seller.completionRate);

  return (
    <Link
      href={`/users/${seller.publicId}`}
      className={cn(
        "block rounded-lg bg-surface-raised p-6 transition-colors hover:bg-bg-subtle focus:outline-none focus-visible:ring-2 focus-visible:ring-brand",
        className,
      )}
      data-testid="seller-profile-card"
      aria-label={`View ${seller.displayName}'s profile`}
    >
      <section aria-label="Seller" className="flex flex-col gap-4">
        <header className="flex items-start gap-4">
          <Avatar
            src={seller.avatarUrl ?? undefined}
            alt={seller.displayName}
            name={seller.displayName}
            size="lg"
          />
          <div className="flex min-w-0 flex-col gap-0.5">
            <h2 className="text-base font-bold tracking-tight text-fg truncate">
              {seller.displayName}
            </h2>
            {memberSinceLabel && (
              <p
                className="text-[11px] font-medium text-fg-muted"
                data-testid="seller-profile-card-member-since"
              >
                {memberSinceLabel}
              </p>
            )}
          </div>
        </header>

        <div className="flex flex-col gap-2">
          <ReputationStars rating={rating} reviewCount={reviewCount} />
          <p className="text-xs text-fg-muted">
            {completedSales} completed sale{completedSales === 1 ? "" : "s"}
          </p>
          <p
            className="text-xs text-fg-muted"
            data-testid="seller-profile-card-completion-rate"
          >
            Completion rate: {completionLabel}
          </p>
          {/* NewSellerBadge self-hides when completedSales ≥ 3. That covers
              the "not enough sales" case. For the "no cancellation data"
              case (completionRate === null with completedSales ≥ 3) we
              emit the same visual badge directly so the card stays
              consistent — the badge itself is a pure status chip with no
              internal state. */}
          {completedSales < 3 ? (
            <NewSellerBadge completedSales={completedSales} />
          ) : seller.completionRate == null ? (
            <StatusBadge tone="warning">New Seller</StatusBadge>
          ) : null}
        </div>

        <span
          className="inline-flex items-center gap-1 text-brand text-sm font-medium self-start"
          data-testid="seller-profile-card-link"
        >
          View profile
          <ArrowRight className="size-4" aria-hidden="true" />
        </span>
      </section>
    </Link>
  );
}
