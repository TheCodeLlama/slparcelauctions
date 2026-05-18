import { AdminEscrowReviewDetailPage } from "./AdminEscrowReviewDetailPage";

/**
 * Detail route for a single escrow manual review (spec §7). The publicId
 * here belongs to the review row, so the param shape stays UUID-keyed
 * (BaseEntity wire convention). Force-dynamic for the same per-visit
 * triage reason as the queue.
 */
export const dynamic = "force-dynamic";

type Props = {
  params: Promise<{ publicId: string }>;
};

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default async function AdminEscrowReviewDetailRoute({ params }: Props) {
  const { publicId } = await params;

  if (!UUID_PATTERN.test(publicId)) {
    return <div className="py-12 text-sm text-danger">Invalid review ID.</div>;
  }

  return <AdminEscrowReviewDetailPage reviewPublicId={publicId} />;
}
