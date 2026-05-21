import { AdminSupportTicketDetail } from "@/components/admin/support/AdminSupportTicketDetail";

// Ticket state, assignment, and message stream all change per visit; force
// dynamic so Amplify never tries to prerender a stale snapshot at build time.
export const dynamic = "force-dynamic";

export default async function AdminSupportTicketDetailPage({
  params,
}: {
  params: Promise<{ publicId: string }>;
}) {
  const { publicId } = await params;
  return <AdminSupportTicketDetail publicId={publicId} />;
}
