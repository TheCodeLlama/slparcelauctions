import type { ReactNode } from "react";
import { Stepper } from "@/components/ui/Stepper";
import { cn } from "@/lib/cn";

export interface ListingWizardLayoutProps {
  /** When provided, renders a stepper above the title. Omit for single-step flows. */
  steps?: string[];
  currentIndex?: number;
  title: string;
  description?: ReactNode;
  /** Body content for the current step (form fields, review summary, etc.). */
  children: ReactNode;
  /** Rendered in a right-aligned footer above a top border — typically Back/Continue buttons. */
  footer: ReactNode;
  className?: string;
}

/**
 * Shared wizard frame for the listing flows. The optional Stepper renders
 * when {@code steps} is non-empty so single-step flows can reuse the same
 * frame without a one-step indicator.
 */
export function ListingWizardLayout({
  steps,
  currentIndex = 0,
  title,
  description,
  children,
  footer,
  className,
}: ListingWizardLayoutProps) {
  return (
    <div
      className={cn(
        "mx-auto flex w-full max-w-3xl flex-col gap-6 p-6",
        className,
      )}
    >
      {steps && steps.length > 0 && (
        <Stepper steps={steps} currentIndex={currentIndex} />
      )}
      <header className="flex flex-col gap-1">
        <h1 className="text-xl font-bold tracking-tight text-fg">{title}</h1>
        {description ? (
          <p className="text-sm text-fg-muted">{description}</p>
        ) : null}
      </header>
      <section className="flex flex-col gap-4">{children}</section>
      <footer className="flex justify-end gap-3 border-t border-border-subtle pt-4">
        {footer}
      </footer>
    </div>
  );
}
