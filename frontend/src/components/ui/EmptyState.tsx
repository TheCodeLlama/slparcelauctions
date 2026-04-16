import type { LucideIcon } from "lucide-react";
import { cn } from "@/lib/cn";

type EmptyStateProps = {
  icon: LucideIcon;
  headline: string;
  description?: string;
  className?: string;
};

export function EmptyState({
  icon: Icon,
  headline,
  description,
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
    </div>
  );
}
