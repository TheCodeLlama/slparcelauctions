"use client";
import { useState, type ReactNode } from "react";
import { ChevronDown, ChevronUp } from "@/components/ui/icons";
import { cn } from "@/lib/cn";

export interface FilterSectionProps {
  title: string;
  children: ReactNode;
  /** Defaults to open. */
  defaultOpen?: boolean;
  className?: string;
}

/**
 * Collapsible filter group used inside the browse sidebar. Header is
 * uppercase + bold per spec §7.1. Presentation-only — the parent owns
 * the filter state; this component only controls visibility.
 */
export function FilterSection({
  title,
  children,
  defaultOpen = true,
  className,
}: FilterSectionProps) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className={cn("border-b border-border-subtle pb-4 mb-4 flex flex-col", className)}>
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="flex items-center justify-between text-xs font-semibold uppercase tracking-wide text-fg-subtle mb-2"
      >
        <span>{title}</span>
        {open ? (
          <ChevronUp className="size-4" aria-hidden="true" />
        ) : (
          <ChevronDown className="size-4" aria-hidden="true" />
        )}
      </button>
      {open && <div className="flex flex-col space-y-2">{children}</div>}
    </div>
  );
}
