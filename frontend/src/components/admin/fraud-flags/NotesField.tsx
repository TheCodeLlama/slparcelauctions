"use client";
import { cn } from "@/lib/cn";

const MAX = 1000;

type Props = {
  value: string;
  onChange: (val: string) => void;
  disabled?: boolean;
};

export function NotesField({ value, onChange, disabled }: Props) {
  const over = value.length > MAX;
  return (
    <div className="flex flex-col gap-1">
      <label className="text-label-md text-on-surface font-medium">
        Admin notes <span className="text-error">*</span>
      </label>
      <textarea
        rows={4}
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value.slice(0, MAX))}
        placeholder="Required before resolving this flag."
        data-testid="notes-field"
        className="w-full resize-y rounded-default bg-surface-container-low px-4 py-3 text-on-surface placeholder:text-on-surface-variant ring-1 ring-outline-variant transition-all focus:outline-none focus:ring-primary disabled:opacity-50"
      />
      <div
        className={cn(
          "self-end text-label-sm",
          over ? "text-error" : "text-on-surface-variant"
        )}
      >
        {value.length} / {MAX}
      </div>
    </div>
  );
}
