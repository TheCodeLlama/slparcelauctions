import type { HTMLAttributes, ReactNode } from "react";
import { cn } from "@/lib/cn";

type AuctionStatus = "active" | "ending-soon" | "ended" | "cancelled";
type Tone = "default" | "success" | "warning" | "danger";

type StatusBadgeProps = {
  status?: AuctionStatus;
  tone?: Tone;
  children?: ReactNode;
  className?: string;
} & Omit<HTMLAttributes<HTMLSpanElement>, "children" | "className">;

const statusConfig: Record<
  AuctionStatus,
  { classes: string; label: string }
> = {
  active: {
    classes: "bg-tertiary-container text-on-tertiary-container",
    label: "Active",
  },
  "ending-soon": {
    classes: "bg-error-container text-on-error-container",
    label: "Ending Soon",
  },
  ended: {
    classes: "bg-surface-container-high text-on-surface-variant",
    label: "Ended",
  },
  cancelled: {
    classes:
      "bg-surface-container-high text-on-surface-variant line-through",
    label: "Cancelled",
  },
};

const toneClasses: Record<Tone, string> = {
  default: "bg-surface-container-high text-on-surface-variant",
  success: "bg-tertiary-container text-on-tertiary-container",
  warning: "bg-secondary-container text-on-secondary-container",
  danger: "bg-error-container text-on-error-container",
};

const baseClasses =
  "rounded-full px-3 py-1 text-label-md font-medium inline-flex items-center gap-1.5";

export function StatusBadge({
  status,
  tone,
  children,
  className,
  ...rest
}: StatusBadgeProps) {
  if (!status && !tone && !children) return null;

  const palette = status
    ? statusConfig[status].classes
    : tone
      ? toneClasses[tone]
      : toneClasses.default;

  const content = children ?? (status ? statusConfig[status].label : null);

  return (
    <span className={cn(baseClasses, palette, className)} {...rest}>
      {content}
    </span>
  );
}
