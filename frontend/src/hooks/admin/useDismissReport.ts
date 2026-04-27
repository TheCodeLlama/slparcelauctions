"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import { useToast } from "@/components/ui/Toast/useToast";
import { isApiError } from "@/lib/api";

export function useDismissReport() {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({ reportId, notes }: { reportId: number; notes: string }) =>
      adminApi.reports.dismiss(reportId, notes),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.reports() });
      qc.invalidateQueries({ queryKey: adminQueryKeys.stats() });
      toast.success("Report dismissed.");
    },
    onError: (err) => {
      if (isApiError(err) && (err.problem as { code?: string }).code === "REPORT_NOT_FOUND") {
        toast.error("Report not found. Refreshing.");
        qc.invalidateQueries({ queryKey: adminQueryKeys.reports() });
        return;
      }
      toast.error("Couldn't dismiss report.");
    },
  });
}
