"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { ChevronRight } from "@/components/ui/icons";

/**
 * Three-level breadcrumb for the auction detail page:
 *
 *   Browse  →  {Region}  →  {Title}
 *
 * The "Browse" link respects {@code sessionStorage["last-browse-url"]}
 * (written by {@link BrowseShell}) so back-navigation restores the exact
 * prior filter state — a user who drilled from
 * {@code /browse?region=Tula&sort=ending_soonest} lands back there, not on
 * the default browse page. Falls back to {@code /browse} when the session
 * value is missing (direct entry via shared link, private browsing, etc.).
 *
 * The Region link applies a single filter:
 * {@code /browse?region=<encoded region>}. The URL codec is strict — we
 * emit the exact param the codec expects so there's no round-trip
 * normalization.
 *
 * The Title is truncated to 40 chars with an ellipsis so long titles do
 * not push the breadcrumb past the edge of the viewport.
 *
 * JSON-LD {@code BreadcrumbList} microdata is emitted so search engines
 * surface the breadcrumb trail correctly in SERPs (spec §SEO). The
 * "position" values are 1-indexed per the schema.org spec.
 */
interface Props {
  region: string;
  title: string;
}

const SESSION_KEY = "last-browse-url";
const TITLE_MAX_CHARS = 40;

function truncate(s: string, max: number): string {
  if (s.length <= max) return s;
  return s.slice(0, max - 1) + "…";
}

export function BreadcrumbNav({ region, title }: Props) {
  // Resolve the "Browse" href once on mount. Server-render emits the
  // fallback /browse URL to keep SSR and first-client-render identical;
  // the effect swaps in the session-restored value (if present) after
  // hydration, which avoids a React hydration warning.
  const [browseHref, setBrowseHref] = useState<string>("/browse");
  useEffect(() => {
    if (typeof window === "undefined") return;
    try {
      const stored = window.sessionStorage.getItem(SESSION_KEY);
      // eslint-disable-next-line react-hooks/set-state-in-effect -- sessionStorage is the external source of truth for the previous browse URL; mirroring it into local state after hydration avoids an SSR/client text mismatch.
      if (stored) setBrowseHref(stored);
    } catch {
      // sessionStorage can throw in private mode; keep the /browse
      // fallback.
    }
  }, []);

  const regionHref = `/browse?region=${encodeURIComponent(region)}`;
  const truncatedTitle = truncate(title, TITLE_MAX_CHARS);

  const jsonLd = {
    "@context": "https://schema.org",
    "@type": "BreadcrumbList",
    itemListElement: [
      {
        "@type": "ListItem",
        position: 1,
        name: "Browse",
        item: browseHref,
      },
      {
        "@type": "ListItem",
        position: 2,
        name: region,
        item: regionHref,
      },
      {
        "@type": "ListItem",
        position: 3,
        name: title,
      },
    ],
  };

  return (
    <nav
      aria-label="Breadcrumb"
      data-testid="breadcrumb-nav"
      className="text-body-sm text-on-surface-variant"
    >
      <ol className="flex items-center gap-2">
        <li className="flex items-center gap-2">
          <Link
            href={browseHref}
            className="transition-colors hover:text-on-surface"
            data-testid="breadcrumb-browse"
          >
            Browse
          </Link>
          <ChevronRight className="size-4" aria-hidden="true" />
        </li>
        <li className="flex items-center gap-2">
          <Link
            href={regionHref}
            className="transition-colors hover:text-on-surface"
            data-testid="breadcrumb-region"
          >
            {region}
          </Link>
          <ChevronRight className="size-4" aria-hidden="true" />
        </li>
        <li className="truncate">
          <span
            aria-current="page"
            className="text-on-surface"
            data-testid="breadcrumb-title"
            title={title}
          >
            {truncatedTitle}
          </span>
        </li>
      </ol>
      <script
        type="application/ld+json"
        // JSON.stringify guarantees no control characters that could
        // break out of the script tag; the schema.org spec requires
        // valid JSON inside the microdata block.
        dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }}
        data-testid="breadcrumb-jsonld"
      />
    </nav>
  );
}
