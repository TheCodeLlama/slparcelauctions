import { AdminDisputeDetailPage } from "./AdminDisputeDetailPage";

export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <AdminDisputeDetailPage escrowId={Number(id)} />;
}
