import type { ReactNode } from "react";
import { Stepper } from "@/components/ui/Stepper";
import { cn } from "@/lib/cn";

export interface ListingWizardLayoutProps {
  steps: string[];
  currentIndex: number;
  title: string;
  description?: ReactNode;
  /** Body content for the current step (form fields, review summary, etc.). */
  children: ReactNode;
  /** Rendered in a right-aligned footer above a top border — typically Back/Continue buttons. */
  footer: ReactNode;
  className?: string;
}

/**
 * Shared wizard frame for the Create / Edit / Activate listing pages. Pulls
 * the Stepper up to a single header location so all three flows stay
 * visually consistent and a Task 8 / 9 consumer doesn't have to re-lay-out
 * steps per-page.
 */
export function ListingWizardLayout({
  steps,
  currentIndex,
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
      <Stepper steps={steps} currentIndex={currentIndex} />
      <header className="flex flex-col gap-1">
        <h1 className="text-headline-md text-on-surface">{title}</h1>
        {description ? (
          <p className="text-body-md text-on-surface-variant">{description}</p>
        ) : null}
      </header>
      <section className="flex flex-col gap-4">{children}</section>
      <footer className="flex justify-end gap-3 border-t border-outline-variant pt-4">
        {footer}
      </footer>
    </div>
  );
}
