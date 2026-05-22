"use client";

import type { ReactNode } from "react";
import { ExternalLink } from "@/components/ui/icons";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { ThemedImage } from "@/components/ui/ThemedImage";
import { useThemedImage } from "@/lib/theme/useThemedImage";
import { cn } from "@/lib/cn";

export interface RealtyGroupHeroBannerProps {
  /** Display name (h1). */
  name: string;
  /** Slug — currently unused in the rendered output but kept on the prop surface so future linkbacks land without a refactor. */
  slug: string;
  description: string | null;
  website: string | null;
  /** ISO string for "Member since". */
  memberSince: string;
  memberCount: number;
  /**
   * Light cover variant — relative path emitted by the backend (e.g.
   * `/api/v1/realty-groups/{id}/cover`). Theme-aware via {@link ThemedImage}.
   * Both variants null => gradient empty state.
   */
  coverLightUrl: string | null;
  /** Dark cover variant. Same convention as {@link coverLightUrl}. */
  coverDarkUrl: string | null;
  /** Light logo variant. Both variants null => initials fallback. */
  logoLightUrl: string | null;
  /** Dark logo variant. Same convention as {@link logoLightUrl}. */
  logoDarkUrl: string | null;
  /**
   * Slot for member-only affordances (gear icon -> manage page). The page
   * passes an `<EditGroupAffordance>` client component here so the server
   * skeleton stays anonymous-safe and the JWT-aware overlay opts in.
   */
  editAffordance?: ReactNode;
  className?: string;
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  if (parts.length === 1) return parts[0][0]!.toUpperCase();
  return (parts[0][0]! + parts[1][0]!).toUpperCase();
}

function formatMemberSince(memberSince: string): string {
  const date = new Date(memberSince);
  if (Number.isNaN(date.getTime())) return "recently";
  return date.toLocaleString("en-US", { month: "long", year: "numeric" });
}

/**
 * Hero block at the top of `/group/[slug]`: full-width cover photo with
 * the logo overlapping its bottom-left edge, group name, description,
 * member-since + member-count chip, optional website link, and an
 * optional member-only edit affordance slot.
 *
 * Cover falls back to a tasteful gradient when null (never a broken-image
 * icon). Logo falls back to first-initial style.
 *
 * Cover and logo are theme-aware: {@link ThemedImage} picks the variant
 * matching the active theme, falls back to the sibling slot when only one
 * was uploaded, and funnels the relative path through {@code apiUrl} so the
 * browser hits the backend origin rather than the page origin (Amplify does
 * not proxy `/api/*`).
 */
export function RealtyGroupHeroBanner({
  name,
  description,
  website,
  memberSince,
  memberCount,
  coverLightUrl,
  coverDarkUrl,
  logoLightUrl,
  logoDarkUrl,
  editAffordance,
  className,
}: RealtyGroupHeroBannerProps) {
  const hasCover = useThemedImage(coverLightUrl, coverDarkUrl) !== null;
  const hasLogo = useThemedImage(logoLightUrl, logoDarkUrl) !== null;

  return (
    <section
      className={cn("flex flex-col", className)}
      aria-label={`${name} hero`}
      data-testid="realty-group-hero-banner"
    >
      <div className="relative aspect-[16/5] w-full overflow-hidden bg-bg-hover sm:rounded-xl">
        {hasCover ? (
          <ThemedImage
            lightSrc={coverLightUrl}
            darkSrc={coverDarkUrl}
            alt=""
            className="h-full w-full object-contain"
            aria-hidden="true"
            loading="lazy"
            data-testid="realty-group-hero-cover"
          />
        ) : (
          <div
            aria-hidden="true"
            className="h-full w-full bg-gradient-to-br from-info-bg via-bg-hover to-surface-raised"
            data-testid="realty-group-hero-cover-empty"
          />
        )}
        {editAffordance && (
          <div className="absolute right-3 top-3">{editAffordance}</div>
        )}
      </div>

      <div className="relative -mt-10 px-4 sm:px-6">
        <div className="flex items-end gap-4">
          {hasLogo ? (
            <ThemedImage
              lightSrc={logoLightUrl}
              darkSrc={logoDarkUrl}
              alt=""
              className="h-16 w-auto max-w-[12rem] rounded-md border border-border bg-surface-raised object-contain shadow-md"
              aria-hidden="true"
              data-testid="realty-group-hero-logo"
            />
          ) : (
            <div
              aria-hidden="true"
              className="inline-flex h-16 w-16 items-center justify-center rounded-md border border-border bg-info-bg text-2xl font-bold text-info shadow-md"
              data-testid="realty-group-hero-logo-empty"
            >
              {initials(name)}
            </div>
          )}
        </div>

        <div className="mt-4 flex flex-col gap-3">
          <div className="flex items-center gap-3 flex-wrap">
            <h1 className="text-2xl font-bold tracking-tight text-fg font-display">
              {name}
            </h1>
            <StatusBadge tone="default" data-testid="member-count-chip">
              {memberCount} member{memberCount === 1 ? "" : "s"}
            </StatusBadge>
          </div>
          {description && (
            <p className="text-sm text-fg-muted whitespace-pre-line">
              {description}
            </p>
          )}
          <div className="flex flex-wrap items-center gap-4 text-xs text-fg-muted">
            <span>Member since {formatMemberSince(memberSince)}</span>
            {website && (
              <a
                href={website}
                target="_blank"
                rel="noopener nofollow ugc"
                className="inline-flex items-center gap-1 text-fg hover:underline"
                data-testid="realty-group-hero-website"
              >
                <ExternalLink className="size-3.5" aria-hidden="true" />
                <span className="truncate max-w-[24rem]">{website}</span>
              </a>
            )}
          </div>
        </div>
      </div>
    </section>
  );
}
