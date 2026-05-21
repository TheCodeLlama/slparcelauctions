"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { adminAssignSupportTicket } from "@/lib/api/support";
import type { SupportTicketDto } from "@/types/support";

/**
 * Mutation wrapper around `POST /api/v1/admin/support-tickets/{publicId}/assign`.
 *
 * The mutation variable is the admin publicId to assign, or `null` to
 * unassign the ticket entirely. The detail / queue / badge caches all
 * invalidate so the "Assigned to: ..." line and the queue's "Assignee"
 * column refresh in-place.
 */
export function useAdminSupportAssign(publicId: string) {
  const qc = useQueryClient();
  return useMutation<SupportTicketDto, Error, string | null>({
    mutationFn: (adminPublicId) =>
      adminAssignSupportTicket(publicId, adminPublicId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["admin-support-ticket", publicId] });
      qc.invalidateQueries({ queryKey: ["admin-support-tickets"] });
      qc.invalidateQueries({ queryKey: ["admin-support-stats"] });
    },
  });
}
