"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import { useToast } from "@/components/ui/Toast/useToast";
import { isApiError } from "@/lib/api";

export function useLiftBan() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({ id, liftedReason }: { id: number; liftedReason: string }) =>
      adminApi.bans.lift(id, liftedReason),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.bans() });
      qc.invalidateQueries({ queryKey: adminQueryKeys.stats() });
      toast.success("Ban lifted.");
    },
    onError: (err) => {
      const code = isApiError(err) ? (err.problem as { code?: string }).code : null;
      if (code === "BAN_ALREADY_LIFTED") {
        toast.error("Ban was already lifted. Refreshing.");
        qc.invalidateQueries({ queryKey: adminQueryKeys.bans() });
        return;
      }
      if (code === "BAN_NOT_FOUND") {
        toast.error("Ban not found. Refreshing.");
        qc.invalidateQueries({ queryKey: adminQueryKeys.bans() });
        return;
      }
      toast.error("Couldn't lift ban.");
    },
  });
}
