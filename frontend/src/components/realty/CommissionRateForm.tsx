"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { useUpdatePermissions } from "@/hooks/realty/useRealtyGroups";
import { rateToPercentInput } from "@/lib/realty/commission";
import type { AgentCardDto } from "@/types/realty";
import { CommissionRateInput } from "./CommissionRateInput";

export interface CommissionRateFormProps {
  groupPublicId: string;
  member: AgentCardDto;
  onComplete?: () => void;
}

/**
 * Leader-only form rendered inside the members-tab "Commission rate" modal.
 * Pre-populates from the member's current rate (as a percentage string), then
 * submits via the existing per-member permissions endpoint — keeping
 * permissions as-is and patching only the rate.
 *
 * <p>The backend's {@code PATCH .../members/{memberPublicId}/permissions}
 * endpoint accepts both fields in one body; sending the existing permission
 * set + the new rate is the established path for a single-member rate edit
 * (the dedicated {@code /members/commission-rates} endpoint exists for
 * bulk-rate edits). Permissions are taken from {@code member.permissions} —
 * which the leader always sees in full — so no read-modify-write race
 * surface beyond what the existing combined endpoint already had.
 *
 * <p>Empty input is rejected before submit: this form is the dedicated
 * commission-rate surface, so the user must commit to a value (use 0 to
 * clear the commission). Explicit {@code 0} is a legal assignment.
 */
export function CommissionRateForm({
  groupPublicId,
  member,
  onComplete,
}: CommissionRateFormProps) {
  const mutate = useUpdatePermissions();
  const [rateInput, setRateInput] = useState<string>(() =>
    rateToPercentInput(member.agentCommissionRate),
  );
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    const trimmed = rateInput.trim();
    if (trimmed === "") {
      setError("Enter a rate (use 0 for no commission).");
      return;
    }
    const parsed = Number(trimmed);
    if (!Number.isFinite(parsed) || parsed < 0 || parsed > 100) {
      setError("Rate must be between 0 and 100.");
      return;
    }
    try {
      await mutate.mutateAsync({
        publicId: groupPublicId,
        memberPublicId: member.memberPublicId,
        body: {
          permissions: member.permissions ?? [],
          agentCommissionRate: parsed / 100,
        },
      });
      onComplete?.();
    } catch {
      // toast handled by mutation onError
    }
  }

  return (
    <form
      onSubmit={onSubmit}
      className="flex flex-col gap-3"
      aria-label={`Edit commission rate for ${member.displayName}`}
    >
      <CommissionRateInput
        value={rateInput}
        onChange={(v) => {
          setRateInput(v);
          if (error) setError(null);
        }}
        data-testid="commission-rate-input"
      />
      {error && (
        <p
          role="alert"
          className="text-xs text-danger"
          data-testid="commission-rate-error"
        >
          {error}
        </p>
      )}
      <div className="flex justify-end">
        <Button
          type="submit"
          variant="primary"
          loading={mutate.isPending}
          disabled={mutate.isPending}
          data-testid="commission-rate-submit"
        >
          Save commission rate
        </Button>
      </div>
    </form>
  );
}
