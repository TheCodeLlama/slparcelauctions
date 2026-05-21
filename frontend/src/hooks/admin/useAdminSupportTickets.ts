"use client";

import { useQuery } from "@tanstack/react-query";
import {
  fetchAdminSupportTickets,
  type AdminListParams,
} from "@/lib/api/support";
import type { Page } from "@/types/page";
import type { AdminSupportTicketQueueRow } from "@/types/support";

/**
 * Query-key factory for the admin support queue list. Indexed by the
 * filter params so different views (status, assignee, last-author)
 * share no cache entry. Exposed so mutation hooks in Task 21 can
 * invalidate the same key without re-typing the literal.
 */
export const adminSupportTicketsKey = (params: AdminListParams) =>
  ["admin-support-tickets", params] as const;

export function useAdminSupportTickets(params: AdminListParams = {}) {
  return useQuery<Page<AdminSupportTicketQueueRow>>({
    queryKey: adminSupportTicketsKey(params),
    queryFn: () => fetchAdminSupportTickets(params),
  });
}
