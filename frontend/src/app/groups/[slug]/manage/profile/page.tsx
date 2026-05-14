"use client";

import { useParams } from "next/navigation";
import { useMemo } from "react";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { GroupProfileForm } from "@/components/realty/GroupProfileForm";
import { useRealtyGroupBySlug } from "@/hooks/realty/useRealtyGroups";
import { useCurrentUser } from "@/lib/user";
import type {
  RealtyGroupPermission,
  RealtyGroupPublicDto,
} from "@/types/realty";

/**
 * Editable profile form for a realty group. Replaces the Profile tab on the
 * former `/dashboard/groups/[slug]/manage` page. The slug-level layout above
 * this page already verified group existence and gates non-members off
 * member-only paths; we still re-resolve the group here because the form
 * needs the full DTO and TanStack Query dedupes the request inside the same
 * render so the wire cost stays at one fetch per visit.
 */
export default function GroupProfilePage() {
  const params = useParams<{ slug: string }>();
  const slug = params?.slug;
  const group = useRealtyGroupBySlug(slug);
  const me = useCurrentUser();

  const { callerPermissions, isLeader } = useMemo(() => {
    if (!group.data || !me.data) {
      return {
        callerPermissions: new Set<RealtyGroupPermission>(),
        isLeader: false,
      };
    }
    const g: RealtyGroupPublicDto = group.data;
    const leader = g.leader.userPublicId === me.data.publicId;
    const ownAgentRow = g.agents.find(
      (a) => a.userPublicId === me.data!.publicId,
    );
    return {
      isLeader: leader,
      callerPermissions: new Set<RealtyGroupPermission>(
        ownAgentRow?.permissions ?? [],
      ),
    };
  }, [group.data, me.data]);

  if (group.isPending || me.isPending) {
    return <LoadingSpinner label="Loading profile..." />;
  }
  if (!group.data) return null;

  return (
    <GroupProfileForm
      group={group.data}
      callerPermissions={callerPermissions}
      isLeader={isLeader}
    />
  );
}
