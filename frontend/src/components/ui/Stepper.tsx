import { CheckCircle2 } from "./icons";
import { cn } from "@/lib/cn";

export type StepperProps = {
  steps: string[];
  currentIndex: number;
  className?: string;
};

type StepState = "complete" | "current" | "upcoming";

/**
 * Horizontal step indicator used by the listing wizard and activate flow.
 *
 * Each step is rendered as an <li> inside an <ol> with:
 *   - data-state="complete" | "current" | "upcoming"
 *   - aria-current="step" on the active step (for a11y and testability)
 *
 * Visual styling uses project design tokens (primary / tertiary-container /
 * on-surface-variant / outline) rather than raw Tailwind palette classes so
 * the component respects light/dark theme switches.
 */
export function Stepper({ steps, currentIndex, className }: StepperProps) {
  return (
    <ol
      className={cn(
        "flex items-center gap-2 overflow-x-auto",
        className,
      )}
    >
      {steps.map((label, idx) => {
        const state: StepState =
          idx < currentIndex
            ? "complete"
            : idx === currentIndex
              ? "current"
              : "upcoming";
        return (
          <li
            key={label}
            data-state={state}
            aria-current={state === "current" ? "step" : undefined}
            className={cn(
              "flex items-center gap-2 text-label-lg",
              state === "complete" && "text-on-tertiary-container",
              state === "current" && "text-primary",
              state === "upcoming" && "text-on-surface-variant",
            )}
          >
            <span
              className={cn(
                "inline-flex h-6 w-6 items-center justify-center rounded-full border text-label-md",
                state === "complete" &&
                  "border-tertiary bg-tertiary text-on-tertiary",
                state === "current" && "border-primary text-primary",
                state === "upcoming" &&
                  "border-outline-variant text-on-surface-variant",
              )}
            >
              {state === "complete" ? (
                <CheckCircle2 className="size-3.5" aria-hidden="true" />
              ) : (
                idx + 1
              )}
            </span>
            <span>{label}</span>
            {idx < steps.length - 1 && (
              <span
                className="mx-1 h-px w-6 bg-outline-variant"
                aria-hidden="true"
              />
            )}
          </li>
        );
      })}
    </ol>
  );
}
