"use client";

import { useQuery } from "@tanstack/react-query";
import { fetchAdminSupportTicket } from "@/lib/api/support";
import type { SupportTicketDto } from "@/types/support";

/**
 * Query key for the admin-side single ticket detail. Exposed so the
 * mutation hooks below can invalidate it without re-typing the literal.
 */
export const adminSupportTicketKey = (publicId: string) =>
  ["admin-support-ticket", publicId] as const;

export function useAdminSupportTicket(publicId: string) {
  return useQuery<SupportTicketDto>({
    queryKey: adminSupportTicketKey(publicId),
    queryFn: () => fetchAdminSupportTicket(publicId),
    enabled: !!publicId,
  });
}
