"use client";

import Link from "next/link";
import { ThemedImage } from "@/components/ui/ThemedImage";
import { useThemedImage } from "@/lib/theme/useThemedImage";
import { cn } from "@/lib/cn";

export interface GroupChipProps {
  /** Slug used for the link target /groups/{slug}. */
  groupSlug: string;
  /** Display name rendered inside the chip. */
  groupName: string;
  /**
   * Light logo variant (relative API path, e.g.
   * `/api/v1/realty-groups/{id}/logo`). Null => initials fallback.
   */
  logoLightUrl?: string | null;
  /** Dark logo variant. Same convention as {@link logoLightUrl}. */
  logoDarkUrl?: string | null;
  className?: string;
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  if (parts.length === 1) return parts[0][0]!.toUpperCase();
  return (parts[0][0]! + parts[1][0]!).toUpperCase();
}

/**
 * Small inline chip identifying a realty group. Used in listing-card
 * "Listed by X of Group" badges (sub-project C) and anywhere we need a
 * compact group reference. Renders as a Next.js {@link Link} pointing at
 * the group's public profile.
 *
 * The logo is theme-aware via {@link ThemedImage}; it picks the variant
 * matching the active theme and falls back to the sibling slot when only
 * one was uploaded. Image bytes come from the backend; ThemedImage funnels
 * the relative path through {@code apiUrl} so the browser hits the API
 * origin rather than the page origin (Amplify does not proxy `/api/*`).
 */
export function GroupChip({
  groupSlug,
  groupName,
  logoLightUrl,
  logoDarkUrl,
  className,
}: GroupChipProps) {
  const hasLogo = useThemedImage(logoLightUrl, logoDarkUrl) !== null;

  return (
    <Link
      href={`/groups/${encodeURIComponent(groupSlug)}`}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full bg-bg-hover px-2 py-0.5 text-xs font-medium text-fg hover:bg-border transition-colors",
        className,
      )}
      data-testid="group-chip"
    >
      {hasLogo ? (
        <ThemedImage
          lightSrc={logoLightUrl}
          darkSrc={logoDarkUrl}
          alt=""
          width={16}
          height={16}
          className="size-4 rounded-full object-cover"
          aria-hidden="true"
          loading="lazy"
        />
      ) : (
        <span
          aria-hidden="true"
          className="inline-flex size-4 items-center justify-center rounded-full bg-info-bg text-[8px] font-bold text-info"
        >
          {initials(groupName)}
        </span>
      )}
      <span className="truncate">{groupName}</span>
    </Link>
  );
}
