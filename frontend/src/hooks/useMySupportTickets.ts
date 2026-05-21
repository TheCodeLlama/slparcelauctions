"use client";

import { useQuery } from "@tanstack/react-query";
import {
  fetchMySupportTickets,
  type MyListParams,
} from "@/lib/api/support";
import type { Page } from "@/types/page";
import type { SupportTicketSummaryDto } from "@/types/support";

/**
 * Query-key factory for the authenticated user's own support tickets.
 * Indexed by the filter params so different list views (e.g. "open" vs.
 * "all") share no cache entry.
 *
 * Exposed so mutation hooks (`useCreateSupportTicket`,
 * `useReplySupportTicket`) can invalidate without re-typing the literal.
 */
export const mySupportTicketsKey = (params: MyListParams = {}) =>
  ["me-support-tickets", params] as const;

export function useMySupportTickets(params: MyListParams = {}) {
  return useQuery<Page<SupportTicketSummaryDto>>({
    queryKey: mySupportTicketsKey(params),
    queryFn: () => fetchMySupportTickets(params),
  });
}
