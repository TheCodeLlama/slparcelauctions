"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import { useToast } from "@/components/ui/Toast/useToast";
import { isApiError } from "@/lib/api";
import type { CreateBanRequest } from "@/lib/admin/types";

export function useCreateBan() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: (body: CreateBanRequest) => adminApi.bans.create(body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.bans() });
      qc.invalidateQueries({ queryKey: adminQueryKeys.stats() });
      toast.success("Ban created.");
    },
    onError: (err) => {
      const code = isApiError(err) ? (err.problem as { code?: string }).code : null;
      if (code === "BAN_TYPE_FIELD_MISMATCH") {
        toast.error("Ban type and identifier fields don't match.");
        return;
      }
      toast.error("Couldn't create ban.");
    },
  });
}
