"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { adminReopenSupportTicket } from "@/lib/api/support";
import type { SupportTicketDto } from "@/types/support";

/**
 * Mutation wrapper around `POST /api/v1/admin/support-tickets/{publicId}/reopen`.
 * On success invalidates the detail, queue, and stats badge so the status
 * pill and the open-counter all reflect the reopen instantly.
 */
export function useAdminSupportReopen(publicId: string) {
  const qc = useQueryClient();
  return useMutation<SupportTicketDto, Error, void>({
    mutationFn: () => adminReopenSupportTicket(publicId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["admin-support-ticket", publicId] });
      qc.invalidateQueries({ queryKey: ["admin-support-tickets"] });
      qc.invalidateQueries({ queryKey: ["admin-support-stats"] });
    },
  });
}
