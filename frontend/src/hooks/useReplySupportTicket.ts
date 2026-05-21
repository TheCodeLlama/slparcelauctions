"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { replySupportTicket } from "@/lib/api/support";
import type {
  ReplySupportTicketRequest,
  SupportTicketMessageDto,
} from "@/types/support";

/**
 * Mutation wrapper around `POST /api/v1/me/support-tickets/{publicId}/messages`.
 * On success invalidates both the single-ticket detail query (so the new
 * message appears in the thread) and the list query (so the row's
 * lastMessageAt / lastMessageAuthor reflect the user's reply).
 */
export function useReplySupportTicket(publicId: string) {
  const qc = useQueryClient();
  return useMutation<
    SupportTicketMessageDto,
    Error,
    ReplySupportTicketRequest
  >({
    mutationFn: (req) => replySupportTicket(publicId, req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["me-support-ticket", publicId] });
      qc.invalidateQueries({ queryKey: ["me-support-tickets"] });
    },
  });
}
