"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { adminResolveSupportTicket } from "@/lib/api/support";
import type { SupportTicketDto } from "@/types/support";

/**
 * Mutation wrapper around `POST /api/v1/admin/support-tickets/{publicId}/resolve`.
 * On success invalidates the single-ticket detail (status flips to RESOLVED),
 * the admin queue (status pill on the row flips), and the queue-stats badge
 * (resolving a ticket drops it from the open-needing-reply tally).
 */
export function useAdminSupportResolve(publicId: string) {
  const qc = useQueryClient();
  return useMutation<SupportTicketDto, Error, void>({
    mutationFn: () => adminResolveSupportTicket(publicId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["admin-support-ticket", publicId] });
      qc.invalidateQueries({ queryKey: ["admin-support-tickets"] });
      qc.invalidateQueries({ queryKey: ["admin-support-stats"] });
    },
  });
}
