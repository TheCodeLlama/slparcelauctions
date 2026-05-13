"use client";

import { useParams } from "next/navigation";
import { useMemo } from "react";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { InvitationsTab } from "@/components/realty/InvitationsTab";
import { useRealtyGroupBySlug } from "@/hooks/realty/useRealtyGroups";
import { useCurrentUser } from "@/lib/user";
import type { RealtyGroupPublicDto } from "@/types/realty";

/**
 * Group-scoped invitation sender view. Replaces the Invitations tab on the
 * former `/dashboard/groups/[slug]/manage` page. Permission gate: caller
 * must be the leader or hold `INVITE_AGENTS`. The layout above hides the
 * sub-nav link for unpermitted callers and redirects non-members entirely;
 * this page-level check still gates members who lack the permission and
 * try to deep-link the URL.
 */
export default function GroupInvitationsPage() {
  const params = useParams<{ slug: string }>();
  const slug = params?.slug;
  const group = useRealtyGroupBySlug(slug);
  const me = useCurrentUser();

  const canInvite = useMemo(() => {
    if (!group.data || !me.data) return false;
    const g: RealtyGroupPublicDto = group.data;
    if (g.leader.userPublicId === me.data.publicId) return true;
    const ownAgentRow = g.agents.find(
      (a) => a.userPublicId === me.data!.publicId,
    );
    return !!ownAgentRow?.permissions.includes("INVITE_AGENTS");
  }, [group.data, me.data]);

  if (group.isPending || me.isPending) {
    return <LoadingSpinner label="Loading invitations..." />;
  }
  if (!group.data) return null;
  if (!canInvite) {
    return (
      <p className="text-sm text-fg-muted">
        You don&apos;t have permission to manage invitations for this group.
      </p>
    );
  }
  return <InvitationsTab groupPublicId={group.data.publicId} />;
}
