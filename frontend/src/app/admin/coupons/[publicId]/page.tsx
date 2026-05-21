import { AdminCouponDetail } from "@/components/admin/coupons/AdminCouponDetail";

type Props = {
  params: Promise<{ publicId: string }>;
};

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// Per-visit data (live aggregate counters, grants table). Mirrors the
// `force-dynamic` posture of the list page so the build never tries
// to prerender a coupon detail at Amplify build time.
export const dynamic = "force-dynamic";

export default async function AdminCouponDetailRoute({ params }: Props) {
  // Next.js 16: `params` is async — must await.
  const { publicId } = await params;

  if (!UUID_PATTERN.test(publicId)) {
    return (
      <div className="py-12 text-sm text-danger" data-testid="invalid-coupon-id">
        Invalid coupon ID.
      </div>
    );
  }

  return <AdminCouponDetail publicId={publicId} />;
}
