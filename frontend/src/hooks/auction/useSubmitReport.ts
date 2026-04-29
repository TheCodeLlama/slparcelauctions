"use client";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { userReportsApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import { useToast } from "@/components/ui/Toast/useToast";
import { isApiError } from "@/lib/api";
import type { ReportRequest } from "@/lib/admin/types";

export function useSubmitReport(auctionId: number) {
  const qc = useQueryClient();
  const toast = useToast();

  return useMutation({
    mutationFn: (body: ReportRequest) => userReportsApi.submit(auctionId, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminQueryKeys.myReport(auctionId) });
      toast.success("Report submitted. Our team will review it.");
    },
    onError: (err) => {
      if (isApiError(err)) {
        const code = (err.problem as { code?: string }).code;
        if (code === "CANNOT_REPORT_OWN_LISTING") {
          toast.error("You cannot report your own listing.");
          return;
        }
        if (code === "VERIFICATION_REQUIRED") {
          toast.error("You must verify your Second Life avatar to submit reports.");
          return;
        }
        if (code === "AUCTION_NOT_REPORTABLE") {
          toast.error("This listing is not currently reportable.");
          return;
        }
      }
      toast.error("Couldn't submit your report. Please try again.");
    },
  });
}
