"use client";
import { cn } from "@/lib/cn";

export interface GroupToggleRowProps {
  group: string;
  label: string;
  subtext: string;
  value: boolean;
  mutedDisabled: boolean;
  onChange: (next: boolean) => void;
}

export function GroupToggleRow({
  group, label, subtext, value, mutedDisabled, onChange
}: GroupToggleRowProps) {
  const handleClick = () => {
    if (mutedDisabled) return;  // no-op when muted; preserve underlying state
    onChange(!value);
  };

  return (
    <div className="flex items-center justify-between py-4 border-b border-outline-variant last:border-0">
      <div className="flex-1 mr-4">
        <div className="text-body-md font-medium text-on-surface">{label}</div>
        <div className="text-body-sm text-on-surface-variant mt-0.5">{subtext}</div>
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={value}
        disabled={mutedDisabled}
        onClick={handleClick}
        className={cn(
          "relative inline-flex h-6 w-11 items-center rounded-full transition-colors",
          value ? "bg-primary" : "bg-surface-container-high border border-outline",
          mutedDisabled && "opacity-50 cursor-not-allowed"
        )}
        aria-label={`${label} — ${value ? "on" : "off"}${mutedDisabled ? " (muted)" : ""}`}
        data-testid={`group-toggle-${group}`}
      >
        <span
          className={cn(
            "inline-block h-4 w-4 transform rounded-full bg-on-primary transition-transform",
            value ? "translate-x-6" : "translate-x-1"
          )}
        />
      </button>
    </div>
  );
}
