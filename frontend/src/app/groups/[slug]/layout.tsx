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
  /** True for routes the layout's non-member redirect leaves alone. */
  publicAccessible?: boolean;
}

/**
 * Persistent layout for every `/groups/[slug]/*` route. Spec section 5.4.
 *
 * Renders a horizontal sub-nav whose items are gated on the caller's role
 * and permissions. Non-members deep-linking to any member-only path are
 * redirected to `/groups/[slug]` (the public profile root). The public
 * profile root and reviews route pass the gate unconditionally.
 *
 * Why a "use client" layout: we need `useParams`, `usePathname`,
 * `useRealtyGroupBySlug`, and `useCurrentUser` to compute role + active
 * nav item. The slug to publicId resolution happens here and again in each
 * sub-page; TanStack Query dedupes the network request inside the same
 * render so the wire cost stays at one fetch per slug per visit.
 */
export default function GroupSlugLayout({ children }: { children: ReactNode }) {
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

  // Redirect non-members off member-only sub-paths. The public profile root
  // (`/groups/[slug]`) and reviews (`/groups/[slug]/reviews`) are exempt:
  // both render anonymously, so a logged-in non-member is no more restricted
  // than an anonymous visitor.
  useEffect(() => {
    if (!slug || group.isPending || me.isPending || !group.data) return;
    if (isMember) return;
    if (!pathname) return;
    const base = `/groups/${encodeURIComponent(slug)}`;
    const reviews = `${base}/reviews`;
    if (pathname === base || pathname === reviews) return;
    router.replace(base);
  }, [
    slug,
    pathname,
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

  const base = `/groups/${encodeURIComponent(slug ?? "")}`;
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
    {
      href: `${base}/reviews`,
      label: "Reviews",
      visible: true,
      publicAccessible: true,
    },
    { href: `${base}/settings`, label: "Settings", visible: isLeader },
  ];

  return (
    <div className="mx-auto max-w-5xl px-4 py-6 flex flex-col gap-6">
      <nav
        aria-label="Group sections"
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
