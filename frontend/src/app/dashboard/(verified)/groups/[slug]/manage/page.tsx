"use client";

import { useParams, useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { cn } from "@/lib/cn";
import {
  useMyRealtyGroups,
  useRealtyGroupBySlug,
} from "@/hooks/realty/useRealtyGroups";
import { useCurrentUser } from "@/lib/user";
import { GroupProfileForm } from "@/components/realty/GroupProfileForm";
import { MembersTab } from "@/components/realty/MembersTab";
import { InvitationsTab } from "@/components/realty/InvitationsTab";
import { SettingsTab } from "@/components/realty/SettingsTab";
import type {
  RealtyGroupPermission,
  RealtyGroupPublicDto,
} from "@/types/realty";

type TabId = "profile" | "members" | "invitations" | "settings";

/**
 * Manage page for a realty group the caller belongs to.
 *
 * Lookup flow:
 * 1. Resolve the group by slug from {@code useRealtyGroupBySlug}.
 * 2. Determine the caller's role from {@code useCurrentUser}: leader iff
 *    {@code currentUser.publicId === group.leader.userPublicId}.
 * 3. If the caller is not a member (neither leader nor in {@code agents}),
 *    redirect to the public profile page.
 * 4. Render tabs whose visibility is gated by:
 *    - Profile: always visible (anyone can see; edit gates inside form)
 *    - Members: always visible
 *    - Invitations: visible if leader or has INVITE_AGENTS
 *    - Settings: leader only
 */
export default function GroupManagePage() {
  const router = useRouter();
  const params = useParams<{ slug: string }>();
  const slug = params?.slug;

  const group = useRealtyGroupBySlug(slug);
  const myGroups = useMyRealtyGroups();
  const me = useCurrentUser();
  const [activeTab, setActiveTab] = useState<TabId>("profile");

  const { callerPermissions, isLeader, isMember, callerUserPublicId } =
    useMemo(() => {
      if (!group.data || !me.data) {
        return {
          callerPermissions: new Set<RealtyGroupPermission>(),
          isLeader: false,
          isMember: false,
          callerUserPublicId: null,
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
        callerPermissions: perms,
        isLeader: leader,
        isMember: leader || !!ownAgentRow,
        callerUserPublicId: me.data.publicId,
      };
    }, [group.data, me.data]);

  // Redirect non-members to the public page.
  useEffect(() => {
    if (
      !slug ||
      group.isPending ||
      myGroups.isPending ||
      me.isPending ||
      !group.data
    )
      return;
    if (!isMember) {
      router.replace(`/group/${encodeURIComponent(slug)}`);
    }
  }, [
    slug,
    group.isPending,
    group.data,
    isMember,
    myGroups.isPending,
    me.isPending,
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
  if (!isMember) {
    return <LoadingSpinner label="Redirecting..." />;
  }

  const canInvite = isLeader || callerPermissions.has("INVITE_AGENTS");

  const tabs: { id: TabId; label: string; visible: boolean }[] = [
    { id: "profile", label: "Profile", visible: true },
    { id: "members", label: "Members", visible: true },
    { id: "invitations", label: "Invitations", visible: canInvite },
    { id: "settings", label: "Settings", visible: isLeader },
  ];

  return (
    <div className="mx-auto max-w-4xl px-4 py-8 flex flex-col gap-6">
      <header className="flex flex-col gap-1">
        <h1 className="text-xl font-bold tracking-tight font-display">
          Manage {group.data.name}
        </h1>
        <span className="text-xs text-fg-muted">
          {isLeader ? "Leader" : "Agent"}
        </span>
      </header>

      <nav
        role="tablist"
        aria-label="Group management sections"
        className="flex gap-1 border-b border-border"
      >
        {tabs
          .filter((t) => t.visible)
          .map((t) => (
            <button
              key={t.id}
              role="tab"
              type="button"
              aria-selected={activeTab === t.id}
              aria-controls={`${t.id}-panel`}
              onClick={() => setActiveTab(t.id)}
              data-testid={`manage-tab-${t.id}`}
              className={cn(
                "px-4 py-2 text-sm font-medium transition-colors",
                activeTab === t.id
                  ? "text-brand border-b-2 border-brand"
                  : "text-fg-muted hover:text-fg",
              )}
            >
              {t.label}
            </button>
          ))}
      </nav>

      <div id={`${activeTab}-panel`} role="tabpanel">
        {activeTab === "profile" && (
          <GroupProfileForm
            group={group.data}
            callerPermissions={callerPermissions}
            isLeader={isLeader}
          />
        )}
        {activeTab === "members" && (
          <MembersTab
            group={group.data}
            callerPermissions={callerPermissions}
            isLeader={isLeader}
            callerUserPublicId={callerUserPublicId}
          />
        )}
        {activeTab === "invitations" && canInvite && (
          <InvitationsTab groupPublicId={group.data.publicId} />
        )}
        {activeTab === "settings" && isLeader && (
          <SettingsTab group={group.data} />
        )}
      </div>
    </div>
  );
}
