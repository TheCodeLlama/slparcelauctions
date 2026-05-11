/* eslint-disable @next/next/no-img-element -- logo images are API-served binary content; next/image requires remotePatterns config */
import Link from "next/link";
import { apiUrl } from "@/lib/api/url";
import { cn } from "@/lib/cn";

export interface GroupBadgeProps {
  /** Slug used for the link target /group/{slug}. */
  groupSlug: string;
  /** Display name rendered inside the badge. */
  groupName: string;
  /**
   * Relative logo path (e.g. `/api/v1/realty-groups/{id}/logo`). Wrapped
   * through {@link apiUrl} at render time. Null => initials fallback.
   */
  logoUrl?: string | null;
  /** Optional helper line beneath the group name. */
  subtitle?: string;
  className?: string;
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  if (parts.length === 1) return parts[0][0]!.toUpperCase();
  return (parts[0][0]! + parts[1][0]!).toUpperCase();
}

/**
 * Larger sibling of {@link GroupChip}: shows a square logo (or initials
 * fallback) next to the group name and optional subtitle, sized for use
 * as a section header or hero callout. Used wherever the group identity
 * itself is the main visual, not a secondary "listed by" note.
 */
export function GroupBadge({
  groupSlug,
  groupName,
  logoUrl,
  subtitle,
  className,
}: GroupBadgeProps) {
  const resolvedLogo = apiUrl(logoUrl ?? null);

  return (
    <Link
      href={`/group/${encodeURIComponent(groupSlug)}`}
      className={cn(
        "inline-flex items-center gap-3 rounded-lg border border-border bg-surface-raised px-3 py-2 transition-colors hover:bg-bg-hover",
        className,
      )}
      data-testid="group-badge"
    >
      {resolvedLogo ? (
        <img
          src={resolvedLogo}
          alt=""
          width={40}
          height={40}
          className="size-10 rounded-md object-cover"
          aria-hidden="true"
          loading="lazy"
        />
      ) : (
        <span
          aria-hidden="true"
          className="inline-flex size-10 items-center justify-center rounded-md bg-info-bg text-sm font-bold text-info"
        >
          {initials(groupName)}
        </span>
      )}
      <span className="flex flex-col">
        <span className="text-sm font-semibold text-fg">{groupName}</span>
        {subtitle && (
          <span className="text-xs text-fg-muted">{subtitle}</span>
        )}
      </span>
    </Link>
  );
}
