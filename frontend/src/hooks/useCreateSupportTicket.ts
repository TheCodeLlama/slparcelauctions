"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { createSupportTicket } from "@/lib/api/support";
import type {
  CreateSupportTicketRequest,
  SupportTicketDto,
} from "@/types/support";

/**
 * Mutation wrapper around `POST /api/v1/me/support-tickets`. On success
 * invalidates the user's own ticket list and navigates to the new
 * ticket's thread page.
 *
 * On failure the caller reads `error.problem.code` (a
 * `SupportTicketErrorCode`) to render the right inline message.
 */
export function useCreateSupportTicket() {
  const router = useRouter();
  const qc = useQueryClient();
  return useMutation<SupportTicketDto, Error, CreateSupportTicketRequest>({
    mutationFn: (req) => createSupportTicket(req),
    onSuccess: (ticket) => {
      qc.invalidateQueries({ queryKey: ["me-support-tickets"] });
      router.push(`/support/${ticket.publicId}`);
    },
  });
}
