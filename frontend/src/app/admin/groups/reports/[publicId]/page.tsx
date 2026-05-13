import { AdminGroupReportDetailPage } from "@/components/admin/realty-groups/AdminGroupReportDetailPage";

/**
 * Detail route for a single realty-group report under the new
 * /admin/groups/reports/[publicId] namespace. The publicId here belongs
 * to the report, not the group, so the param shape stays UUID-keyed.
 */
export const dynamic = "force-dynamic";

type Props = {
  params: Promise<{ publicId: string }>;
};

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default async function AdminGroupReportDetailRoute({ params }: Props) {
  const { publicId } = await params;

  if (!UUID_PATTERN.test(publicId)) {
    return (
      <div className="py-12 text-sm text-danger">Invalid report ID.</div>
    );
  }

  return <AdminGroupReportDetailPage reportPublicId={publicId} />;
}
