"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { adminReplySupportTicket } from "@/lib/api/support";
import type {
  AdminReplyRequest,
  SupportTicketMessageDto,
} from "@/types/support";

/**
 * Mutation wrapper around `POST /api/v1/admin/support-tickets/{publicId}/messages`.
 *
 * On success invalidates:
 *   - the single-ticket detail query (so the new message appears),
 *   - the admin queue list (so the row's lastMessage* timestamps refresh),
 *   - the queue-stats badge (a public admin reply flips
 *     `lastMessageAuthor=ADMIN`, which decrements the "needs admin reply"
 *     counter; internal notes do not, but invalidating is cheap and
 *     guarantees correctness without a server-shape coupling here).
 */
export function useAdminSupportReply(publicId: string) {
  const qc = useQueryClient();
  return useMutation<SupportTicketMessageDto, Error, AdminReplyRequest>({
    mutationFn: (req) => adminReplySupportTicket(publicId, req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["admin-support-ticket", publicId] });
      qc.invalidateQueries({ queryKey: ["admin-support-tickets"] });
      qc.invalidateQueries({ queryKey: ["admin-support-stats"] });
    },
  });
}
