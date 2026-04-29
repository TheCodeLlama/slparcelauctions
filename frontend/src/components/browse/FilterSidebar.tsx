"use client";
import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

export interface FilterSidebarProps {
  children: ReactNode;
  className?: string;
}

/**
 * Desktop fixed-left filter rail. The mobile trigger button lives in
 * {@link ResultsHeader} instead — the sidebar itself is hidden on small
 * screens via the caller's {@code className} (typically
 * {@code hidden md:flex}).
 */
export function FilterSidebar({ children, className }: FilterSidebarProps) {
  return (
    <aside
      aria-label="Filters"
      className={cn(
        "flex flex-col gap-4 border-r border-outline-variant bg-surface-container-lowest p-4 overflow-y-auto",
        className,
      )}
    >
      {children}
    </aside>
  );
}
