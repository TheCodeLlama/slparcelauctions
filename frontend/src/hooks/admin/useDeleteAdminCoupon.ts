"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { deleteAdminCoupon } from "@/lib/api/coupons";
import { adminCouponKey } from "./useAdminCoupon";

/**
 * Mutation hook for `DELETE /api/v1/admin/coupons/{publicId}`. The
 * backend hard-deletes when zero grants exist and soft-archives
 * (`active=false`, `redeemableUntil=now`) otherwise. On success,
 * routes back to the list page and invalidates the list cache so the
 * row reflects the new state.
 */
export function useDeleteAdminCoupon(publicId: string) {
  const qc = useQueryClient();
  const router = useRouter();
  return useMutation({
    mutationFn: (): Promise<void> => deleteAdminCoupon(publicId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminCouponKey(publicId) });
      qc.invalidateQueries({ queryKey: ["admin-coupons"] });
      router.push("/admin/coupons");
    },
  });
}
