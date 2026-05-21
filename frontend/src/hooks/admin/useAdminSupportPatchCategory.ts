"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { adminPatchSupportTicket } from "@/lib/api/support";
import type {
  SupportTicketCategory,
  SupportTicketDto,
} from "@/types/support";

/**
 * Mutation wrapper around `PATCH /api/v1/admin/support-tickets/{publicId}`.
 * Backend currently only accepts a `category` field on this endpoint
 * (re-routing tickets between Account/Bidding/Listing/Escrow/Wallet/Other);
 * we expose just that single argument here.
 */
export function useAdminSupportPatchCategory(publicId: string) {
  const qc = useQueryClient();
  return useMutation<SupportTicketDto, Error, SupportTicketCategory>({
    mutationFn: (category) => adminPatchSupportTicket(publicId, category),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["admin-support-ticket", publicId] });
      qc.invalidateQueries({ queryKey: ["admin-support-tickets"] });
      qc.invalidateQueries({ queryKey: ["admin-support-stats"] });
    },
  });
}
