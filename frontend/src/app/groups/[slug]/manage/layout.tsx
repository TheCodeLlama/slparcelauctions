"use client";

import Link from "next/link";
import { useParams, usePathname, useRouter } from "next/navigation";
import { useEffect, useMemo, type ReactNode } from "react";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { cn } from "@/lib/cn";
import { useRealtyGroupBySlug } from "@/hooks/realty/useRealtyGroups";
import { useCurrentUser } from "@/lib/user";
import type {
  RealtyGroupPermission,
  RealtyGroupPublicDto,
} from "@/types/realty";

interface SubNavItem {
  href: string;
  label: string;
  visible: boolean;
}

/**
 * Persistent layout for every {@code /groups/[slug]/manage/*} route. Wraps
 * every member-only sub-page with the horizontal sub-nav whose items are
 * gated on the caller's role and permissions.
 *
 * <p>Non-members deep-linking to any path under {@code /manage/*} are
 * redirected to the public profile at {@code /groups/[slug]}. The public
 * profile and the public reviews page ({@code /groups/[slug]/reviews}) live
 * outside this layout, so anonymous and non-member visitors hit them
 * directly without ever entering this redirect logic.
 *
 * <p>The previous public-route exemption (which carved out the public
 * profile + reviews from this layout's redirect) is gone — those routes no
 * longer share a layout with the management view, so the exemption is no
 * longer needed.
 *
 * <p>Why a "use client" layout: needs {@code useParams}, {@code usePathname},
 * {@code useRealtyGroupBySlug}, and {@code useCurrentUser} to compute role +
 * active nav item. TanStack Query dedupes the slug fetch between layout and
 * sub-pages so the wire cost stays at one fetch per slug per visit.
 */
export default function GroupManageLayout({ children }: { children: ReactNode }) {
  const router = useRouter();
  const params = useParams<{ slug: string }>();
  const pathname = usePathname();
  const slug = params?.slug;

  const group = useRealtyGroupBySlug(slug);
  const me = useCurrentUser();

  const { isLeader, isMember, callerPermissions } = useMemo(() => {
    if (!group.data || !me.data) {
      return {
        isLeader: false,
        isMember: false,
        callerPermissions: new Set<RealtyGroupPermission>(),
      };
    }
    const g: RealtyGroupPublicDto = group.data;
    const leader = g.leader.userPublicId === me.data.publicId;
    const ownAgentRow = g.agents.find(
      (a) => a.userPublicId === me.data!.publicId,
    );
    const perms = new Set<RealtyGroupPermission>(
      ownAgentRow?.permissions ?? [],
    );
    return {
      isLeader: leader,
      isMember: leader || !!ownAgentRow,
      callerPermissions: perms,
    };
  }, [group.data, me.data]);

  // Redirect non-members off the management subtree entirely. Everything
  // under /manage/* is member-only; anonymous / non-member viewers belong on
  // the public profile.
  useEffect(() => {
    if (!slug || group.isPending || me.isPending || !group.data) return;
    if (isMember) return;
    router.replace(`/groups/${encodeURIComponent(slug)}`);
  }, [
    slug,
    group.isPending,
    group.data,
    me.isPending,
    isMember,
    router,
  ]);

  if (group.isPending || me.isPending) {
    return <LoadingSpinner label="Loading group..." />;
  }

  if (group.isError || !group.data) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-8">
        <p className="text-sm text-danger">
          Couldn&apos;t load this group. Try again.
        </p>
      </div>
    );
  }

  const base = `/groups/${encodeURIComponent(slug ?? "")}/manage`;
  const canInvite = isLeader || callerPermissions.has("INVITE_AGENTS");
  const canViewWallet =
    isLeader || callerPermissions.has("VIEW_GROUP_TRANSACTIONS");
  const canManageSlGroups =
    isLeader || callerPermissions.has("REGISTER_SL_GROUP");
  const canViewAnalytics =
    isLeader || callerPermissions.has("MANAGE_MEMBERS");

  const items: SubNavItem[] = [
    { href: `${base}/profile`, label: "Profile", visible: isMember },
    { href: `${base}/members`, label: "Members", visible: isMember },
    { href: `${base}/wallet`, label: "Wallet", visible: canViewWallet },
    {
      href: `${base}/sl-groups`,
      label: "SL Groups",
      visible: canManageSlGroups,
    },
    {
      href: `${base}/analytics/commissions`,
      label: "Analytics",
      visible: canViewAnalytics,
    },
    { href: `${base}/invitations`, label: "Invitations", visible: canInvite },
    { href: `${base}/settings`, label: "Settings", visible: isLeader },
  ];

  return (
    <div className="mx-auto max-w-5xl px-4 py-6 flex flex-col gap-6">
      <nav
        aria-label="Group management sections"
        className="flex flex-wrap gap-1 border-b border-border"
        data-testid="group-sub-nav"
      >
        {items
          .filter((i) => i.visible)
          .map((i) => {
            const active = pathname === i.href;
            return (
              <Link
                key={i.href}
                href={i.href}
                aria-current={active ? "page" : undefined}
                className={cn(
                  "px-4 py-2 text-sm font-medium transition-colors",
                  active
                    ? "text-brand border-b-2 border-brand"
                    : "text-fg-muted hover:text-fg",
                )}
              >
                {i.label}
              </Link>
            );
          })}
      </nav>
      <div>{children}</div>
    </div>
  );
}
