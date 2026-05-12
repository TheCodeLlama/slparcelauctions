"use client";

import { useState } from "react";
import { Card } from "@/components/ui/Card";
import { IconButton } from "@/components/ui/IconButton";
import { Check, Copy, MessageSquare, ShieldCheck } from "@/components/ui/icons";

export interface SlGroupVerificationInstructionsCardProps {
  /**
   * The 12-character base32 verification code (e.g. {@code "1A2B3C4D5E6F"}).
   * The {@code SLPA-} prefix is added by the component so callers can supply
   * the raw {@code pending.verificationCode} field unchanged.
   */
  code: string;
}

/**
 * Realty Groups: E — verification instructions panel. Rendered inside the
 * Register modal immediately after the backend creates a pending
 * registration, and reused in any "show instructions again" affordance.
 *
 * Displays the {@code SLPA-XXXXXXXXXXXX} code with a clipboard button and
 * the two acceptable verification paths:
 *
 *  1. About text — paste the code into the SL group's About field. The
 *     backend poller sweeps every 5 minutes; the "Check now" button in the
 *     parent row triggers an on-demand recheck.
 *  2. Founder via terminal — the SL group's founder taps any SLPA terminal,
 *     picks "SL Group Verify", and types the code.
 *
 * Spec §6.2.
 */
export function SlGroupVerificationInstructionsCard({
  code,
}: SlGroupVerificationInstructionsCardProps) {
  const fullCode = `SLPA-${code}`;
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(fullCode);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Non-fatal; the code is also visible on screen for manual copy.
    }
  };

  return (
    <Card data-testid="sl-group-instructions-card">
      <Card.Body>
        <div className="flex items-center gap-2 mb-3">
          <code
            className="flex-1 font-mono text-base font-semibold text-fg bg-bg-subtle rounded px-3 py-2 select-all"
            data-testid="verification-code-display"
          >
            {fullCode}
          </code>
          <IconButton
            aria-label="Copy verification code"
            variant="secondary"
            size="md"
            onClick={handleCopy}
            data-testid="copy-code-button"
          >
            {copied ? <Check /> : <Copy />}
          </IconButton>
        </div>

        <p className="text-xs text-fg-muted mb-4">
          Complete one of the two verification steps below. The registration
          activates as soon as either path succeeds.
        </p>

        <div className="flex flex-col gap-4">
          <div className="flex items-start gap-3">
            <MessageSquare
              className="h-5 w-5 text-brand mt-0.5 shrink-0"
              aria-hidden="true"
            />
            <div>
              <h4 className="text-sm font-medium text-fg">
                Option 1 — About text
              </h4>
              <p className="text-sm text-fg-muted mt-1">
                Set your SL group&apos;s About text to include{" "}
                <code className="font-mono text-xs text-fg">{fullCode}</code>.
                SLParcels rechecks every 5 minutes. Click &quot;Check now&quot;
                next to the row in the table to poll immediately.
              </p>
            </div>
          </div>

          <div className="flex items-start gap-3">
            <ShieldCheck
              className="h-5 w-5 text-brand mt-0.5 shrink-0"
              aria-hidden="true"
            />
            <div>
              <h4 className="text-sm font-medium text-fg">
                Option 2 — Founder via terminal
              </h4>
              <p className="text-sm text-fg-muted mt-1">
                Hand{" "}
                <code className="font-mono text-xs text-fg">{fullCode}</code>{" "}
                to your SL group&apos;s founder. They step onto any SLParcels
                terminal, choose &quot;SL Group Verify&quot;, and type the
                code.
              </p>
            </div>
          </div>
        </div>
      </Card.Body>
    </Card>
  );
}
