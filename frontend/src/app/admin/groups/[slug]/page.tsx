"use client";

import { useParams } from "next/navigation";
import { LoadingSpinner } from "@/components/ui/LoadingSpinner";
import { useRealtyGroupBySlug } from "@/hooks/realty/useRealtyGroups";
import { AdminRealtyGroupDetailPage } from "@/components/admin/realty-groups/AdminRealtyGroupDetailPage";

/**
 * Admin detail route keyed on the group slug. Resolves slug -> publicId
 * via the existing public `getGroupBySlug` lookup and forwards the
 * publicId to the existing detail component (which already accepts it as
 * a prop). The internal admin endpoints remain publicId-keyed; only the
 * URL is slug-shaped, matching the public /groups/[slug] surface.
 */
export default function AdminGroupDetailRoute() {
  const params = useParams<{ slug: string }>();
  const slug = params?.slug;
  const group = useRealtyGroupBySlug(slug);

  if (group.isPending) {
    return <LoadingSpinner label="Loading group..." />;
  }

  if (group.isError || !group.data) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-8">
        <p className="text-sm text-danger">Could not load this group.</p>
      </div>
    );
  }

  return <AdminRealtyGroupDetailPage publicId={group.data.publicId} />;
}
