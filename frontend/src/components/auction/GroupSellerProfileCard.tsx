/* eslint-disable @next/next/no-img-element -- group logo + agent avatar are API-served binary content */
import Link from "next/link";
import { ArrowRight } from "@/components/ui/icons";
import { Avatar } from "@/components/ui/Avatar";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { NewSellerBadge } from "@/components/user/NewSellerBadge";
import { ReputationStars } from "@/components/user/ReputationStars";
import { apiUrl } from "@/lib/api/url";
import { cn } from "@/lib/cn";
import type { GroupAttribution, ListingAgent } from "@/types/auction";

/**
 * Group-flavoured seller card for the auction detail page. Rendered in
 * place of {@link SellerProfileCard} when the listing was created under
 * a non-dissolved realty group. The group is the public "seller"; the
 * agent who placed the listing appears as a subline link.
 *
 * Layout mirrors {@link SellerProfileCard} (logo on the left, stats
 * block, view-profile pill) so the surrounding page composition is
 * unchanged. The outer card is NOT a single anchor — the agent's name
 * is a distinct link inside the card, which means we can't nest it in
 * an outer anchor without producing invalid HTML. Click targets:
 *   - Group logo + group name → /groups/{slug}
 *   - Agent name              → /users/{agentPublicId}
 *   - "View group profile"    → /groups/{slug}
 */
export interface GroupSellerProfileCardProps {
  group: GroupAttribution;
  agent: ListingAgent;
  /** Aggregated group rating; null/undefined renders the "no reviews" state. */
  averageRating?: number | string | null;
  reviewCount?: number | null;
  completedSales: number;
  /**
   * ISO date string for the group's foundedAt (server emits
   * {@code memberSince}). Rendered as "Founded {Mon YYYY}" if present.
   */
  foundedAt?: string | null;
  className?: string;
}

function formatFoundedAt(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  const month = d.toLocaleString("en-US", { month: "short", timeZone: "UTC" });
  const year = d.getUTCFullYear();
  return `Founded ${month} ${year}`;
}

function normaliseRating(value: number | string | null | undefined): number | null {
  if (value == null) return null;
  const n = typeof value === "string" ? Number(value) : value;
  if (!Number.isFinite(n)) return null;
  return n;
}

export function GroupSellerProfileCard({
  group,
  agent,
  averageRating,
  reviewCount,
  completedSales,
  foundedAt,
  className,
}: GroupSellerProfileCardProps) {
  const rating = normaliseRating(averageRating);
  const totalReviews = reviewCount ?? 0;
  const foundedLabel = formatFoundedAt(foundedAt ?? null);
  const groupHref = `/groups/${encodeURIComponent(group.slug)}`;
  const agentHref = `/users/${encodeURIComponent(agent.publicId)}`;
  const resolvedLogo = apiUrl(group.logoUrl ?? null);
  const resolvedAvatar = apiUrl(agent.avatarUrl ?? null);

  return (
    <section
      aria-label="Seller group"
      data-testid="group-seller-profile-card"
      className={cn(
        "rounded-lg bg-surface-raised p-6 flex flex-col gap-4",
        className,
      )}
    >
      <header className="flex items-start gap-4">
        <Link
          href={groupHref}
          aria-label={`View ${group.name}'s profile`}
          className="shrink-0 rounded focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
        >
          {resolvedLogo ? (
            <img
              src={resolvedLogo}
              alt={group.name}
              className="size-12 rounded object-cover"
              loading="lazy"
            />
          ) : (
            <Avatar
              src={undefined}
              alt={group.name}
              name={group.name}
              size="lg"
            />
          )}
        </Link>
        <div className="flex min-w-0 flex-col gap-0.5">
          <h2 className="text-base font-bold tracking-tight text-fg truncate">
            <Link
              href={groupHref}
              className="hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand rounded"
              data-testid="group-seller-profile-card-name"
            >
              {group.name}
            </Link>
          </h2>
          <p
            className="text-[11px] font-medium text-fg-muted flex items-center gap-1.5"
            data-testid="group-seller-profile-card-listed-by"
          >
            <span>Listed by</span>
            {resolvedAvatar && (
              <img
                src={resolvedAvatar}
                alt=""
                aria-hidden="true"
                className="size-4 rounded-full object-cover"
                loading="lazy"
              />
            )}
            <Link
              href={agentHref}
              className="font-semibold text-fg hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand rounded"
              data-testid="group-seller-profile-card-agent-link"
            >
              {agent.displayName}
            </Link>
          </p>
          {foundedLabel && (
            <p
              className="text-[11px] font-medium text-fg-muted"
              data-testid="group-seller-profile-card-founded"
            >
              {foundedLabel}
            </p>
          )}
        </div>
      </header>

      <div className="flex flex-col gap-2">
        <ReputationStars rating={rating} reviewCount={totalReviews} />
        <p className="text-xs text-fg-muted">
          {completedSales} completed sale{completedSales === 1 ? "" : "s"}
        </p>
        {completedSales < 3 ? (
          <NewSellerBadge completedSales={completedSales} />
        ) : totalReviews === 0 ? (
          <StatusBadge tone="warning">New Group</StatusBadge>
        ) : null}
      </div>

      <Link
        href={groupHref}
        className="inline-flex items-center gap-1 text-brand text-sm font-medium self-start rounded focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
        data-testid="group-seller-profile-card-link"
      >
        View group profile
        <ArrowRight className="size-4" aria-hidden="true" />
      </Link>
    </section>
  );
}
