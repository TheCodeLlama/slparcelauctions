import { AdminDisputeDetailPage } from "./AdminDisputeDetailPage";

export default async function Page({ params }: { params: Promise<{ publicId: string }> }) {
  const { publicId } = await params;
  return <AdminDisputeDetailPage escrowId={Number(publicId)} />;
}
