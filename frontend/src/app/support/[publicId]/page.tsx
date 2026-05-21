import { SupportTicketThread } from "@/components/support/SupportTicketThread";

// Live thread data — new messages, attachment signed-URL fetches, "reopen"
// transitions — so opt out of Amplify build-time prerendering. Mirrors the
// rest of the support surface (list + new pages).
export const dynamic = "force-dynamic";

export default async function SupportTicketDetailPage({
  params,
}: {
  params: Promise<{ publicId: string }>;
}) {
  // Next.js 16: route params are async; must be awaited before passing to a
  // client component. See `frontend/AGENTS.md` and the matching admin coupon
  // detail page at `src/app/admin/coupons/[publicId]/page.tsx`.
  const { publicId } = await params;
  return <SupportTicketThread publicId={publicId} />;
}
