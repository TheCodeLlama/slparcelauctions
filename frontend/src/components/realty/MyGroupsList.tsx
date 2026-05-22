"use client";

import Link from "next/link";
import { Button } from "@/components/ui/Button";
import { EmptyState } from "@/components/ui/EmptyState";
import { Users2 } from "@/components/ui/icons";
import { ThemedImage } from "@/components/ui/ThemedImage";
import { useThemedImage } from "@/lib/theme/useThemedImage";
import type { RealtyGroupSummaryDto } from "@/types/realty";

export interface MyGroupsListProps {
  /** Groups the caller belongs to (leader or agent). */
  groups: RealtyGroupSummaryDto[];
  /**
   * publicId of the caller. Reserved for callers that want to derive role
   * badges from the summary list once the DTO carries that data. Unused
   * today but kept on the API so callers can pre-thread it without a
   * follow-up prop addition.
   */
  callerUserPublicId?: string | null;
}

/**
 * Renders the caller's realty-group memberships as a vertical list of rows
 * linking to `/groups/{slug}/manage/profile`. Extracted from the legacy
 * `/dashboard/groups` page so the new `/groups/me` route is a thin wrapper.
 *
 * Empty state is owned by the consumer — callers may want different copy
 * depending on context (a full-page route vs. a dashboard sub-section).
 * This component renders its own empty state for the full-page route case;
 * the dashboard "Your groups" section renders `null` when empty (see Task 28).
 */
export function MyGroupsList({ groups }: MyGroupsListProps) {
  if (groups.length === 0) {
    return (
      <EmptyState
        icon={Users2}
        headline="You're not part of any realty groups yet"
        description="Create one to organize your listings or wait for a leader to invite you."
      >
        <Link href="/groups/new">
          <Button variant="primary" data-testid="groups-empty-create-cta">
            Create group
          </Button>
        </Link>
      </EmptyState>
    );
  }
  return (
    <ul className="flex flex-col gap-2" data-testid="my-groups-list">
      {groups.map((g) => (
        <MyGroupRow key={g.publicId} group={g} />
      ))}
    </ul>
  );
}

function MyGroupRow({ group }: { group: RealtyGroupSummaryDto }) {
  const hasLogo =
    useThemedImage(group.logoLightUrl, group.logoDarkUrl) !== null;
  return (
    <li>
      <Link
        href={`/groups/${encodeURIComponent(group.slug)}/manage/profile`}
        className="flex items-center gap-3 rounded-lg border border-border bg-surface-raised px-3 py-2.5 transition-colors hover:bg-bg-hover"
        data-testid={`my-group-row-${group.publicId}`}
      >
        {hasLogo ? (
          <ThemedImage
            lightSrc={group.logoLightUrl}
            darkSrc={group.logoDarkUrl}
            alt=""
            className="h-10 w-10 rounded object-cover"
            aria-hidden="true"
          />
        ) : (
          <div className="h-10 w-10 rounded bg-info-bg" aria-hidden="true" />
        )}
        <div className="flex flex-col gap-0.5 min-w-0 flex-1">
          <span className="text-sm font-semibold text-fg truncate">
            {group.name}
          </span>
          <span className="text-xs text-fg-muted">
            {group.memberCount} member{group.memberCount === 1 ? "" : "s"}
          </span>
        </div>
      </Link>
    </li>
  );
}
