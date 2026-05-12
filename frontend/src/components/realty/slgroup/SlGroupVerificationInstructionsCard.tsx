"use client";

import { useState } from "react";
import { Card } from "@/components/ui/Card";
import { IconButton } from "@/components/ui/IconButton";
import { Check, Copy, ShieldCheck } from "@/components/ui/icons";

export interface SlGroupVerificationInstructionsCardProps {
  /**
   * The full verification code as returned by the backend, already prefixed
   * (e.g. {@code "SLPA-1A2B3C4D5E6F"}). Callers should pass
   * {@code pending.verificationCode} directly — the backend's
   * {@code SlGroupVerificationCodeGenerator} emits the {@code SLPA-} prefix
   * inline, so no additional prefixing happens here.
   */
  code: string;
}

/**
 * Realty Groups: E — verification instructions panel. Rendered inside the
 * Register modal immediately after the backend creates a pending
 * registration, and reused in any "show instructions again" affordance.
 *
 * Displays the {@code SLPA-XXXXXXXXXXXX} code with a clipboard button and
 * the founder-via-terminal verification instructions. Sub-project F retired
 * the About-text polling path; founder-terminal is now the only verification
 * method.
 *
 * Spec §6.2.
 */
export function SlGroupVerificationInstructionsCard({
  code,
}: SlGroupVerificationInstructionsCardProps) {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(code);
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
            {code}
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
          Complete the verification step below to activate this SL group
          registration.
        </p>

        <div className="flex flex-col gap-4">
          <div className="flex items-start gap-3">
            <ShieldCheck
              className="h-5 w-5 text-brand mt-0.5 shrink-0"
              aria-hidden="true"
            />
            <div>
              <h4 className="text-sm font-medium text-fg">
                Founder via terminal
              </h4>
              <p className="text-sm text-fg-muted mt-1">
                Hand{" "}
                <code className="font-mono text-xs text-fg">{code}</code>{" "}
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
