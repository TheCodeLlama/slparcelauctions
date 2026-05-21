"use client";

import { Star } from "lucide-react";
import type { GroupCardLayout, RealtyGroupCard } from "@/types/realty";
import { cn } from "@/lib/cn";
import { formatFounded } from "../lib/format";
import { GroupLogo } from "./GroupLogo";
import { GroupCover } from "./GroupCover";

interface GroupCardProps {
  group: RealtyGroupCard;
  layout?: GroupCardLayout;
  onClick?: () => void;
}

export function GroupCard({ group, layout = "standard", onClick }: GroupCardProps) {
  const r = group.rating;

  if (layout === "compact") {
    return (
      <button
        type="button"
        onClick={onClick}
        className="block text-left w-full rounded-lg border border-border bg-surface-raised p-3.5 hover:border-border-strong hover:-translate-y-px hover:shadow-md transition"
      >
        <div className="flex gap-3 items-start">
          <GroupLogo name={group.name} logoUrl={group.logoLightUrl ?? group.logoDarkUrl} size="sm" />
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-1.5 mb-0.5">
              <span className="text-sm font-semibold truncate">{group.name}</span>
            </div>
            <div className="text-xs text-fg-muted mb-1">
              {r.reviewCount > 0 && r.averageRating !== null ? (
                <span className="inline-flex items-center gap-1">
                  <Star className="w-2.5 h-2.5 text-brand fill-current" />
                  {r.averageRating.toFixed(1)} · {r.reviewCount} reviews
                </span>
              ) : (
                <span className="text-fg-subtle">No reviews yet</span>
              )}
            </div>
            <div className="flex gap-2 text-[11.5px] text-fg-subtle">
              <span>{group.activeListingsCount} active</span>
              <span>·</span>
              <span>{group.completedSalesCount} sales</span>
            </div>
          </div>
        </div>
      </button>
    );
  }

  if (layout === "cover") {
    return (
      <button
        type="button"
        onClick={onClick}
        className="block text-left w-full rounded-lg border border-border bg-surface-raised overflow-hidden hover:border-border-strong hover:-translate-y-px hover:shadow-md transition"
      >
        <GroupCover coverUrl={group.coverLightUrl ?? group.coverDarkUrl} />
        <div className="px-4 pb-4 relative">
          <div className="-mt-7 mb-2.5">
            <GroupLogo name={group.name} logoUrl={group.logoLightUrl ?? group.logoDarkUrl} size="md" />
          </div>
          <div className="text-base font-bold tracking-tight mb-1">{group.name}</div>
          <div className="flex items-center gap-1.5 text-xs mb-2">
            {r.reviewCount > 0 && r.averageRating !== null ? (
              <>
                <Star className="w-3 h-3 text-brand fill-current" />
                <span className="font-semibold">{r.averageRating.toFixed(1)}</span>
                <span className="text-fg-subtle">· {r.reviewCount} reviews</span>
              </>
            ) : (
              <span className="text-fg-subtle text-xs">No reviews yet</span>
            )}
          </div>
          <p className="text-xs text-fg-muted leading-relaxed mb-3 line-clamp-2">
            {group.tagline}
          </p>
          <div className="flex items-center justify-between pt-2.5 border-t border-border text-[11.5px]">
            <span
              className={cn(
                group.activeListingsCount > 0
                  ? "text-fg font-semibold"
                  : "text-fg-subtle",
              )}
            >
              {group.activeListingsCount} active
            </span>
            <span className="text-fg-subtle">{group.completedSalesCount} sales</span>
            <span className="text-fg-subtle">
              Since {formatFounded(group.foundedAt).split(" ")[1]}
            </span>
          </div>
        </div>
      </button>
    );
  }

  // standard
  const dim = group.activeListingsCount === 0;
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "block text-left w-full rounded-lg border border-border bg-surface-raised p-4 hover:border-border-strong hover:-translate-y-px hover:shadow-md transition",
        dim && "opacity-80",
      )}
    >
      <div className="flex gap-3.5 items-start mb-3">
        <GroupLogo name={group.name} logoUrl={group.logoLightUrl ?? group.logoDarkUrl} size="sm" />
        <div className="flex-1 min-w-0">
          <h3 className="text-[15px] font-bold tracking-tight m-0 truncate">
            {group.name}
          </h3>
          <div className="text-xs text-fg-muted flex items-center gap-1 mt-1">
            {r.reviewCount > 0 && r.averageRating !== null ? (
              <>
                <Star className="w-2.5 h-2.5 text-brand fill-current" />
                <span className="font-semibold text-fg tabular-nums">
                  {r.averageRating.toFixed(1)}
                </span>
                <span>· {r.reviewCount} reviews</span>
              </>
            ) : (
              <span className="text-fg-subtle">No reviews yet</span>
            )}
          </div>
        </div>
      </div>
      <p className="text-xs text-fg-muted leading-relaxed mb-3.5 line-clamp-2 min-h-[39px]">
        {group.tagline}
      </p>
      <div className="grid grid-cols-3 gap-2 pt-3 border-t border-border text-[11.5px]">
        <CardStat label="Active" value={group.activeListingsCount} dim={dim} />
        <CardStat label="Sales" value={group.completedSalesCount} />
        <CardStat
          label="Members"
          value={
            <>
              {group.memberCount}
              <span className="text-fg-subtle font-normal">
                /{group.memberSeatLimit}
              </span>
            </>
          }
        />
      </div>
      <div className="mt-2.5 text-[11px] text-fg-subtle">
        Active since {formatFounded(group.foundedAt)}
      </div>
    </button>
  );
}

function CardStat({
  label,
  value,
  dim,
}: {
  label: string;
  value: React.ReactNode;
  dim?: boolean;
}) {
  return (
    <div>
      <div className="text-fg-subtle uppercase tracking-[0.04em] font-semibold text-[10px]">
        {label}
      </div>
      <div
        className={cn(
          "font-bold tabular-nums text-[13px] mt-0.5",
          dim ? "text-fg-subtle" : "text-fg",
        )}
      >
        {value}
      </div>
    </div>
  );
}
