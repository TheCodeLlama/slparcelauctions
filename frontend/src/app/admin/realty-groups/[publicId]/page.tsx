import { AdminRealtyGroupDetailPage } from "@/components/admin/realty-groups/AdminRealtyGroupDetailPage";

type Props = {
  params: Promise<{ publicId: string }>;
};

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default async function AdminRealtyGroupDetailRoute({ params }: Props) {
  const { publicId } = await params;

  if (!UUID_PATTERN.test(publicId)) {
    return (
      <div className="py-12 text-sm text-danger">Invalid group ID.</div>
    );
  }

  return <AdminRealtyGroupDetailPage publicId={publicId} />;
}
