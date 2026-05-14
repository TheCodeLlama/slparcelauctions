"use client";

import { useParams } from "next/navigation";
import { useMemo } from "react";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { SettingsTab } from "@/components/realty/SettingsTab";
import { useRealtyGroupBySlug } from "@/hooks/realty/useRealtyGroups";
import { useCurrentUser } from "@/lib/user";

/**
 * Leader-only settings page (transfer leadership, dissolve). Replaces the
 * Settings tab on the former `/dashboard/groups/[slug]/manage` page.
 */
export default function GroupSettingsPage() {
  const params = useParams<{ slug: string }>();
  const slug = params?.slug;
  const group = useRealtyGroupBySlug(slug);
  const me = useCurrentUser();

  const isLeader = useMemo(() => {
    if (!group.data || !me.data) return false;
    return group.data.leader.userPublicId === me.data.publicId;
  }, [group.data, me.data]);

  if (group.isPending || me.isPending) {
    return <LoadingSpinner label="Loading settings..." />;
  }
  if (!group.data) return null;
  if (!isLeader) {
    return (
      <p className="text-sm text-fg-muted">
        Only the group leader can change settings.
      </p>
    );
  }
  return <SettingsTab group={group.data} />;
}
