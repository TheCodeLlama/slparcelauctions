// frontend/src/components/auth/AuthCard.tsx
import type { ReactNode } from "react";
import { Card } from "@/components/ui/Card";

type AuthCardProps = { children: ReactNode };

/**
 * Compound layout component for the three auth pages (register, login,
 * forgot-password). Wraps the existing Card primitive in a centered container
 * with the SLPA brand header always rendered at the top.
 *
 * Compound subcomponents:
 *   <AuthCard.Title />     — h2 headline
 *   <AuthCard.Subtitle />  — p body text
 *   <AuthCard.Body />      — form container with space-y-6 rhythm
 *   <AuthCard.Footer />    — cross-link area with background-shift separator
 *
 * Why compound instead of flat props?
 *   - Matches the existing Card compound pattern (Card.Header / Card.Body / Card.Footer).
 *   - Forgot-password success state swap is the page's responsibility — the page
 *     conditionally renders ForgotPasswordForm vs. the success message inside
 *     AuthCard.Body without any prop-drilling into AuthCard.
 *   - Footer link styling is co-located in the Footer subcomponent, so callers
 *     never need to thread className props for the cross-link area.
 *
 * Brand header is hardcoded (not a slot). See spec §6.
 */
export function AuthCard({ children }: AuthCardProps) {
  return (
    <div className="mx-auto max-w-md px-4 py-12">
      {/* Brand header — always rendered, not a slot */}
      <div className="mb-8 text-center">
        <h1 className="text-2xl font-bold tracking-tight font-black uppercase tracking-tight text-fg">
          SLPA
        </h1>
        <p className="mt-2 text-[11px] font-medium uppercase tracking-widest text-fg-muted">
          The Digital Curator
        </p>
      </div>
      {/*
       * Inner content wrapper applies px-10 py-10 padding so that
       * AuthCard.Footer can use matching negative margins (-mx-10 -mb-10 px-10)
       * to bleed flush to the Card edges. The Card root has overflow-hidden,
       * which clips the footer's background exactly at the card boundary.
       *
       * CRITICAL: If you change the px-10 / py-10 here, you MUST update the
       * matching values in the Footer component below (-mx-10 -mb-10 px-10).
       * ONE WRONG VALUE and the footer either floats inward or overflows
       * the card horizontally. This is a load-bearing relationship.
       */}
      <Card>
        <div className="px-10 py-10">{children}</div>
      </Card>
    </div>
  );
}

function Title({ children }: { children: ReactNode }) {
  return (
    <h2 className="text-lg font-bold tracking-tight font-semibold text-fg">
      {children}
    </h2>
  );
}

function Subtitle({ children }: { children: ReactNode }) {
  return (
    <p className="mt-1 text-sm text-fg-muted">{children}</p>
  );
}

function Body({ children }: { children: ReactNode }) {
  return <div className="mt-6 space-y-6">{children}</div>;
}

function Footer({ children }: { children: ReactNode }) {
  // "No-Line Rule" (DESIGN.md §2): no border-t. Use a background shift instead.
  //
  // CRITICAL: The negative margins MUST match the inner content wrapper's
  // px-10 py-10 padding in AuthCard exactly. If that wrapper changes to
  // px-8 / py-8, change -mx-10 → -mx-8 and -mb-10 → -mb-8 and px-10 → px-8
  // here too. ONE WRONG VALUE and the footer either floats inward or overflows
  // the card horizontally. The Card root's overflow-hidden clips the background
  // flush at the card edge — this only works when the values match perfectly.
  return (
    <div className="mt-8 bg-bg-subtle -mx-10 -mb-10 px-10 py-6 text-center text-sm text-fg-muted">
      {children}
    </div>
  );
}

AuthCard.Title = Title;
AuthCard.Subtitle = Subtitle;
AuthCard.Body = Body;
AuthCard.Footer = Footer;
