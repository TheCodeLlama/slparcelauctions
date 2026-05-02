import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

/**
 * Small uppercase tracking-wide brand-orange label rendered above section
 * headings on marketing surfaces. Pairs visually with {@link SectionHeading}.
 */
export function Eyebrow({
  children,
  className,
}: {
  children: ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "text-[11px] font-semibold uppercase tracking-[0.08em] text-brand",
        className
      )}
    >
      {children}
    </div>
  );
}
