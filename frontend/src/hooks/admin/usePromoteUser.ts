"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import { useToast } from "@/components/ui/Toast/useToast";
import { isApiError } from "@/lib/api";

export function usePromoteUser(userId: number) {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: (notes: string) => adminApi.users.promote(userId, notes),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.user(userId) });
      qc.invalidateQueries({ queryKey: adminQueryKeys.users() });
      qc.invalidateQueries({ queryKey: adminQueryKeys.stats() });
      toast.success("User promoted to admin.");
    },
    onError: (err) => {
      const code = isApiError(err) ? (err.problem as { code?: string }).code : null;
      if (code === "ALREADY_ADMIN") {
        toast.error("User is already an admin.");
        return;
      }
      toast.error("Couldn't promote user.");
    },
  });
}
