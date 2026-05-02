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
    classes: "bg-info-bg text-info",
    label: "Active",
  },
  "ending-soon": {
    classes: "bg-danger-bg text-danger",
    label: "Ending Soon",
  },
  ended: {
    classes: "bg-bg-hover text-fg-muted",
    label: "Ended",
  },
  cancelled: {
    classes:
      "bg-bg-hover text-fg-muted line-through",
    label: "Cancelled",
  },
};

const toneClasses: Record<Tone, string> = {
  default: "bg-bg-hover text-fg-muted",
  success: "bg-success-bg text-success",
  warning: "bg-warning-bg text-warning",
  danger: "bg-danger-bg text-danger",
};

const baseClasses =
  "rounded-full px-3 py-1 text-xs font-medium inline-flex items-center gap-1.5";

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
