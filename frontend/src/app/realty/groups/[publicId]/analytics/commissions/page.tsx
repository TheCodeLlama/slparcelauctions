export const dynamic = "force-dynamic";

import { GroupCommissionAnalyticsPage } from "@/components/realty/analytics/GroupCommissionAnalyticsPage";

interface PageProps {
  params: Promise<{ publicId: string }>;
}

/**
 * Realty Groups: F — leader commission analytics page (spec §15.2).
 *
 * <p>{@code force-dynamic} because the analytics rows depend on the caller's
 * JWT for the server-side leader/{@code MANAGE_MEMBERS} permission gate,
 * and the totals change per visit as new commissions clear. Mirrors the
 * SSR-caveat posture for the other realty-group sub-pages.
 */
export default async function Page({ params }: PageProps) {
  const { publicId } = await params;
  return (
    <div className="mx-auto max-w-5xl px-4 py-8 flex flex-col gap-6">
      <GroupCommissionAnalyticsPage groupPublicId={publicId} />
    </div>
  );
}
