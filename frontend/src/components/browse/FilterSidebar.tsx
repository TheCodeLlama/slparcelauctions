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
        "flex flex-col gap-4 border-r border-border-subtle bg-surface-raised p-4 overflow-y-auto sticky top-[calc(var(--header-h,0px)+16px)] h-fit",
        className,
      )}
    >
      {children}
    </aside>
  );
}
