/* eslint-disable @next/next/no-img-element -- avatar + logo images are API-served binary content */
import Link from "next/link";
import { apiUrl } from "@/lib/api/url";
import type { GroupAttribution, ListingAgent } from "@/types/auction";

export interface GroupAttributionLineProps {
  agent: ListingAgent | null | undefined;
  group: GroupAttribution | null | undefined;
}

/**
 * "Listed by <agent> of <group>" attribution line for the auction detail
 * page. Rendered near the header when the listing was created under a realty
 * group (non-dissolved). Returns null when either side is absent or the group
 * is dissolved — individual listings show no attribution, and dissolved groups
 * are retired from public display per spec §6.2.
 */
export function GroupAttributionLine({ agent, group }: GroupAttributionLineProps) {
  if (!agent || !group || group.dissolved) return null;

  const resolvedAvatar = apiUrl(agent.avatarUrl ?? null);
  const resolvedLogo = apiUrl(group.logoUrl ?? null);

  return (
    <div className="flex items-center gap-2 text-sm text-fg-muted">
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
      <strong className="text-fg">{agent.displayName}</strong>
      <span>of</span>
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
        href={`/group/${encodeURIComponent(group.slug)}`}
        className="font-semibold text-fg underline hover:text-fg-muted transition-colors"
      >
        {group.name}
      </Link>
    </div>
  );
}
