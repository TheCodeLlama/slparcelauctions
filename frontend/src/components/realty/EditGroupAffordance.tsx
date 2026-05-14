"use client";

import Link from "next/link";
import { Settings } from "@/components/ui/icons";
import { useMyRealtyGroups } from "@/hooks/realty/useRealtyGroups";

export interface EditGroupAffordanceProps {
  /**
   * Slug of the group whose hero this affordance overlays. Matched
   * against the caller's affiliation list to decide whether to render.
   */
  slug: string;
}

/**
 * Member-only "manage group" gear icon overlaid on the hero banner.
 *
 * Rationale — the server component that renders `/groups/[slug]` has no
 * JWT, so it cannot know whether the visitor is a member with
 * `EDIT_GROUP_PROFILE` (or the leader). This thin client overlay reads
 * the caller's affiliations from {@link useMyRealtyGroups} and renders
 * the gear when a match is found.
 *
 * Phase 13 scope: render the gear when the caller is a member at all
 * (leader => all-implicit, agents => relies on the destination page to
 * gate per-permission). Sub-project A's `UserRealtyGroupAffiliationDto`
 * carries `role` but not the agent's flag set — fine-grained `EDIT`
 * gating happens at the destination route.
 *
 * The destination is `/groups/[slug]/manage/profile` (the member-only
 * management subtree introduced in the template-1:1 split between the
 * public profile and the management area).
 */
export function EditGroupAffordance({ slug }: EditGroupAffordanceProps) {
  const { data: myGroups, isPending, isError } = useMyRealtyGroups();
  if (isPending || isError) return null;
  if (!myGroups || !myGroups.some((g) => g.slug === slug)) return null;

  return (
    <Link
      href={`/groups/${encodeURIComponent(slug)}/manage/profile`}
      aria-label="Manage group"
      className="inline-flex h-9 w-9 items-center justify-center rounded-full bg-surface-raised/90 text-fg backdrop-blur transition-colors hover:bg-surface-raised focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
      data-testid="edit-group-affordance"
    >
      <Settings className="size-4" aria-hidden="true" />
    </Link>
  );
}
