import { X } from "@/components/ui/icons";
import { cn } from "@/lib/cn";

export interface ActiveFilterBadgeProps {
  label: string;
  onRemove: () => void;
  className?: string;
}

/**
 * Pill used in the ActiveFilters row above browse results. Clicking the X
 * clears the associated field from the current query via {@code onRemove}.
 * Kept presentation-only so the parent can translate each chip to the
 * appropriate field mutation.
 */
export function ActiveFilterBadge({
  label,
  onRemove,
  className,
}: ActiveFilterBadgeProps) {
  return (
    <span
      className={cn(
        "inline-flex items-center gap-2 rounded-full px-3 py-1 text-body-sm",
        "bg-surface-container-low text-on-surface-variant",
        className,
      )}
    >
      <span>{label}</span>
      <button
        type="button"
        onClick={onRemove}
        aria-label={`Remove filter: ${label}`}
        className="rounded-full p-0.5 hover:bg-surface-container-high focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary"
      >
        <X className="size-3.5" aria-hidden="true" />
      </button>
    </span>
  );
}
