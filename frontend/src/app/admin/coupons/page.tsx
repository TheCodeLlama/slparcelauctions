import { AdminCouponList } from "@/components/admin/coupons/AdminCouponList";

// Per-visit data (filter params, fresh totals) — same posture as the
// other admin list pages.
export const dynamic = "force-dynamic";

export default function AdminCouponsRoute() {
  return <AdminCouponList />;
}
