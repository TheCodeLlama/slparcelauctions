"use client";

import { useId, type ReactNode } from "react";
import { Input } from "@/components/ui/Input";
import { cn } from "@/lib/cn";
import type {
  AuctionDurationHours,
  AuctionSnipeWindowMin,
} from "@/types/auction";

/**
 * Controlled state for the starting-bid / reserve / buy-now / duration /
 * snipe block. Kept as a single interface so the Create and Edit pages
 * can persist exactly what the seller has typed (including partial / null
 * optional prices) without mapping through a nested form shape.
 */
export interface AuctionSettingsValue {
  startingBid: number;
  reservePrice: number | null;
  buyNowPrice: number | null;
  durationHours: AuctionDurationHours;
  snipeProtect: boolean;
  snipeWindowMin: AuctionSnipeWindowMin | null;
}

export interface AuctionSettingsFormProps {
  value: AuctionSettingsValue;
  onChange: (value: AuctionSettingsValue) => void;
  /**
   * Server-sourced field errors (e.g., from a 400 ProblemDetail.errors map).
   * Client-side cross-field rules compute their own errors inline; the
   * server map is used only when the computed rule doesn't fire.
   */
  errors?: Record<string, string | undefined>;
  disabled?: boolean;
}

const DURATIONS: { value: AuctionDurationHours; label: string }[] = [
  { value: 24, label: "24 hours" },
  { value: 48, label: "48 hours" },
  { value: 72, label: "72 hours" },
  { value: 168, label: "7 days" },
  { value: 336, label: "14 days" },
];

const SNIPE_WINDOWS: { value: AuctionSnipeWindowMin; label: string }[] = [
  { value: 5, label: "5 minutes" },
  { value: 10, label: "10 minutes" },
  { value: 15, label: "15 minutes" },
  { value: 30, label: "30 minutes" },
  { value: 60, label: "60 minutes" },
];

const SELECT_CLASSES =
  "h-12 w-full rounded-lg bg-bg-subtle text-fg px-4 ring-1 ring-transparent transition-all focus:bg-surface-raised focus:outline-none focus:ring-2 focus:ring-brand disabled:opacity-60";

export function AuctionSettingsForm({
  value,
  onChange,
  errors = {},
  disabled = false,
}: AuctionSettingsFormProps) {
  const update = <K extends keyof AuctionSettingsValue>(
    key: K,
    next: AuctionSettingsValue[K],
  ) => onChange({ ...value, [key]: next });

  // Cross-field rules (spec §4.1): reserve >= starting, buy-now >= max.
  // Computed-live so the UI gives instant feedback; the backend re-checks
  // on submit. We deliberately ignore the server-side map for these two
  // fields when the computed rule fires — the live message is more useful.
  const reserveError =
    value.reservePrice != null && value.reservePrice < value.startingBid
      ? `Reserve must be at least the starting bid (L$${value.startingBid}).`
      : errors.reservePrice;

  const buyNowMin = Math.max(
    value.startingBid,
    value.reservePrice ?? 0,
  );
  const buyNowError =
    value.buyNowPrice != null && value.buyNowPrice < buyNowMin
      ? `Buy-it-now must be at least L$${buyNowMin}.`
      : errors.buyNowPrice;

  return (
    <div className="flex flex-col gap-4">
      <Input
        type="number"
        label="Starting bid (L$)"
        min={1}
        value={value.startingBid}
        disabled={disabled}
        error={errors.startingBid}
        onChange={(e) => update("startingBid", Number(e.target.value))}
      />
      <Input
        type="number"
        label="Reserve price (L$)"
        min={0}
        helperText="Optional. Minimum price for the sale to close."
        value={value.reservePrice ?? ""}
        disabled={disabled}
        error={reserveError}
        onChange={(e) =>
          update(
            "reservePrice",
            e.target.value === "" ? null : Number(e.target.value),
          )
        }
      />
      <Input
        type="number"
        label="Buy-it-now price (L$)"
        min={0}
        helperText="Optional. Any bidder can end the auction at this price."
        value={value.buyNowPrice ?? ""}
        disabled={disabled}
        error={buyNowError}
        onChange={(e) =>
          update(
            "buyNowPrice",
            e.target.value === "" ? null : Number(e.target.value),
          )
        }
      />

      <LabeledField
        label="Duration"
        error={errors.durationHours}
        render={(id) => (
          <select
            id={id}
            className={SELECT_CLASSES}
            value={value.durationHours}
            disabled={disabled}
            onChange={(e) =>
              update(
                "durationHours",
                Number(e.target.value) as AuctionDurationHours,
              )
            }
          >
            {DURATIONS.map((d) => (
              <option key={d.value} value={d.value}>
                {d.label}
              </option>
            ))}
          </select>
        )}
      />

      <SnipeProtectToggle
        checked={value.snipeProtect}
        disabled={disabled}
        onChange={(checked) => {
          onChange({
            ...value,
            snipeProtect: checked,
            // Default the extension window to 10m when the seller turns
            // protection on; backend validation requires a non-null
            // snipeWindowMin whenever snipeProtect is true.
            snipeWindowMin: checked ? value.snipeWindowMin ?? 10 : null,
          });
        }}
      />

      {value.snipeProtect && (
        <LabeledField
          label="Extension window"
          helperText="How much time each last-minute bid adds to the clock."
          error={errors.snipeWindowMin}
          render={(id) => (
            <select
              id={id}
              className={SELECT_CLASSES}
              value={value.snipeWindowMin ?? 10}
              disabled={disabled}
              onChange={(e) =>
                update(
                  "snipeWindowMin",
                  Number(e.target.value) as AuctionSnipeWindowMin,
                )
              }
            >
              {SNIPE_WINDOWS.map((w) => (
                <option key={w.value} value={w.value}>
                  {w.label}
                </option>
              ))}
            </select>
          )}
        />
      )}
    </div>
  );
}

function LabeledField({
  label,
  helperText,
  error,
  render,
}: {
  label: string;
  helperText?: string;
  error?: string;
  /**
   * Render-prop style keeps the htmlFor/id association correct: the
   * label's target must be the native <select>/<input>, not a wrapping
   * div (which testing-library correctly flags as non-labellable).
   */
  render: (id: string) => ReactNode;
}) {
  const id = useId();
  return (
    <div className="flex w-full flex-col gap-1">
      <label
        htmlFor={id}
        className="text-xs font-medium text-fg-muted"
      >
        {label}
      </label>
      {render(id)}
      {error ? (
        <span className="text-xs text-danger">{error}</span>
      ) : helperText ? (
        <span className="text-xs text-fg-muted">
          {helperText}
        </span>
      ) : null}
    </div>
  );
}

/**
 * Simple accessible switch built on a native checkbox so react-testing-library
 * users can target it via getByRole("switch") / getByLabelText.
 */
function SnipeProtectToggle({
  checked,
  disabled,
  onChange,
}: {
  checked: boolean;
  disabled?: boolean;
  onChange: (checked: boolean) => void;
}) {
  const id = useId();
  return (
    <div className="flex items-start gap-3">
      <input
        id={id}
        type="checkbox"
        role="switch"
        checked={checked}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
        className={cn(
          "peer h-5 w-5 appearance-none rounded border-2 border-on-surface-variant/40",
          "bg-surface-raised transition-colors",
          "checked:border-brand checked:bg-brand",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand",
          "disabled:opacity-60",
        )}
      />
      <label htmlFor={id} className="flex flex-col gap-1 text-sm text-fg">
        <span className="font-medium">Snipe protection</span>
        <span className="text-xs text-fg-muted">
          Extend the auction when someone bids near the end.
        </span>
      </label>
    </div>
  );
}
