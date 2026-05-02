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
      <label className="text-xs font-medium text-fg">
        Admin notes <span className="text-danger-flat">*</span>
      </label>
      <textarea
        rows={4}
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value.slice(0, MAX))}
        placeholder="Required before resolving this flag."
        data-testid="notes-field"
        className="w-full resize-y rounded-lg bg-bg-subtle px-4 py-3 text-fg placeholder:text-fg-muted ring-1 ring-border-subtle transition-all focus:outline-none focus:ring-2 focus:ring-brand disabled:opacity-50"
      />
      <div
        className={cn(
          "self-end text-[11px] font-medium",
          over ? "text-danger-flat" : "text-fg-muted"
        )}
      >
        {value.length} / {MAX}
      </div>
    </div>
  );
}
