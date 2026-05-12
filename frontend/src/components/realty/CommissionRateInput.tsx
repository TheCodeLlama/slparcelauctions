"use client";

import { useId } from "react";

export interface CommissionRateInputProps {
  /**
   * Percentage value as a free-form string ("10", "12.5", ""). Held as a
   * string so the user can edit freely without the parent re-clamping mid
   * keystroke. Parent converts to a 0..1 decimal on submit.
   */
  value: string;
  onChange: (next: string) => void;
  disabled?: boolean;
  "data-testid"?: string;
}

/**
 * Per-member agent-commission-rate input used by the leader's invite and
 * edit-permissions forms. The rate is the agent's slice of group-listing
 * earnings after platform commission — see Realty Groups: E spec §6.3.
 *
 * Value is presented as a percentage (10 = 10%) for human authoring; the
 * wire format is a 0..1 decimal. Parent forms own the conversion at
 * submit time so the displayed string never lags the underlying state.
 *
 * Empty value → backend leaves the rate unchanged (omit the field). An
 * explicit "0" → backend assigns 0% (group keeps full earnings).
 */
export function CommissionRateInput({
  value,
  onChange,
  disabled,
  "data-testid": dataTestId,
}: CommissionRateInputProps) {
  const id = useId();
  return (
    <div className="flex flex-col gap-1">
      <label
        htmlFor={id}
        className="text-xs font-medium text-fg-muted"
      >
        Agent commission rate
      </label>
      <div className="relative">
        <input
          id={id}
          type="number"
          inputMode="decimal"
          min={0}
          max={100}
          step="0.01"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          disabled={disabled}
          placeholder="0"
          data-testid={dataTestId}
          className="h-10 w-full rounded-lg bg-bg-subtle text-fg placeholder:text-fg-muted pl-4 pr-8 ring-1 ring-transparent transition-all focus:bg-surface-raised focus:outline-none focus:ring-brand"
        />
        <span
          className="absolute right-3 top-1/2 -translate-y-1/2 text-sm text-fg-muted pointer-events-none"
          aria-hidden="true"
        >
          %
        </span>
      </div>
      <p className="text-xs text-fg-muted">
        Their share of group-listing earnings after platform commission.
        Leave blank to keep the current rate.
      </p>
    </div>
  );
}
