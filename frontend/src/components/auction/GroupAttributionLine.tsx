/* eslint-disable @next/next/no-img-element -- avatar + logo images are API-served binary content */
import Link from "next/link";
import { apiUrl } from "@/lib/api/url";
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
 * Realty Groups: E (sub-project E, spec §6.4) — case-3 listings (agent
 * lists group-owned land under a realty group) surface the group as the
 * primary seller via the "Sold by" heading. Case-1 (legacy: agent lists
 * own land under a group) is not reachable through new listings as of
 * sub-project E so this same heading also covers legacy case-1 rows
 * without further DTO branching. The backend group-attribution DTO does
 * not carry the SL group id today, so {@code group != null} is treated
 * as the case-3 marker.
 */
export function GroupAttributionLine({ agent, group }: GroupAttributionLineProps) {
  if (!agent || !group || group.dissolved) return null;

  const resolvedAvatar = apiUrl(agent.avatarUrl ?? null);
  const resolvedLogo = apiUrl(group.logoUrl ?? null);

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
        {resolvedLogo && (
          <img
            src={resolvedLogo}
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
