"use client";
import { useMutation } from "@tanstack/react-query";
import { realtyGroupReportsApi } from "@/lib/api/realtyGroupReports";
import type { SubmitReportRequest } from "@/types/realty";

/**
 * Submit a public report against a realty group. No query cache to
 * invalidate on the reporter side — the report flow opens a modal,
 * fires this mutation, and shows a toast on the response.
 *
 * <p>Error surfaces the caller renders out of the {@code ApiError}:
 * <ul>
 *   <li>409 {@code ALREADY_REPORTED} — caller has an open report on this group.</li>
 *   <li>409 {@code CANNOT_REPORT_OWN_GROUP} — caller is a member of the group.</li>
 *   <li>429 {@code REPORT_RATE_LIMITED} — daily quota exhausted.</li>
 * </ul>
 *
 * <p>The admin side mutation hooks (resolve / dismiss) live in
 * {@link ./useGroupReports} and invalidate the queue + detail queries.
 */
export function useSubmitGroupReport(groupPublicId: string) {
  return useMutation({
    mutationFn: (body: SubmitReportRequest) =>
      realtyGroupReportsApi.submit(groupPublicId, body),
  });
}
