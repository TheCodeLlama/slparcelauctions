"use client";
import { useState, useEffect, useRef } from "react";
import { ChevronDown } from "@/components/ui/icons";
import { UserTypeahead } from "./UserTypeahead";
import type { AdminLedgerKind } from "@/lib/admin/types";

const ALL_KINDS: AdminLedgerKind[] = [
  "USER_LEDGER", "ESCROW_TXN", "TERMINAL_CMD", "WITHDRAWAL", "BID_RESERVATION",
];

const REF_TYPES = [
  "ESCROW", "AUCTION", "WITHDRAWAL", "BID",
  "LISTING_FEE_REFUND", "TERMINAL_COMMAND",
  "PENALTY", "ADJUSTMENT", "DORMANCY",
];

const ENTRY_TYPES_BY_KIND: Record<AdminLedgerKind, string[]> = {
  USER_LEDGER: [
    "DEPOSIT", "WITHDRAW_QUEUED", "WITHDRAW_COMPLETED", "WITHDRAW_REVERSED",
    "BID_RESERVED", "BID_RELEASED",
    "ESCROW_DEBIT", "ESCROW_REFUND",
    "LISTING_FEE_DEBIT", "LISTING_FEE_REFUND",
    "PENALTY_DEBIT", "ADJUSTMENT",
  ],
  ESCROW_TXN: [
    "AUCTION_ESCROW_PAYMENT", "AUCTION_ESCROW_PAYOUT",
    "AUCTION_ESCROW_REFUND", "AUCTION_ESCROW_COMMISSION",
    "LISTING_FEE_PAYMENT", "LISTING_FEE_REFUND",
    "LISTING_PENALTY_PAYMENT",
  ],
  TERMINAL_CMD: ["AUCTION_ESCROW", "LISTING_FEE_REFUND", "ADMIN_WITHDRAWAL", "WALLET_WITHDRAWAL"],
  WITHDRAWAL: ["PENDING", "IN_FLIGHT", "COMPLETED", "FAILED", "CANCELLED"],
  BID_RESERVATION: ["RESERVED", "RELEASED"],
};

type Props = {
  search: string;
  onSearchChange: (s: string) => void;
  kinds: AdminLedgerKind[];
  onKindsChange: (k: AdminLedgerKind[]) => void;
  selectedUser: { publicId: string; label: string } | null;
  onUserChange: (sel: { publicId: string; label: string } | null) => void;
  entryType: string | null;
  onEntryTypeChange: (s: string | null) => void;
  refType: string | null;
  onRefTypeChange: (s: string | null) => void;
  refId: number | null;
  onRefIdChange: (n: number | null) => void;
  dateFrom: string;
  dateTo: string;
  onDateFromChange: (s: string) => void;
  onDateToChange: (s: string) => void;
  amountMin: number | null;
  amountMax: number | null;
  onAmountMinChange: (n: number | null) => void;
  onAmountMaxChange: (n: number | null) => void;
  onReset: () => void;
  isDirty: boolean;
};

export function AdminLedgerFilterBar(props: Props) {
  const [searchInput, setSearchInput] = useState(props.search);
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- URL search param is the external source of truth; mirroring it into local input state on URL change (back/forward, Reset) is the point of this effect.
    setSearchInput(props.search);
  }, [props.search]);
  useEffect(() => {
    const t = setTimeout(() => {
      if (searchInput !== props.search) props.onSearchChange(searchInput);
    }, 300);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchInput]);

  const dateRangeInvalid =
    props.dateFrom && props.dateTo && new Date(props.dateFrom) > new Date(props.dateTo);

  const allowedEntryTypes =
    props.kinds.length === 1 ? ENTRY_TYPES_BY_KIND[props.kinds[0]] : null;

  return (
    <div className="flex flex-col gap-2" data-testid="ledger-filter-bar">
      <div className="flex flex-wrap items-center gap-2">
        <input
          type="search"
          placeholder="Search description / sl txn / idempotency / terminal id…"
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Escape") setSearchInput("");
            if (e.key === "Enter") props.onSearchChange(searchInput);
          }}
          data-testid="ledger-search-input"
          className="min-w-72 flex-1 max-w-md rounded-lg bg-bg-muted px-3 py-2 text-sm text-fg placeholder:text-fg-muted ring-1 ring-border-subtle focus:outline-none focus:ring-2 focus:ring-brand"
        />

        <KindsMultiSelect kinds={props.kinds} onChange={props.onKindsChange} />

        <UserTypeahead selected={props.selectedUser ?? undefined} onSelect={props.onUserChange} />

        <select
          value={props.entryType ?? ""}
          disabled={!allowedEntryTypes}
          onChange={(e) => props.onEntryTypeChange(e.target.value || null)}
          title={allowedEntryTypes ? "" : "Select exactly one kind to enable entry-type filter"}
          data-testid="ledger-entry-type"
          className="rounded-lg bg-bg-muted px-3 py-2 text-sm text-fg ring-1 ring-border-subtle disabled:opacity-50"
        >
          <option value="">All entry types</option>
          {(allowedEntryTypes ?? []).map((t) => (
            <option key={t} value={t}>{t}</option>
          ))}
        </select>

        {props.isDirty && (
          <button
            type="button"
            onClick={props.onReset}
            data-testid="ledger-reset-filters"
            className="text-[11px] text-fg-muted hover:text-fg underline"
          >
            Reset
          </button>
        )}
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <label className="text-[11px] text-fg-muted flex items-center gap-1">
          From:
          <input
            type="datetime-local"
            value={props.dateFrom}
            onChange={(e) => props.onDateFromChange(e.target.value)}
            data-testid="ledger-date-from"
            className={`rounded bg-bg-muted px-2 py-1 text-fg ring-1 text-[12px] ${dateRangeInvalid ? "ring-danger" : "ring-border-subtle"}`}
          />
        </label>
        <label className="text-[11px] text-fg-muted flex items-center gap-1">
          To:
          <input
            type="datetime-local"
            value={props.dateTo}
            onChange={(e) => props.onDateToChange(e.target.value)}
            data-testid="ledger-date-to"
            className={`rounded bg-bg-muted px-2 py-1 text-fg ring-1 text-[12px] ${dateRangeInvalid ? "ring-danger" : "ring-border-subtle"}`}
          />
        </label>
        {dateRangeInvalid && (
          <span className="text-[11px] text-danger">From must be before To</span>
        )}
        <label className="text-[11px] text-fg-muted flex items-center gap-1">
          Amt min:
          <input
            type="number"
            value={props.amountMin ?? ""}
            onChange={(e) => props.onAmountMinChange(e.target.value ? Number(e.target.value) : null)}
            data-testid="ledger-amount-min"
            className="w-24 rounded bg-bg-muted px-2 py-1 text-fg ring-1 ring-border-subtle text-[12px]"
          />
        </label>
        <label className="text-[11px] text-fg-muted flex items-center gap-1">
          Amt max:
          <input
            type="number"
            value={props.amountMax ?? ""}
            onChange={(e) => props.onAmountMaxChange(e.target.value ? Number(e.target.value) : null)}
            data-testid="ledger-amount-max"
            className="w-24 rounded bg-bg-muted px-2 py-1 text-fg ring-1 ring-border-subtle text-[12px]"
          />
        </label>
        <select
          value={props.refType ?? ""}
          onChange={(e) => {
            const v = e.target.value || null;
            props.onRefTypeChange(v);
            if (!v) props.onRefIdChange(null);
          }}
          data-testid="ledger-ref-type"
          className="rounded-lg bg-bg-muted px-3 py-1 text-fg ring-1 ring-border-subtle text-[12px]"
        >
          <option value="">Any refType</option>
          {REF_TYPES.map((t) => (<option key={t} value={t}>{t}</option>))}
        </select>
        <input
          type="number"
          placeholder="refId"
          value={props.refId ?? ""}
          disabled={!props.refType}
          onChange={(e) => props.onRefIdChange(e.target.value ? Number(e.target.value) : null)}
          data-testid="ledger-ref-id"
          className="w-24 rounded bg-bg-muted px-2 py-1 text-fg ring-1 ring-border-subtle text-[12px] disabled:opacity-50"
        />
      </div>
    </div>
  );
}

function KindsMultiSelect({
  kinds,
  onChange,
}: {
  kinds: AdminLedgerKind[];
  onChange: (k: AdminLedgerKind[]) => void;
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

  const label = kinds.length === 0 ? "All kinds" : `${kinds.length} kind${kinds.length === 1 ? "" : "s"}`;

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        data-testid="ledger-kinds-trigger"
        className="inline-flex items-center gap-1.5 px-3 py-2 text-sm rounded-lg bg-bg-muted ring-1 ring-border-subtle text-fg hover:bg-bg-hover"
      >
        Kinds: {label}
        <ChevronDown className="size-3.5" aria-hidden="true" />
      </button>
      {open && (
        <div className="absolute left-0 top-full mt-1 z-30 w-56 rounded-lg bg-bg-subtle border border-border-subtle shadow-md p-2">
          {ALL_KINDS.map((k) => {
            const checked = kinds.includes(k);
            return (
              <label
                key={k}
                className="flex items-center gap-2 px-2 py-1 text-[12px] text-fg hover:bg-bg-muted rounded cursor-pointer"
              >
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={(e) => {
                    if (e.target.checked) onChange([...kinds, k]);
                    else onChange(kinds.filter((x) => x !== k));
                  }}
                  data-testid={`ledger-kind-checkbox-${k}`}
                />
                <span>{k}</span>
              </label>
            );
          })}
          {kinds.length > 0 && (
            <button
              type="button"
              onClick={() => onChange([])}
              className="w-full mt-1 text-[11px] text-fg-muted hover:text-fg underline py-1"
            >
              Clear
            </button>
          )}
        </div>
      )}
    </div>
  );
}
