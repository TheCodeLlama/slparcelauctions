import { AdminUserDetailPage } from "@/components/admin/users/AdminUserDetailPage";

type Props = {
  params: Promise<{ publicId: string }>;
};

export default async function AdminUserDetailRoute({ params }: Props) {
  const { publicId } = await params;
  const userId = parseInt(publicId, 10);

  if (!Number.isFinite(userId) || userId <= 0) {
    return <div className="py-12 text-sm text-danger">Invalid user ID.</div>;
  }

  return <AdminUserDetailPage userId={userId} />;
}
