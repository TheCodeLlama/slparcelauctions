"use client";

import { useParams } from "next/navigation";
import { useMemo } from "react";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { MembersTab } from "@/components/realty/MembersTab";
import { useRealtyGroupBySlug } from "@/hooks/realty/useRealtyGroups";
import { useCurrentUser } from "@/lib/user";
import type {
  RealtyGroupPermission,
  RealtyGroupPublicDto,
} from "@/types/realty";

/**
 * Members list for a realty group. Replaces the Members tab on the former
 * `/dashboard/groups/[slug]/manage` page. The body component (`MembersTab`)
 * is reused unchanged; this wrapper resolves slug to the full group DTO and
 * derives `callerPermissions`, `isLeader`, and `callerUserPublicId` from the
 * current user. The layout above this page has already verified group
 * existence + caller membership; TanStack Query dedupes the slug fetch so
 * the wire cost stays at one request per render.
 */
export default function GroupMembersPage() {
  const params = useParams<{ slug: string }>();
  const slug = params?.slug;
  const group = useRealtyGroupBySlug(slug);
  const me = useCurrentUser();

  const { callerPermissions, isLeader, callerUserPublicId } = useMemo(() => {
    if (!group.data || !me.data) {
      return {
        callerPermissions: new Set<RealtyGroupPermission>(),
        isLeader: false,
        callerUserPublicId: null,
      };
    }
    const g: RealtyGroupPublicDto = group.data;
    const leader = g.leader.userPublicId === me.data.publicId;
    const ownAgentRow = g.agents.find(
      (a) => a.userPublicId === me.data!.publicId,
    );
    return {
      callerPermissions: new Set<RealtyGroupPermission>(
        ownAgentRow?.permissions ?? [],
      ),
      isLeader: leader,
      callerUserPublicId: me.data.publicId,
    };
  }, [group.data, me.data]);

  if (group.isPending || me.isPending) {
    return <LoadingSpinner label="Loading members..." />;
  }
  if (!group.data) return null;

  return (
    <MembersTab
      group={group.data}
      callerPermissions={callerPermissions}
      isLeader={isLeader}
      callerUserPublicId={callerUserPublicId}
    />
  );
}
