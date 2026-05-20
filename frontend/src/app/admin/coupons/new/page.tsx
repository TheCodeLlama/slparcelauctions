import { AdminCouponForm } from "@/components/admin/coupons/AdminCouponForm";

// Per-visit form state (server-emitted form scaffolding shouldn't be
// prerendered at Amplify build time). Mirrors the list page posture.
export const dynamic = "force-dynamic";

export default function AdminCouponNewPage() {
  return <AdminCouponForm />;
}
