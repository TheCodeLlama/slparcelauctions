import { AdminSupportTicketQueue } from "@/components/admin/support/AdminSupportTicketQueue";

// Per-visit data (filter params, fresh counts) — same posture as the
// other admin list pages so Amplify build-time prerender can't snapshot
// a stale queue against a freshly-changed wire shape.
export const dynamic = "force-dynamic";

export default function AdminSupportTicketQueuePage() {
  return <AdminSupportTicketQueue />;
}
