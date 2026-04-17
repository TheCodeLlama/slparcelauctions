import type { ReactNode } from "react";
import type { LucideIcon } from "lucide-react";
import { cn } from "@/lib/cn";

type EmptyStateProps = {
  icon: LucideIcon;
  headline: string;
  description?: string;
  /** Optional slot for a CTA (e.g. primary-action button) below the copy. */
  children?: ReactNode;
  className?: string;
};

export function EmptyState({
  icon: Icon,
  headline,
  description,
  children,
  className,
}: EmptyStateProps) {
  return (
    <div
      className={cn(
        "flex flex-col items-center justify-center gap-3 py-12 text-center",
        className,
      )}
    >
      <Icon className="size-10 text-on-surface-variant" aria-hidden="true" />
      <h3 className="text-title-md text-on-surface">{headline}</h3>
      {description && (
        <p className="text-body-md text-on-surface-variant max-w-sm">
          {description}
        </p>
      )}
      {children && <div className="mt-2">{children}</div>}
    </div>
  );
}
