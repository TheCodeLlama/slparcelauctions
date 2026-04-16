import { cn } from "@/lib/cn";

type LoadingSpinnerProps = { label?: string; className?: string };

export function LoadingSpinner({ label, className }: LoadingSpinnerProps) {
  return (
    <div
      role="status"
      aria-live="polite"
      className={cn(
        "flex flex-col items-center justify-center gap-3 py-8",
        className,
      )}
    >
      <div className="size-8 animate-spin rounded-full border-2 border-primary border-t-transparent" />
      {label && (
        <span className="text-body-sm text-on-surface-variant">{label}</span>
      )}
    </div>
  );
}
