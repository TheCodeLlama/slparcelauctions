"use client";

import { useEffect, useRef, useState, type RefObject } from "react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { cn } from "@/lib/cn";
import type { LedgerFilter, UserLedgerEntryType } from "@/types/wallet";

/**
 * Subset of {@link UserLedgerEntryType} surfaced as quick-filter chips.
 *
 * The "Withdrawal" chip filters by {@code WITHDRAW_QUEUED} only — the
 * collapsed-view backend hides {@code WITHDRAW_COMPLETED} and
 * {@code WITHDRAW_REVERSED} rows entirely; one chip covers the full
 * withdrawal lifecycle.
 */
const CHIP_TYPES: { type: UserLedgerEntryType; label: string }[] = [
  { type: "DEPOSIT", label: "Deposit" },
  { type: "WITHDRAW_QUEUED", label: "Withdrawal" },
  { type: "BID_RESERVED", label: "Bid reserved" },
  { type: "BID_RELEASED", label: "Bid released" },
  { type: "ESCROW_DEBIT", label: "Escrow funded" },
  { type: "ESCROW_REFUND", label: "Escrow refund" },
  { type: "LISTING_FEE_DEBIT", label: "Listing fee" },
  { type: "LISTING_FEE_REFUND", label: "Listing refund" },
  { type: "PENALTY_DEBIT", label: "Penalty" },
];

export interface LedgerFilterBarProps {
  filter: LedgerFilter;
  onChange: (next: LedgerFilter) => void;
  onExport: () => void;
}

/**
 * Convert a {@code <input type="date">} value (always a local-calendar
 * `YYYY-MM-DD` string) to an ISO-8601 instant suitable for the backend
 * `from` filter. Returns the start of that day in UTC. Empty input
 * returns {@code undefined} so the filter clears.
 *
 * Backend filters are ISO instants on `created_at`; the date picker is
 * deliberately calendar-day-resolution so the UI always passes a clean
 * midnight boundary regardless of the user's TZ.
 */
function dateToIsoFrom(value: string): string | undefined {
  if (!value) return undefined;
  // value is YYYY-MM-DD; treat as start-of-day UTC.
  return new Date(`${value}T00:00:00.000Z`).toISOString();
}

/**
 * Companion to {@link dateToIsoFrom} for the upper bound. Backend `to`
 * is exclusive on `created_at`, so "to: 2026-04-30" means "everything up
 * to but not including May 1 00:00 UTC" — we send the next-day midnight.
 */
function dateToIsoTo(value: string): string | undefined {
  if (!value) return undefined;
  const d = new Date(`${value}T00:00:00.000Z`);
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString();
}

/**
 * Reverse of {@link dateToIsoFrom}/{@link dateToIsoTo} for hydrating the
 * date input from URL state. Returns the local-calendar UTC date portion
 * of the ISO instant. For `to`, subtracts a day so the displayed picker
 * value matches what the user originally selected (since we stored it as
 * the exclusive upper bound).
 */
function isoToDateInput(iso: string | undefined, isExclusiveUpper: boolean): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (!Number.isFinite(d.getTime())) return "";
  if (isExclusiveUpper) d.setUTCDate(d.getUTCDate() - 1);
  return d.toISOString().slice(0, 10);
}

/**
 * Filter chrome for the wallet ledger view: chip toggles for entry type,
 * date-range pickers, amount-range inputs (debounced 300 ms so typing
 * doesn't fire a request per keystroke), and Reset / Export CSV actions.
 *
 * Filter state lives in the parent — every change calls {@link onChange}
 * with the new {@link LedgerFilter}. The parent then mirrors that into
 * URL search params so filters survive refresh and are shareable.
 */
export function LedgerFilterBar({
  filter,
  onChange,
  onExport,
}: LedgerFilterBarProps) {
  // Local state for amount inputs so we can debounce — the persisted
  // filter only updates 300 ms after the user stops typing.
  //
  // The displayed input string is reset to track the parent filter
  // whenever the filter's amount changes externally (Reset, navigation,
  // restored from URL). We do this by comparing the parent value against
  // a "last seen" snapshot during render — React's idiomatic alternative
  // to setState-in-effect, see
  // https://react.dev/reference/react/useState#storing-information-from-previous-renders
  const minProp =
    filter.amountMin !== undefined ? String(filter.amountMin) : "";
  const maxProp =
    filter.amountMax !== undefined ? String(filter.amountMax) : "";
  const [minStr, setMinStr] = useState<string>(minProp);
  const [maxStr, setMaxStr] = useState<string>(maxProp);
  const [lastMinProp, setLastMinProp] = useState<string>(minProp);
  const [lastMaxProp, setLastMaxProp] = useState<string>(maxProp);
  if (minProp !== lastMinProp) {
    setLastMinProp(minProp);
    setMinStr(minProp);
  }
  if (maxProp !== lastMaxProp) {
    setLastMaxProp(maxProp);
    setMaxStr(maxProp);
  }

  const minTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const maxTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    const minTimerSnap = minTimer;
    const maxTimerSnap = maxTimer;
    return () => {
      if (minTimerSnap.current) clearTimeout(minTimerSnap.current);
      if (maxTimerSnap.current) clearTimeout(maxTimerSnap.current);
    };
  }, []);

  const toggleChip = (t: UserLedgerEntryType) => {
    const current = filter.entryTypes ?? [];
    const next = current.includes(t)
      ? current.filter((x) => x !== t)
      : [...current, t];
    onChange({
      ...filter,
      entryTypes: next.length === 0 ? undefined : next,
    });
  };

  const handleFromChange = (raw: string) => {
    onChange({ ...filter, from: dateToIsoFrom(raw) });
  };
  const handleToChange = (raw: string) => {
    onChange({ ...filter, to: dateToIsoTo(raw) });
  };

  const scheduleAmountChange = (
    field: "amountMin" | "amountMax",
    raw: string,
    timerRef: RefObject<ReturnType<typeof setTimeout> | null>,
  ) => {
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => {
      const trimmed = raw.trim();
      if (trimmed === "") {
        onChange({ ...filter, [field]: undefined });
        return;
      }
      const n = parseInt(trimmed, 10);
      if (!Number.isFinite(n) || n < 0) {
        onChange({ ...filter, [field]: undefined });
        return;
      }
      onChange({ ...filter, [field]: n });
    }, 300);
  };

  const reset = () => {
    setMinStr("");
    setMaxStr("");
    onChange({});
  };

  const selected = new Set(filter.entryTypes ?? []);

  return (
    <div className="bg-surface-container rounded-2xl p-4 flex flex-col gap-4">
      <h3 className="text-sm font-medium text-on-surface">Filter activity</h3>

      <div>
        <div className="text-xs uppercase tracking-wide text-on-surface-variant mb-2">
          Type
        </div>
        <div className="flex flex-wrap gap-2">
          {CHIP_TYPES.map(({ type, label }) => {
            const isSelected = selected.has(type);
            return (
              <button
                key={type}
                type="button"
                onClick={() => toggleChip(type)}
                aria-pressed={isSelected}
                className={cn(
                  "h-8 px-3 rounded-full text-label-md transition-colors",
                  isSelected
                    ? "bg-primary-container text-on-primary-container"
                    : "border border-outline-variant text-on-surface-variant hover:bg-surface-container-low",
                )}
              >
                {label}
              </button>
            );
          })}
        </div>
      </div>

      <div className="flex flex-wrap gap-4">
        <div className="flex-1 min-w-[180px]">
          <Input
            type="date"
            label="From"
            value={isoToDateInput(filter.from, false)}
            onChange={(e) => handleFromChange(e.target.value)}
            aria-label="Filter from date"
          />
        </div>
        <div className="flex-1 min-w-[180px]">
          <Input
            type="date"
            label="To"
            value={isoToDateInput(filter.to, true)}
            onChange={(e) => handleToChange(e.target.value)}
            aria-label="Filter to date"
          />
        </div>
        <div className="flex-1 min-w-[140px]">
          <Input
            type="number"
            inputMode="numeric"
            min={0}
            label="Min L$"
            value={minStr}
            onChange={(e) => {
              setMinStr(e.target.value);
              scheduleAmountChange("amountMin", e.target.value, minTimer);
            }}
            aria-label="Minimum amount in L$"
          />
        </div>
        <div className="flex-1 min-w-[140px]">
          <Input
            type="number"
            inputMode="numeric"
            min={0}
            label="Max L$"
            value={maxStr}
            onChange={(e) => {
              setMaxStr(e.target.value);
              scheduleAmountChange("amountMax", e.target.value, maxTimer);
            }}
            aria-label="Maximum amount in L$"
          />
        </div>
      </div>

      <div className="flex gap-3 justify-end">
        <Button variant="tertiary" size="sm" onClick={reset}>
          Reset
        </Button>
        <Button variant="secondary" size="sm" onClick={onExport}>
          Export CSV
        </Button>
      </div>
    </div>
  );
}
