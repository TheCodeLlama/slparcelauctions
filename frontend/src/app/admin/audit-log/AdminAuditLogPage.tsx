"use client";

import { useState } from "react";
import { AdminAuditLogFilters } from "./AdminAuditLogFilters";
import { AdminAuditLogTable } from "./AdminAuditLogTable";
import { useAuditLog, useAuditLogExportUrl } from "@/lib/admin/auditLogHooks";
import type { AdminAuditLogFilters as Filters } from "@/lib/admin/auditLog";

export function AdminAuditLogPage() {
  const [filters, setFilters] = useState<Filters>({});
  const { data, isLoading, error } = useAuditLog(filters);
  const exportUrl = useAuditLogExportUrl(filters);

  const handleDownload = () => {
    const a = document.createElement("a");
    a.href = exportUrl;
    a.download = "";
    a.click();
  };

  if (isLoading) return <p>Loading…</p>;
  if (error) return <p className="text-danger">Failed to load audit log</p>;

  return (
    <div className="space-y-4">
      <header>
        <h1 className="text-xl font-semibold">Admin audit log</h1>
        <p className="text-xs opacity-60 mt-1">
          Every admin action — fraud-flag triage, reports, bans, disputes,
          withdrawals, secret rotations.
        </p>
      </header>
      <AdminAuditLogFilters
        filters={filters}
        onChange={setFilters}
        onDownloadCsv={handleDownload}
      />
      <AdminAuditLogTable rows={data?.content ?? []} />
      {data && (
        <div className="flex justify-between text-xs opacity-60 border-t border-border-subtle pt-3">
          <span>
            Showing {data.content.length} of {data.totalElements}
          </span>
        </div>
      )}
    </div>
  );
}
