"use client";

import { useParams } from "next/navigation";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { SlGroupsPage } from "@/components/realty/slgroup/SlGroupsPage";
import { useRealtyGroupBySlug } from "@/hooks/realty/useRealtyGroups";

/**
 * SL Groups management page for a realty group. Migrated from
 * `/realty/groups/[publicId]/sl-groups`. The body component is unchanged;
 * this wrapper resolves slug -> publicId so the existing publicId-keyed
 * service surface keeps working. TanStack Query dedupes the slug fetch
 * with the layout above this page.
 */
export default function SlGroupsPageRoute() {
  const params = useParams<{ slug: string }>();
  const group = useRealtyGroupBySlug(params?.slug);

  if (group.isPending) {
    return <LoadingSpinner label="Loading SL groups..." />;
  }
  if (!group.data) return null;

  return <SlGroupsPage groupPublicId={group.data.publicId} />;
}
