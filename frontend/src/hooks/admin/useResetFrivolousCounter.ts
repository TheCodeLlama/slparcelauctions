"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import { useToast } from "@/components/ui/Toast/useToast";

export function useResetFrivolousCounter(userId: number) {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: (notes: string) => adminApi.users.resetFrivolousCounter(userId, notes),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.user(userId) });
      qc.invalidateQueries({ queryKey: adminQueryKeys.stats() });
      toast.success("Frivolous cancellation counter reset.");
    },
    onError: () => {
      toast.error("Couldn't reset frivolous counter.");
    },
  });
}
