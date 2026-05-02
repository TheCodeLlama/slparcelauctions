import { AdminUserDetailPage } from "@/components/admin/users/AdminUserDetailPage";

type Props = {
  params: Promise<{ id: string }>;
};

export default async function AdminUserDetailRoute({ params }: Props) {
  const { id } = await params;
  const userId = parseInt(id, 10);

  if (!Number.isFinite(userId) || userId <= 0) {
    return <div className="py-12 text-sm text-danger">Invalid user ID.</div>;
  }

  return <AdminUserDetailPage userId={userId} />;
}
