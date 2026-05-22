/* eslint-disable @next/next/no-img-element -- avatar image is API-served binary content */
"use client";

import Link from "next/link";
import { apiUrl } from "@/lib/api/url";
import { ThemedImage } from "@/components/ui/ThemedImage";
import { useThemedImage } from "@/lib/theme/useThemedImage";
import type { GroupAttribution, ListingAgent } from "@/types/auction";

export interface GroupAttributionLineProps {
  agent: ListingAgent | null | undefined;
  group: GroupAttribution | null | undefined;
}

/**
 * "Sold by <group>" heading + "Listed by <agent> of <group>" subline for
 * the auction detail page. Rendered near the header when the listing was
 * created under a non-dissolved realty group. Returns null when either
 * side is absent or the group is dissolved — individual listings show no
 * attribution, and dissolved groups are retired from public display per
 * spec §6.2.
 *
 * Realty Groups: group sales (agent lists group-owned land under a realty
 * group) surface the group as the primary seller via the "Sold by"
 * heading. Legacy "agent lists own land under a group" rows are not
 * reachable through new listings since sub-project E, so this same
 * heading also covers those legacy rows without further DTO branching.
 * The backend group-attribution DTO does not carry the SL group id
 * today, so {@code group != null} is treated as the group-sale marker.
 */
export function GroupAttributionLine({ agent, group }: GroupAttributionLineProps) {
  // The group logo is theme-aware: ThemedImage picks the variant matching the
  // active theme and falls back to the sibling slot when only one is uploaded.
  // The hook runs unconditionally (before the early return) so hook order stays
  // stable across renders — it tolerates the null group/logo URLs.
  const hasLogo =
    useThemedImage(group?.logoLightUrl, group?.logoDarkUrl) !== null;

  if (!agent || !group || group.dissolved) return null;

  const resolvedAvatar = apiUrl(agent.avatarUrl ?? null);

  return (
    <div
      className="flex flex-col gap-1"
      data-testid="group-attribution-line"
    >
      <h2
        className="text-base font-semibold tracking-tight text-fg flex items-center gap-2"
        data-testid="group-attribution-sold-by"
      >
        <span>Sold by</span>
        {hasLogo && (
          <ThemedImage
            lightSrc={group.logoLightUrl}
            darkSrc={group.logoDarkUrl}
            alt=""
            className="w-5 h-5 rounded object-cover"
            aria-hidden="true"
            loading="lazy"
          />
        )}
        <Link
          href={`/groups/${encodeURIComponent(group.slug)}`}
          className="underline hover:text-fg-muted transition-colors"
        >
          {group.name}
        </Link>
      </h2>
      <p className="flex items-center gap-2 text-sm text-fg-muted">
        <span>Listed by</span>
        {resolvedAvatar && (
          <img
            src={resolvedAvatar}
            alt=""
            className="w-5 h-5 rounded-full object-cover"
            aria-hidden="true"
            loading="lazy"
          />
        )}
        <Link
          href={`/users/${encodeURIComponent(agent.publicId)}`}
          className="font-medium text-fg underline hover:text-fg-muted transition-colors"
        >
          {agent.displayName}
        </Link>
        <span>of {group.name}</span>
      </p>
    </div>
  );
}
