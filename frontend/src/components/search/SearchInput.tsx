"use client";

import { ComboboxInput } from "@headlessui/react";
import { Search } from "@/components/ui/icons";

export interface SearchInputProps {
  value: string;
  onChange: (next: string) => void;
  /** Called on Enter when no row is highlighted (Headless UI default
   *  Enter handles row selection). The overlay routes to /browse?q=. */
  onBareEnter: () => void;
  autoFocus?: boolean;
}

export function SearchInput({
  value,
  onChange,
  onBareEnter,
  autoFocus,
}: SearchInputProps) {
  return (
    <div className="flex items-center gap-2 px-3 h-12 border-b border-border bg-surface-raised">
      <Search className="size-4 text-fg-muted" aria-hidden />
      <ComboboxInput
        autoFocus={autoFocus}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => {
          // Headless UI handles Enter for active option selection. If
          // the listbox has no active option (i.e. user typed but
          // didn't arrow into a row), Enter routes to /browse?q=.
          if (e.key === "Enter") {
            const target = e.currentTarget;
            if (!target.getAttribute("aria-activedescendant")) {
              e.preventDefault();
              onBareEnter();
            }
          }
        }}
        placeholder="Search parcels, regions…"
        className="flex-1 bg-transparent text-sm text-fg placeholder:text-fg-muted focus:outline-none"
        autoComplete="off"
        spellCheck={false}
      />
    </div>
  );
}
