"use client";

import { ComboboxInput } from "@headlessui/react";
import { Search } from "@/components/ui/icons";

export interface SearchInputProps {
  value: string;
  onChange: (next: string) => void;
  autoFocus?: boolean;
}

export function SearchInput({
  value,
  onChange,
  autoFocus,
}: SearchInputProps) {
  return (
    <div className="flex items-center gap-2 px-3 h-12 border-b border-border bg-surface-raised">
      <Search className="size-4 text-fg-muted" aria-hidden />
      <ComboboxInput
        autoFocus={autoFocus}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="Search parcels, regions…"
        className="flex-1 bg-transparent text-sm text-fg placeholder:text-fg-muted focus:outline-none"
        autoComplete="off"
        spellCheck={false}
      />
    </div>
  );
}
