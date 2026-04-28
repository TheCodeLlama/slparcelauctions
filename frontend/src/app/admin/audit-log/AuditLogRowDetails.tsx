"use client";

import type { AdminAuditLogRow } from "@/lib/admin/auditLog";

export function AuditLogRowDetails({ row }: { row: AdminAuditLogRow }) {
  return (
    <div className="bg-surface-container-low rounded p-3 my-2">
      <div className="text-[10px] uppercase opacity-55 mb-2">Details</div>
      <pre className="text-[10.5px] leading-relaxed bg-surface-container p-2 rounded overflow-x-auto">
{JSON.stringify(row.details, null, 2)}
      </pre>
    </div>
  );
}
