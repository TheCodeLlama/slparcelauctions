/* eslint-disable @next/next/no-img-element -- backend-served binary images; next/image would need remotePatterns config across both prod + dev backends */
"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Heart, ShieldCheck, Star, Tag, User } from "lucide-react";
import type {
  AgentCardDto,
  RealtyGroupPublicDto,
} from "@/types/realty";
import { apiUrl } from "@/lib/api/url";
import { useCurrentUser } from "@/lib/user";
import { cn } from "@/lib/cn";
import { RealtyGroupHeroBanner } from "@/components/realty/RealtyGroupHeroBanner";
import {
  fetchGroupReviews,
  type GroupReviewRow,
} from "@/lib/api/realtyGroupReviews";
import { Avatar } from "./components/Avatar";
import { Badge } from "./components/Badge";
import { Btn } from "./components/Btn";
import { DetailRow } from "./components/DetailRow";
import { RatingSummary } from "@/components/reviews/RatingSummary";

interface PublicGroupProfileProps {
  group: RealtyGroupPublicDto;
}

type Tab = "listings" | "members" | "reviews" | "about";

const TAGLINE_MAX_CHARS = 120;

/**
 * Public group profile, rendered at {@code /groups/[slug]}. Renders the
 * existing {@link RealtyGroupHeroBanner} at the top (cover image + overlapping
 * logo + group name + member-count chip + description + member-since +
 * website link) and the claude.ai/design template's chrome below it
 * (Verified-SL-group + rating chip strip, 4-stat grid, tabbed sections for
 * Active listings / Members / Reviews / About).
 *
 * <p>The cover-and-hero block is intentionally NOT the template's
 * {@code GroupDetailPage} hero — the user kept the pre-existing
 * {@code RealtyGroupHeroBanner} surface unchanged so the rest of the site's
 * group surfaces stay visually consistent. Only the area below the hero
 * matches the template.
 *
 * <p>The template's "Contact group" primary button becomes a
 * <strong>"Manage group"</strong> link to {@code /groups/[slug]/manage/profile}
 * when the viewer is the group leader or an agent. Non-member viewers see
 * the "Contact group" button (currently a placeholder action consistent
 * with the template).
 */
export function PublicGroupProfile({ group }: PublicGroupProfileProps) {
  const me = useCurrentUser();
  const [tab, setTab] = useState<Tab>("listings");

  const isMember = useMemo(() => {
    if (!me.data) return false;
    if (group.leader.userPublicId === me.data.publicId) return true;
    return group.agents.some(
      (a) => a.userPublicId === me.data!.publicId,
    );
  }, [group, me.data]);

  const tagline = useMemo(() => taglineFor(group.description), [group.description]);
  const memberSince = formatFoundedAt(group.memberSince);
  const reviewCount = group.rating?.reviewCount ?? 0;
  const averageRating = group.rating?.averageRating ?? null;
  const activeListingsCount = group.activeListingsCount ?? 0;
  const completedSalesCount = group.completedSalesCount ?? 0;
  const hasVerifiedSlGroup = group.hasVerifiedSlGroup ?? false;

  const tabs: Array<[Tab, string]> = [
    ["listings", `Active listings · ${activeListingsCount}`],
    ["members", `Members · ${group.memberCount}`],
    ["reviews", `Reviews · ${reviewCount}`],
    ["about", "About"],
  ];

  return (
    <div>
      <div className="mx-auto w-full max-w-5xl px-4 sm:px-6">
        <RealtyGroupHeroBanner
          name={group.name}
          slug={group.slug}
          description={group.description}
          website={group.website}
          memberSince={group.memberSince}
          memberCount={group.memberCount}
          coverUrl={group.coverUrl}
          logoUrl={group.logoUrl}
        />
      </div>

      <div className="mx-auto w-full max-w-5xl px-4 sm:px-6 mt-6">
        <div className="flex flex-wrap items-center justify-between gap-3 mb-5">
          <div className="flex items-center gap-3 flex-wrap">
            {hasVerifiedSlGroup && (
              <Badge tone="success" dot>
                <ShieldCheck className="w-2.5 h-2.5" /> Verified SL group
              </Badge>
            )}
            {reviewCount > 0 && averageRating !== null ? (
              <span className="inline-flex items-center gap-1.5 text-sm text-fg-muted">
                <Star className="w-3.5 h-3.5 text-brand fill-current" />
                <span className="font-semibold text-fg">
                  {averageRating.toFixed(1)}
                </span>
                <span>&middot; {reviewCount} reviews</span>
              </span>
            ) : (
              <span className="text-sm text-fg-muted">No reviews yet</span>
            )}
          </div>
          <div className="flex gap-2">
            <Btn variant="secondary">
              <Heart className="w-3.5 h-3.5" /> Follow
            </Btn>
            {isMember ? (
              <Link
                href={`/groups/${group.slug}/manage/profile`}
                className="inline-flex items-center justify-center gap-1.5 rounded-md border bg-brand text-on-brand border-brand hover:opacity-90 font-semibold px-3.5 py-2 text-sm whitespace-nowrap leading-tight transition-colors active:translate-y-px"
                data-testid="manage-group-btn"
              >
                <User className="w-3.5 h-3.5" /> Manage group
              </Link>
            ) : (
              <Btn variant="primary" data-testid="contact-group-btn">
                <User className="w-3.5 h-3.5" /> Contact group
              </Btn>
            )}
          </div>
        </div>

        <div className="grid grid-cols-2 md:grid-cols-4 mb-7 rounded-lg border border-border bg-surface-raised overflow-hidden">
          <StatCell
            label="Active listings"
            value={activeListingsCount}
            hint="Live or scheduled now"
            border
          />
          <StatCell
            label="Lifetime sales"
            value={completedSalesCount}
            hint="Closed via escrow"
            border
          />
          <StatCell
            label="Members"
            value={`${group.memberCount} / ${group.memberSeatLimit}`}
            hint="Agents + leader"
            border
          />
          <StatCell
            label="Rating"
            value={
              reviewCount > 0 && averageRating !== null
                ? averageRating.toFixed(2)
                : "—"
            }
            hint={`${reviewCount} reviews`}
          />
        </div>

        <div
          className="border-b border-border flex gap-1 mb-6 flex-wrap"
          role="tablist"
          aria-label="Group sections"
        >
          {tabs.map(([k, l]) => (
            <button
              key={k}
              type="button"
              role="tab"
              aria-selected={tab === k}
              onClick={() => setTab(k)}
              className={cn(
                "px-3.5 py-2.5 text-sm font-medium cursor-pointer bg-transparent border-none border-b-2 -mb-px",
                tab === k
                  ? "border-brand text-fg"
                  : "border-transparent text-fg-muted hover:text-fg",
              )}
            >
              {l}
            </button>
          ))}
        </div>

        {tab === "listings" && (
          <ListingsTab activeCount={activeListingsCount} />
        )}

        {tab === "members" && (
          <MembersTab
            group={group}
            agents={group.agents.filter(
              (a) => a.userPublicId !== group.leader.userPublicId,
            )}
          />
        )}

        {tab === "reviews" && (
          <ReviewsTab
            groupPublicId={group.publicId}
            reviewCount={reviewCount}
          />
        )}

        {tab === "about" && <AboutTab group={group} tagline={tagline} hasVerifiedSlGroup={hasVerifiedSlGroup} memberSince={memberSince} />}
      </div>
    </div>
  );
}

function StatCell({
  label,
  value,
  hint,
  border,
}: {
  label: string;
  value: React.ReactNode;
  hint: string;
  border?: boolean;
}) {
  return (
    <div className={cn("p-4", border && "border-r border-border")}>
      <div className="text-[11px] text-fg-subtle uppercase tracking-[0.06em] font-semibold">
        {label}
      </div>
      <div className="text-2xl font-bold tracking-tight mt-1">{value}</div>
      <div className="text-[11.5px] text-fg-subtle mt-0.5">{hint}</div>
    </div>
  );
}

function ListingsTab({ activeCount }: { activeCount: number }) {
  if (activeCount === 0) {
    return (
      <div className="rounded-lg border border-border bg-surface-raised p-12 text-center mb-8">
        <Tag className="w-7 h-7 text-fg-subtle mx-auto mb-2" />
        <div className="font-semibold mb-1">No active listings right now</div>
        <div className="text-sm text-fg-muted">
          Follow the group to be notified when they list a new parcel.
        </div>
      </div>
    );
  }
  return (
    <div className="rounded-lg border border-border bg-surface-raised p-12 text-center mb-8">
      <div className="text-fg-muted">
        {activeCount} active listing{activeCount === 1 ? "" : "s"}. Browse them on the
        main listings page.
      </div>
    </div>
  );
}

function MembersTab({
  group,
  agents,
}: {
  group: RealtyGroupPublicDto;
  agents: AgentCardDto[];
}) {
  return (
    <div className="mb-8">
      <div className="text-[11px] font-bold tracking-[0.06em] uppercase text-fg-subtle mb-2.5">
        Group leader
      </div>
      <PublicMemberRow
        displayName={group.leader.displayName ?? "Leader"}
        avatarUrl={group.leader.avatarUrl}
        role="Leader"
      />
      {agents.length > 0 && (
        <>
          <div className="text-[11px] font-bold tracking-[0.06em] uppercase text-fg-subtle mt-5 mb-2.5">
            Agents
          </div>
          <div className="flex flex-col gap-2">
            {agents.map((a) => (
              <PublicMemberRow
                key={a.memberPublicId}
                displayName={a.displayName ?? "Agent"}
                avatarUrl={a.avatarUrl}
                role="Agent"
              />
            ))}
          </div>
        </>
      )}
      <div className="mt-4 text-xs text-fg-subtle">
        {group.memberCount} of {group.memberSeatLimit} seats filled.
      </div>
    </div>
  );
}

function PublicMemberRow({
  displayName,
  avatarUrl,
  role,
}: {
  displayName: string;
  avatarUrl: string | null;
  role: string;
}) {
  const resolvedAvatar = apiUrl(avatarUrl);
  return (
    <div className="rounded-lg border border-border bg-surface-raised p-3.5 flex items-center gap-3.5">
      {resolvedAvatar ? (
        <img
          src={resolvedAvatar}
          alt=""
          className="w-11 h-11 rounded-full object-cover shrink-0"
          aria-hidden="true"
        />
      ) : (
        <Avatar name={displayName} size="lg" />
      )}
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <span className="text-sm font-semibold">{displayName}</span>
          <Badge tone="neutral">{role}</Badge>
        </div>
      </div>
    </div>
  );
}

function ReviewsTab({
  groupPublicId,
  reviewCount,
}: {
  groupPublicId: string;
  reviewCount: number;
}) {
  const reviews = useQuery({
    queryKey: ["realty-groups", "reviews", groupPublicId, "first-page"] as const,
    queryFn: () => fetchGroupReviews(groupPublicId, 0, 20),
    enabled: reviewCount > 0,
    staleTime: 60_000,
  });

  if (reviewCount === 0) {
    return (
      <div className="rounded-lg border border-border bg-surface-raised p-12 text-center mb-8">
        <Star className="w-7 h-7 text-fg-subtle mx-auto mb-2" />
        <div className="font-semibold">No reviews yet</div>
        <div className="text-sm text-fg-muted mt-1">
          Be the first to leave a review after a successful transaction.
        </div>
      </div>
    );
  }
  if (reviews.isPending) {
    return (
      <div className="rounded-lg border border-border bg-surface-raised p-12 text-center mb-8 text-fg-muted text-sm">
        Loading reviews...
      </div>
    );
  }
  if (reviews.isError || !reviews.data) {
    return (
      <div
        role="alert"
        className="rounded-lg border border-border bg-surface-raised p-6 text-sm text-fg-muted mb-8"
      >
        Couldn&rsquo;t load reviews. Try refreshing.
      </div>
    );
  }
  const rows = reviews.data.content;
  return (
    <div className="flex flex-col gap-3 mb-8">
      {rows.map((r: GroupReviewRow) => (
        <div
          key={`${r.reviewerPublicId}-${r.auctionPublicId}`}
          className="rounded-lg border border-border bg-surface-raised p-4"
        >
          <div className="flex items-center gap-2.5 mb-2">
            <Avatar name={r.reviewerDisplayName} size="md" />
            <div className="flex-1">
              <div className="text-sm font-semibold">{r.reviewerDisplayName}</div>
              <div className="flex items-center gap-1.5">
                <RatingSummary
                  rating={r.rating > 0 ? r.rating : null}
                  reviewCount={r.rating > 0 ? 1 : 0}
                  size="xs"
                  hideNumber
                  hideCountText
                />
                <span className="text-xs text-fg-subtle">
                  &middot; {formatReviewDate(r.createdAt)}
                </span>
              </div>
            </div>
          </div>
          {r.comment && (
            <div className="text-sm text-fg-muted leading-relaxed">
              {r.comment}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

function AboutTab({
  group,
  tagline,
  hasVerifiedSlGroup,
  memberSince,
}: {
  group: RealtyGroupPublicDto;
  tagline: string;
  hasVerifiedSlGroup: boolean;
  memberSince: string;
}) {
  return (
    <div className="mb-8 grid grid-cols-1 lg:[grid-template-columns:1fr_320px] gap-6 items-start">
      <div>
        <h3 className="text-[15px] font-semibold mt-0">About {group.name}</h3>
        <p className="text-sm text-fg-muted leading-relaxed whitespace-pre-line">
          {group.description ?? tagline}
        </p>
        {group.website && (
          <>
            <h3 className="text-[15px] font-semibold mt-6">Website</h3>
            <a
              href={group.website}
              target="_blank"
              rel="noopener nofollow ugc"
              className="text-sm text-brand hover:underline break-all"
            >
              {group.website}
            </a>
          </>
        )}
      </div>
      <div className="rounded-lg border border-border bg-surface-raised p-4">
        <div className="text-[11px] font-semibold text-fg-subtle uppercase tracking-[0.05em] mb-2.5">
          Group details
        </div>
        <DetailRow label="Founded" value={memberSince} />
        <DetailRow
          label="SL group"
          value={hasVerifiedSlGroup ? "Verified" : "Not yet linked"}
        />
        <DetailRow
          label="Members"
          value={`${group.memberCount} of ${group.memberSeatLimit}`}
        />
        <DetailRow
          label="Slug"
          value={<span className="font-mono text-[11px]">{group.slug}</span>}
        />
      </div>
    </div>
  );
}

function taglineFor(description: string | null): string {
  if (!description) return "";
  if (description.length <= TAGLINE_MAX_CHARS) return description;
  return description.substring(0, TAGLINE_MAX_CHARS) + "...";
}

function formatFoundedAt(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "recently";
  return date.toLocaleString("en-US", { month: "long", year: "numeric" });
}

function formatReviewDate(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleString("en-US", { month: "short", day: "numeric", year: "numeric" });
}
