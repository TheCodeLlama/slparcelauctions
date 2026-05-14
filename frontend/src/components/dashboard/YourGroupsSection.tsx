"use client";

import Link from "next/link";

type YourGroup = {
  publicId: string;
  slug: string;
  name: string;
  role: "LEADER" | "AGENT";
};

export interface YourGroupsSectionProps {
  groups: YourGroup[];
}

/**
 * Dashboard overview section listing every realty group the caller
 * belongs to, with a role chip and quick-action links to that group's
 * operational surfaces (profile / wallet / members / reviews). Empty
 * state suppresses the section entirely so a non-member's overview is
 * unchanged.
 *
 * Per the /groups namespace migration plan (Task 28).
 */
export function YourGroupsSection({ groups }: YourGroupsSectionProps) {
  if (groups.length === 0) return null;
  return (
    <section className="flex flex-col gap-3" data-testid="your-groups-section">
      <h2 className="text-sm font-semibold text-fg">Your groups</h2>
      <ul className="flex flex-col gap-2">
        {groups.map((g) => (
          <li
            key={g.publicId}
            className="rounded-lg border border-border bg-surface-raised p-3 flex items-center justify-between gap-3"
            data-testid="your-groups-card"
          >
            <div className="flex flex-col">
              <Link
                href={`/groups/${g.slug}`}
                className="text-sm font-semibold text-fg hover:underline"
              >
                {g.name}
              </Link>
              <span className="text-xs text-fg-muted uppercase tracking-wide">
                {g.role === "LEADER" ? "Leader" : "Agent"}
              </span>
            </div>
            <div className="flex gap-1 flex-wrap text-xs">
              <Link
                href={`/groups/${g.slug}/profile`}
                aria-label={`Profile for ${g.name}`}
                className="rounded bg-bg-subtle px-2 py-1 text-fg hover:bg-bg-muted"
              >
                Profile
              </Link>
              <Link
                href={`/groups/${g.slug}/wallet`}
                aria-label={`Wallet for ${g.name}`}
                className="rounded bg-bg-subtle px-2 py-1 text-fg hover:bg-bg-muted"
              >
                Wallet
              </Link>
              <Link
                href={`/groups/${g.slug}/members`}
                aria-label={`Members for ${g.name}`}
                className="rounded bg-bg-subtle px-2 py-1 text-fg hover:bg-bg-muted"
              >
                Members
              </Link>
              <Link
                href={`/groups/${g.slug}/reviews`}
                aria-label={`Reviews for ${g.name}`}
                className="rounded bg-bg-subtle px-2 py-1 text-fg hover:bg-bg-muted"
              >
                Reviews
              </Link>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
