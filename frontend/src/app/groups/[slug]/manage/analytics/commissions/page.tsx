"use client";

import { useParams } from "next/navigation";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { GroupCommissionAnalyticsPage } from "@/components/realty/analytics/GroupCommissionAnalyticsPage";
import { useRealtyGroupBySlug } from "@/hooks/realty/useRealtyGroups";

/**
 * Commission analytics page for a realty group. Migrated from
 * `/realty/groups/[publicId]/analytics/commissions`. The body component is
 * unchanged; this wrapper resolves slug -> publicId so the existing
 * publicId-keyed service surface keeps working. TanStack Query dedupes the
 * slug fetch with the layout above this page.
 */
export default function CommissionsAnalyticsPageRoute() {
  const params = useParams<{ slug: string }>();
  const group = useRealtyGroupBySlug(params?.slug);

  if (group.isPending) {
    return <LoadingSpinner label="Loading analytics..." />;
  }
  if (!group.data) return null;

  return <GroupCommissionAnalyticsPage groupPublicId={group.data.publicId} />;
}
