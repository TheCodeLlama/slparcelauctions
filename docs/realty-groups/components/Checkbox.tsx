// export/realty-groups/components/Checkbox.tsx
"use client";

import { Check } from "lucide-react";
import { cn } from "../lib/cn";

interface CheckboxProps {
  label: string;
  checked: boolean;
  onChange: () => void;
}

export function Checkbox({ label, checked, onChange }: CheckboxProps) {
  return (
    <label className="flex items-center gap-2 cursor-pointer text-sm text-fg">
      <span
        className={cn(
          "w-4 h-4 rounded grid place-items-center shrink-0 border-[1.5px] transition-colors",
          checked
            ? "border-brand bg-brand"
            : "border-brand bg-surface-raised",
        )}
      >
        {checked && <Check className="w-2.5 h-2.5 text-on-brand" strokeWidth={3} />}
      </span>
      <input
        type="checkbox"
        checked={checked}
        onChange={onChange}
        className="hidden"
      />
      {label}
    </label>
  );
}
