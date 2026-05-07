"use client";
import { useQuery } from "@tanstack/react-query";
import { adminApi } from "@/lib/admin/api";
import { adminQueryKeys } from "@/lib/admin/queryKeys";
import type { AdminLedgerFilters } from "@/lib/admin/types";

export function useAdminLedgerList(filters: AdminLedgerFilters) {
  return useQuery({
    queryKey: adminQueryKeys.ledgerList(filters),
    queryFn: () => adminApi.ledger.list(filters),
    staleTime: 5_000,
  });
}

/**
 * Typeahead-flavored wrapper over the existing admin users-list endpoint
 * (`GET /api/v1/admin/users?search=q&size=10`). Returns up to 10 matches
 * by username/displayName/SL UUID/publicId. The 200ms debounce should be
 * applied at the call site (the input component) so we don't fire a request
 * on every keystroke.
 */
export function useAdminUserTypeahead(query: string, enabled = true) {
  const trimmed = query.trim();
  return useQuery({
    queryKey: ["admin", "user-typeahead", trimmed],
    queryFn: () =>
      adminApi.users.search({ search: trimmed, page: 0, size: 10 }),
    enabled: enabled && trimmed.length >= 2,
    staleTime: 30_000,
  });
}
