"use client";

import type {
  AdminAuditLogFilters as Filters,
  AdminActionType,
  AdminActionTargetType,
} from "@/lib/admin/auditLog";
import {
  ACTION_TYPE_LABELS,
  labelFor,
} from "@/lib/admin/action-type-labels";

// Sourced from ACTION_TYPE_LABELS so this filter stays in sync with the
// backend AdminActionType enum (sub-project G §15) -- any future enum value
// added to the label map automatically appears here.
const ACTION_TYPES: AdminActionType[] = (
  Object.keys(ACTION_TYPE_LABELS) as AdminActionType[]
).sort((a, b) => labelFor(a).localeCompare(labelFor(b)));

const TARGET_TYPES: AdminActionTargetType[] = [
  "REPORT", "BAN", "USER", "AUCTION", "FRAUD_FLAG",
  "DISPUTE", "WITHDRAWAL", "TERMINAL_SECRET",
];

type Props = {
  filters: Filters;
  onChange: (next: Filters) => void;
  onDownloadCsv: () => void;
};

export function AdminAuditLogFilters({ filters, onChange, onDownloadCsv }: Props) {
  return (
    <div className="flex gap-2 flex-wrap items-center bg-bg-subtle p-3 rounded">
      <select
        value={filters.actionType ?? ""}
        onChange={(e) =>
          onChange({
            ...filters,
            actionType: (e.target.value || undefined) as AdminActionType | undefined,
          })
        }
        className="px-2 py-1 bg-bg-muted text-xs rounded"
      >
        <option value="">All actions</option>
        {ACTION_TYPES.map((t) => (
          <option key={t} value={t}>
            {labelFor(t)}
          </option>
        ))}
      </select>
      <select
        value={filters.targetType ?? ""}
        onChange={(e) =>
          onChange({
            ...filters,
            targetType: (e.target.value || undefined) as AdminActionTargetType | undefined,
          })
        }
        className="px-2 py-1 bg-bg-muted text-xs rounded"
      >
        <option value="">All target types</option>
        {TARGET_TYPES.map((t) => (
          <option key={t} value={t}>
            {t}
          </option>
        ))}
      </select>
      <input
        type="number"
        placeholder="Admin user ID"
        value={filters.adminUserId ?? ""}
        onChange={(e) =>
          onChange({
            ...filters,
            adminUserId: e.target.value ? Number(e.target.value) : undefined,
          })
        }
        className="px-2 py-1 bg-bg-muted text-xs rounded w-32"
      />
      <input
        type="datetime-local"
        value={filters.from ?? ""}
        onChange={(e) =>
          onChange({ ...filters, from: e.target.value || undefined })
        }
        className="px-2 py-1 bg-bg-muted text-xs rounded"
      />
      <input
        type="datetime-local"
        value={filters.to ?? ""}
        onChange={(e) =>
          onChange({ ...filters, to: e.target.value || undefined })
        }
        className="px-2 py-1 bg-bg-muted text-xs rounded"
      />
      <input
        type="text"
        placeholder="Search notes..."
        value={filters.q ?? ""}
        onChange={(e) =>
          onChange({ ...filters, q: e.target.value || undefined })
        }
        className="px-2 py-1 bg-bg-muted text-xs rounded flex-1 min-w-[180px]"
      />
      <button
        type="button"
        onClick={onDownloadCsv}
        className="px-3 py-1.5 border border-border rounded text-xs"
      >
        ↓ Download CSV
      </button>
    </div>
  );
}
