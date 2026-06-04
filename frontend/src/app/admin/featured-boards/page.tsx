import { AdminFeaturedBoardsPage } from "@/components/admin/featured-boards/AdminFeaturedBoardsPage";

// Per-visit data (slot assignments change as auctions end / curators move slots)
// -- same posture as other admin list pages so Amplify build-time prerender
// cannot snapshot a stale slot list against a changed wire shape.
export const dynamic = "force-dynamic";

export default function AdminFeaturedBoardsRoute() {
  return <AdminFeaturedBoardsPage />;
}
