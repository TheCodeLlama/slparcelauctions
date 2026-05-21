"use client";

import { useQuery } from "@tanstack/react-query";
import { fetchMySupportTicket } from "@/lib/api/support";
import type { SupportTicketDto } from "@/types/support";

export const mySupportTicketKey = (publicId: string) =>
  ["me-support-ticket", publicId] as const;

export function useMySupportTicket(publicId: string) {
  return useQuery<SupportTicketDto>({
    queryKey: mySupportTicketKey(publicId),
    queryFn: () => fetchMySupportTicket(publicId),
    enabled: !!publicId,
  });
}
