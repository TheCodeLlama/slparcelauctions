// export/realty-groups/pages/GroupDetailPage.tsx
"use client";

import { useState } from "react";
import { Heart, ShieldCheck, Star, Tag, User } from "lucide-react";
import type {
  GroupMember,
  GroupReview,
  RealtyGroupCard,
} from "../types";
import { cn, formatFounded } from "../lib/cn";
import { Avatar } from "../components/Avatar";
import { Badge } from "../components/Badge";
import { Btn } from "../components/Btn";
import { DetailRow } from "../components/DetailRow";
import { GroupCover } from "../components/GroupCover";
import { GroupLogo } from "../components/GroupLogo";
import { MemberRow } from "../components/MemberRow";
import { StarRating } from "../components/StarRating";

interface GroupDetailPageProps {
  group: RealtyGroupCard;
  leader: GroupMember;
  agents: GroupMember[];
  reviews: GroupReview[];
  onFollow?: () => void;
  onContact?: () => void;
}

type Tab = "listings" | "members" | "reviews" | "about";

export function GroupDetailPage({
  group,
  leader,
  agents,
  reviews,
  onFollow,
  onContact,
}: GroupDetailPageProps) {
  const [tab, setTab] = useState<Tab>("listings");

  const tabs: Array<[Tab, string]> = [
    ["listings", `Active listings \u00b7 ${group.activeListingsCount}`],
    ["members", `Members \u00b7 ${group.memberCount}`],
    ["reviews", `Reviews \u00b7 ${group.rating.reviewCount}`],
    ["about", "About"],
  ];

  return (
    <div>
      <GroupCover coverUrl={group.coverUrl} size="hero" />

      <div className="w-full max-w-[1280px] mx-auto px-6">
        <div className="-mt-[60px] flex gap-5 items-end mb-6">
          <div className="rounded-lg overflow-hidden shadow-xl shrink-0">
            <GroupLogo
              name={group.name}
              logoUrl={group.logoUrl}
              size="xl"
              square
            />
          </div>
          <div className="flex-1 pb-1.5 min-w-0">
            <div className="flex items-center gap-2.5 mb-1.5 flex-wrap">
              <h1 className="text-[28px] font-bold tracking-tight m-0 text-fg">
                {group.name}
              </h1>
              {group.hasVerifiedSlGroup && (
                <Badge tone="success" dot>
                  <ShieldCheck className="w-2.5 h-2.5" /> Verified SL group
                </Badge>
              )}
            </div>
            <div className="flex gap-3.5 items-center text-sm text-fg-muted flex-wrap">
              {group.rating.reviewCount > 0 && group.rating.averageRating !== null ? (
                <span className="inline-flex items-center gap-1.5">
                  <Star className="w-3.5 h-3.5 text-brand fill-current" />
                  <span className="font-semibold text-fg">
                    {group.rating.averageRating.toFixed(1)}
                  </span>
                  <span>&middot; {group.rating.reviewCount} reviews</span>
                </span>
              ) : (
                <span>No reviews yet</span>
              )}
              <span>&middot;</span>
              <span>Active since {formatFounded(group.foundedAt)}</span>
              <span>&middot;</span>
              <span>
                {group.memberCount} of {group.memberSeatLimit} members
              </span>
            </div>
          </div>
          <div className="flex gap-2 pb-1.5">
            <Btn variant="secondary" onClick={onFollow}>
              <Heart className="w-3.5 h-3.5" /> Follow
            </Btn>
            <Btn variant="primary" onClick={onContact}>
              <User className="w-3.5 h-3.5" /> Contact group
            </Btn>
          </div>
        </div>

        <p className="text-base text-fg-muted max-w-[720px] leading-relaxed mt-0 mb-6">
          {group.tagline}
        </p>

        <div className="grid grid-cols-4 mb-7 rounded-lg border border-border bg-surface-raised overflow-hidden">
          <StatCell
            label="Active listings"
            value={group.activeListingsCount}
            hint="Live or scheduled now"
            border
          />
          <StatCell
            label="Lifetime sales"
            value={group.completedSalesCount}
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
              group.rating.reviewCount > 0 && group.rating.averageRating !== null
                ? `${group.rating.averageRating.toFixed(2)}`
                : "\u2014"
            }
            hint={`${group.rating.reviewCount} reviews`}
          />
        </div>

        <div className="border-b border-border flex gap-1 mb-6">
          {tabs.map(([k, l]) => (
            <button
              key={k}
              type="button"
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
          <ListingsTab activeCount={group.activeListingsCount} />
        )}

        {tab === "members" && (
          <MembersTab
            leader={leader}
            agents={agents}
            memberCount={group.memberCount}
            memberSeatLimit={group.memberSeatLimit}
          />
        )}

        {tab === "reviews" && <ReviewsTab reviews={reviews} />}

        {tab === "about" && <AboutTab group={group} />}
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
        Render this group&rsquo;s {activeCount} active listings here using your
        existing auction card component.
      </div>
    </div>
  );
}

function MembersTab({
  leader,
  agents,
  memberCount,
  memberSeatLimit,
}: {
  leader: GroupMember;
  agents: GroupMember[];
  memberCount: number;
  memberSeatLimit: number;
}) {
  return (
    <div className="mb-8">
      <div className="text-[11px] font-bold tracking-[0.06em] uppercase text-fg-subtle mb-2.5">
        Group leader
      </div>
      <MemberRow member={leader} role="Leader" />
      <div className="text-[11px] font-bold tracking-[0.06em] uppercase text-fg-subtle mt-5 mb-2.5">
        Agents
      </div>
      <div className="flex flex-col gap-2">
        {agents.map((a, i) => (
          <MemberRow
            key={a.id}
            member={a}
            role={["Senior agent", "Agent", "Junior agent"][i] ?? "Agent"}
          />
        ))}
      </div>
      <div className="mt-4 text-xs text-fg-subtle">
        {memberCount} of {memberSeatLimit} seats filled.
      </div>
    </div>
  );
}

function ReviewsTab({ reviews }: { reviews: GroupReview[] }) {
  if (reviews.length === 0) {
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
  return (
    <div className="flex flex-col gap-3 mb-8">
      {reviews.map((r) => (
        <div
          key={r.id}
          className="rounded-lg border border-border bg-surface-raised p-4"
        >
          <div className="flex items-center gap-2.5 mb-2">
            <Avatar name={r.author} size="md" />
            <div className="flex-1">
              <div className="text-sm font-semibold">{r.author}</div>
              <div className="flex items-center gap-1.5">
                <StarRating value={r.stars} size={11} showNumber={false} />
                <span className="text-xs text-fg-subtle">&middot; {r.when}</span>
              </div>
            </div>
          </div>
          <div className="text-sm text-fg-muted leading-relaxed">{r.text}</div>
        </div>
      ))}
    </div>
  );
}

function AboutTab({ group }: { group: RealtyGroupCard }) {
  return (
    <div className="mb-8 grid grid-cols-1 lg:[grid-template-columns:1fr_320px] gap-6 items-start">
      <div>
        <h3 className="text-[15px] font-semibold mt-0">About {group.name}</h3>
        <p className="text-sm text-fg-muted leading-relaxed">
          {group.tagline} Operating across multiple regions, the group focuses on
          consistency, transparent pricing, and verified ownership. Every listing
          is signed off by the group leader before publication.
        </p>
        <h3 className="text-[15px] font-semibold mt-6">Specialties</h3>
        <div className="flex gap-1.5 flex-wrap">
          {["Waterfront", "Forest", "Mainland", "Themed RP"].map((s) => (
            <Badge key={s} tone="neutral">
              {s}
            </Badge>
          ))}
        </div>
      </div>
      <div className="rounded-lg border border-border bg-surface-raised p-4">
        <div className="text-[11px] font-semibold text-fg-subtle uppercase tracking-[0.05em] mb-2.5">
          Group details
        </div>
        <DetailRow label="Founded" value={formatFounded(group.foundedAt)} />
        <DetailRow
          label="SL group"
          value={group.hasVerifiedSlGroup ? "Verified" : "Not yet linked"}
        />
        <DetailRow
          label="Public ID"
          value={
            <span className="font-mono text-[11px]">{group.publicId}</span>
          }
        />
        <DetailRow
          label="Slug"
          value={<span className="font-mono text-[11px]">{group.slug}</span>}
        />
      </div>
    </div>
  );
}
