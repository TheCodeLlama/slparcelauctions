"use client";

export interface MasterMuteRowProps {
  value: boolean;
  onChange: (next: boolean) => void;
}

export function MasterMuteRow({ value, onChange }: MasterMuteRowProps) {
  return (
    <div className="flex items-center justify-between py-4 border-b border-outline-variant mb-4">
      <div>
        <div className="text-title-sm font-semibold text-on-surface">
          Mute all SL IM notifications
        </div>
        <div className="text-body-sm text-on-surface-variant mt-1">
          Master switch — overrides everything below.
        </div>
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={value}
        onClick={() => onChange(!value)}
        className={
          "relative inline-flex h-6 w-11 items-center rounded-full transition-colors " +
          (value ? "bg-primary" : "bg-surface-container-high border border-outline")
        }
        aria-label="Mute all SL IM notifications"
      >
        <span
          className={
            "inline-block h-4 w-4 transform rounded-full bg-on-primary transition-transform " +
            (value ? "translate-x-6" : "translate-x-1")
          }
        />
      </button>
    </div>
  );
}
