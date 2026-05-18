import { AdminEscrowReviewsListPage } from "./AdminEscrowReviewsListPage";

/**
 * Admin escrow manual-review queue (spec §7). Force-dynamic because the
 * queue changes per visit and the row counts drive admin triage — static
 * prerender would couple the Amplify build to whatever the prod API
 * happens to be serving.
 */
export const dynamic = "force-dynamic";

export default function Page() {
  return <AdminEscrowReviewsListPage />;
}
