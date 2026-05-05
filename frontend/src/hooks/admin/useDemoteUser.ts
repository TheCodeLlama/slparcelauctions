"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import { useToast } from "@/components/ui/Toast/useToast";
import { isApiError } from "@/lib/api";

export function useDemoteUser(publicId: string) {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: (notes: string) => adminApi.users.demote(publicId, notes),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.user(publicId) });
      qc.invalidateQueries({ queryKey: adminQueryKeys.users() });
      qc.invalidateQueries({ queryKey: adminQueryKeys.stats() });
      toast.success("User demoted.");
    },
    onError: (err) => {
      const code = isApiError(err) ? (err.problem as { code?: string }).code : null;
      if (code === "SELF_DEMOTE_FORBIDDEN") {
        toast.error("You cannot demote yourself.");
        return;
      }
      if (code === "NOT_ADMIN") {
        toast.error("User is not an admin.");
        return;
      }
      toast.error("Couldn't demote user.");
    },
  });
}
