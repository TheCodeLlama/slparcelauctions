"use client";
import { useState, useEffect, useRef } from "react";
import { ChevronDown } from "@/components/ui/icons";
import type { AuctionStatus } from "@/lib/admin/types";

const ALL_STATUSES: AuctionStatus[] = [
  "DRAFT", "DRAFT_PAID", "VERIFICATION_PENDING", "VERIFICATION_FAILED",
  "ACTIVE", "ENDED", "ESCROW_PENDING", "ESCROW_FUNDED",
  "TRANSFER_PENDING", "COMPLETED", "CANCELLED", "EXPIRED",
  "DISPUTED", "SUSPENDED",
];

type Props = {
  search: string;
  onSearchChange: (search: string) => void;
  statuses: AuctionStatus[];
  onStatusesChange: (statuses: AuctionStatus[]) => void;
  hasReserve: boolean | null;
  onHasReserveChange: (v: boolean | null) => void;
  showStatusFilter: boolean;
  onReset: () => void;
  isDirty: boolean;
};

export function ListingsFilterBar({
  search,
  onSearchChange,
  statuses,
  onStatusesChange,
  hasReserve,
  onHasReserveChange,
  showStatusFilter,
  onReset,
  isDirty,
}: Props) {
  const [searchInput, setSearchInput] = useState(search);

  useEffect(() => { setSearchInput(search); }, [search]);

  // 300ms debounce
  useEffect(() => {
    const t = setTimeout(() => {
      if (searchInput !== search) onSearchChange(searchInput);
    }, 300);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchInput]);

  return (
    <div className="flex flex-wrap items-center gap-2" data-testid="listings-filter-bar">
      <input
        type="search"
        placeholder="Search title or seller…"
        value={searchInput}
        onChange={(e) => setSearchInput(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Escape") setSearchInput("");
          if (e.key === "Enter") onSearchChange(searchInput);
        }}
        data-testid="listings-search-input"
        className="min-w-64 flex-1 max-w-md rounded-lg bg-bg-muted px-3 py-2 text-sm text-fg placeholder:text-fg-muted ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand"
      />

      {showStatusFilter && (
        <StatusMultiSelect statuses={statuses} onChange={onStatusesChange} />
      )}

      <ReserveCycle value={hasReserve} onChange={onHasReserveChange} />

      {isDirty && (
        <button
          type="button"
          onClick={onReset}
          data-testid="listings-reset-filters"
          className="text-[11px] text-fg-muted hover:text-fg underline"
        >
          Reset
        </button>
      )}
    </div>
  );
}

function StatusMultiSelect({
  statuses,
  onChange,
}: {
  statuses: AuctionStatus[];
  onChange: (s: AuctionStatus[]) => void;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    function onDocClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onDocClick);
    return () => document.removeEventListener("mousedown", onDocClick);
  }, [open]);

  const label = statuses.length === 0
    ? "All statuses"
    : `${statuses.length} status${statuses.length === 1 ? "" : "es"}`;

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        data-testid="listings-status-trigger"
        className="inline-flex items-center gap-1.5 px-3 py-2 text-sm rounded-lg bg-bg-muted ring-1 ring-border-subtle text-fg hover:bg-bg-hover"
      >
        Status: {label}
        <ChevronDown className="size-3.5" aria-hidden="true" />
      </button>
      {open && (
        <div
          role="menu"
          data-testid="listings-status-menu"
          className="absolute left-0 top-full mt-1 z-30 w-64 rounded-lg bg-bg-subtle border border-border-subtle shadow-md p-2 max-h-80 overflow-y-auto"
        >
          {ALL_STATUSES.map((s) => {
            const checked = statuses.includes(s);
            return (
              <label
                key={s}
                className="flex items-center gap-2 px-2 py-1 text-[12px] text-fg hover:bg-bg-muted rounded cursor-pointer"
              >
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={(e) => {
                    if (e.target.checked) onChange([...statuses, s]);
                    else onChange(statuses.filter((x) => x !== s));
                  }}
                  data-testid={`listings-status-${s}`}
                />
                <span>{s}</span>
              </label>
            );
          })}
          {statuses.length > 0 && (
            <button
              type="button"
              onClick={() => onChange([])}
              className="w-full mt-1 text-[11px] text-fg-muted hover:text-fg underline py-1"
            >
              Clear status filter
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function ReserveCycle({
  value,
  onChange,
}: {
  value: boolean | null;
  onChange: (v: boolean | null) => void;
}) {
  function next() {
    if (value === null) onChange(true);
    else if (value === true) onChange(false);
    else onChange(null);
  }
  const label = value === null ? "Either" : value ? "Has reserve" : "No reserve";
  return (
    <button
      type="button"
      onClick={next}
      data-testid="listings-reserve-cycle"
      className="inline-flex items-center gap-1.5 px-3 py-2 text-sm rounded-lg bg-bg-muted ring-1 ring-border-subtle text-fg hover:bg-bg-hover"
    >
      Reserve: <span className="font-medium">{label}</span>
    </button>
  );
}
