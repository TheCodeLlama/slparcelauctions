import { useQuery } from "@tanstack/react-query";
import { adminApi } from "./api";
import { adminQueryKeys } from "./queryKeys";
import type { AdminAuditLogFilters } from "./auditLog";

export function useAuditLog(filters: AdminAuditLogFilters) {
  return useQuery({
    queryKey: adminQueryKeys.auditLogList(filters),
    queryFn: () => adminApi.auditLog.list(filters),
  });
}

export function useAuditLogExportUrl(filters: AdminAuditLogFilters) {
  return adminApi.auditLog.exportUrl(filters);
}
