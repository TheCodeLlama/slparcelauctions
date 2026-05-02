import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

/**
 * Section heading composition: large `<h2>` + optional sub copy + optional
 * right-side slot (typically a "View all" link). One source of truth for
 * spacing and typography on home / browse / dashboard sections.
 */
export function SectionHeading({
  title,
  sub,
  right,
  className,
}: {
  title: ReactNode;
  sub?: ReactNode;
  right?: ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "mb-5 flex items-end justify-between gap-4",
        className
      )}
    >
      <div>
        <h2 className="text-2xl font-bold tracking-tight text-fg">{title}</h2>
        {sub && (
          <div className="mt-1 text-sm text-fg-muted">{sub}</div>
        )}
      </div>
      {right && <div className="shrink-0">{right}</div>}
    </div>
  );
}
