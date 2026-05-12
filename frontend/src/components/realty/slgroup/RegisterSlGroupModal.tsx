"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { Modal } from "@/components/ui/Modal";
import { isApiError } from "@/lib/api";
import type { RealtyGroupSlGroup } from "@/types/realty";
import { useRegisterSlGroup } from "@/hooks/realty/useRegisterSlGroup";
import { SlGroupVerificationInstructionsCard } from "./SlGroupVerificationInstructionsCard";

export interface RegisterSlGroupModalProps {
  open: boolean;
  onClose: () => void;
  /** Realty group public UUID. */
  groupPublicId: string;
}

/**
 * Realty Groups: E — "Register new SL group" modal. Two stages:
 *
 *  1. Input — the leader pastes an SL group UUID. Submit fires the
 *     {@code POST /api/v1/realty/groups/{publicId}/sl-groups} endpoint via
 *     {@link useRegisterSlGroup}.
 *  2. Instructions — on a 201 response we render
 *     {@link SlGroupVerificationInstructionsCard} inline with the returned
 *     verification code and the founder-via-terminal instructions. The row
 *     also appears in the page table via TanStack invalidation in
 *     {@link useRegisterSlGroup}.
 *
 * Error handling:
 *  - 409 {@code SL_GROUP_ALREADY_REGISTERED} → inline "already registered"
 *    message.
 *  - 422 / world API failure → inline error message with the backend's
 *    {@code detail}/{@code title}.
 *  - All other errors → inline message from the error's {@code message}.
 *
 * Spec §6.2.
 */
export function RegisterSlGroupModal({
  open,
  onClose,
  groupPublicId,
}: RegisterSlGroupModalProps) {
  const [uuid, setUuid] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [registered, setRegistered] = useState<RealtyGroupSlGroup | null>(null);
  const mutation = useRegisterSlGroup(groupPublicId);

  const handleClose = () => {
    setUuid("");
    setError(null);
    setRegistered(null);
    mutation.reset();
    onClose();
  };

  const handleSubmit = async () => {
    const trimmed = uuid.trim();
    if (!trimmed) {
      setError("Enter an SL group UUID.");
      return;
    }
    setError(null);
    try {
      const row = await mutation.mutateAsync(trimmed);
      setRegistered(row);
    } catch (e) {
      if (isApiError(e)) {
        const code = e.problem.code as string | undefined;
        if (code === "SL_GROUP_ALREADY_REGISTERED") {
          setError(
            "This SL group is already registered on the realty group.",
          );
        } else if (code === "INSUFFICIENT_GROUP_PERMISSION") {
          setError(
            "You do not have permission to register SL groups on this realty group.",
          );
        } else {
          // 422 invalid UUID / SL world API failure / other
          const detail =
            (e.problem.detail as string | undefined) ??
            (e.problem.title as string | undefined) ??
            e.message;
          setError(detail);
        }
      } else {
        setError(
          e instanceof Error ? e.message : "Registration failed. Please try again.",
        );
      }
    }
  };

  // Footer differs between stages.
  const footer = registered ? (
    <Button
      variant="primary"
      onClick={handleClose}
      data-testid="register-done-button"
    >
      Done
    </Button>
  ) : (
    <>
      <Button
        variant="secondary"
        onClick={handleClose}
        disabled={mutation.isPending}
      >
        Cancel
      </Button>
      <Button
        variant="primary"
        onClick={handleSubmit}
        loading={mutation.isPending}
        data-testid="register-submit-button"
      >
        Register
      </Button>
    </>
  );

  return (
    <Modal
      open={open}
      title="Register SL Group"
      onClose={handleClose}
      footer={footer}
    >
      {!registered && (
        <div className="flex flex-col gap-3" data-testid="register-input-stage">
          <p className="text-sm text-fg-muted">
            Enter the UUID of the SL group you want to register. After
            registration you will receive a verification code to confirm
            ownership through your group&apos;s founder.
          </p>
          <Input
            type="text"
            value={uuid}
            onChange={(e) => setUuid(e.target.value)}
            placeholder="00000000-0000-0000-0000-000000000000"
            aria-label="SL group UUID"
            label="SL group UUID"
            error={error ?? undefined}
            data-testid="register-uuid-input"
          />
        </div>
      )}

      {registered && registered.pending && (
        <div
          className="flex flex-col gap-3"
          data-testid="register-instructions-stage"
        >
          <p className="text-sm text-fg-muted">
            Registration created. Complete the verification step below to
            activate this SL group.
          </p>
          <SlGroupVerificationInstructionsCard
            code={registered.pending.verificationCode}
          />
        </div>
      )}
    </Modal>
  );
}
