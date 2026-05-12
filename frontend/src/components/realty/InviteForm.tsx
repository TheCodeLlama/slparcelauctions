"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { useInvite } from "@/hooks/realty/useRealtyGroups";
import { ALL_PERMISSIONS } from "@/lib/realty/permissions";
import type { RealtyGroupPermission } from "@/types/realty";
import { CommissionRateInput } from "./CommissionRateInput";
import { PermissionToggleRow } from "./PermissionToggleRow";

export interface InviteFormProps {
  groupPublicId: string;
  /** Called after the mutation succeeds — closes the parent modal. */
  onComplete?: () => void;
}

/**
 * Form rendered inside the invitations-tab "Send invitation" modal.
 *
 * Caller-supplied username + permission toggles (all default off — leader
 * must opt in per spec §4.3). Submit fires {@link useInvite}; the
 * mutation hook owns the success/error toast. On success the parent
 * modal is dismissed via {@link InviteFormProps.onComplete}.
 */
export function InviteForm({ groupPublicId, onComplete }: InviteFormProps) {
  const invite = useInvite();
  const [username, setUsername] = useState("");
  const [permissions, setPermissions] = useState<Set<RealtyGroupPermission>>(
    () => new Set(),
  );
  // Commission rate held as a percentage string. Blank → omit the field
  // on invite (backend default = 0). Explicit "0" → 0 (legal, no
  // commission). See {@link CommissionRateInput}.
  const [rateInput, setRateInput] = useState<string>("");
  const [touched, setTouched] = useState(false);

  function toggle(p: RealtyGroupPermission, on: boolean) {
    setPermissions((prev) => {
      const next = new Set(prev);
      if (on) next.add(p);
      else next.delete(p);
      return next;
    });
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setTouched(true);
    if (!username.trim()) return;
    const trimmed = rateInput.trim();
    let agentCommissionRate: number | undefined;
    if (trimmed !== "") {
      const parsed = Number(trimmed);
      if (Number.isFinite(parsed)) {
        agentCommissionRate = parsed / 100;
      }
    }
    try {
      await invite.mutateAsync({
        publicId: groupPublicId,
        body: {
          invitedUsername: username.trim(),
          permissions: Array.from(permissions),
          ...(agentCommissionRate !== undefined
            ? { agentCommissionRate }
            : {}),
        },
      });
      setUsername("");
      setPermissions(new Set());
      setRateInput("");
      setTouched(false);
      onComplete?.();
    } catch {
      // toast handled by mutation onError
    }
  }

  const usernameError =
    touched && !username.trim() ? "Username is required" : undefined;

  return (
    <form
      onSubmit={onSubmit}
      className="flex flex-col gap-4"
      aria-label="Send invitation"
    >
      <Input
        label="Username"
        placeholder="e.g. resident-name"
        value={username}
        onChange={(e) => setUsername(e.target.value)}
        error={usernameError}
        data-testid="invite-form-username"
      />
      <div className="flex flex-col gap-2">
        <span className="text-xs font-medium text-fg-muted">
          Permissions (optional)
        </span>
        {ALL_PERMISSIONS.map((p) => (
          <PermissionToggleRow
            key={p}
            permission={p}
            checked={permissions.has(p)}
            onChange={(on) => toggle(p, on)}
          />
        ))}
      </div>
      <CommissionRateInput
        value={rateInput}
        onChange={setRateInput}
        data-testid="invite-form-commission-rate"
      />
      <div className="flex justify-end">
        <Button
          type="submit"
          variant="primary"
          loading={invite.isPending}
          disabled={invite.isPending}
          data-testid="invite-form-submit"
        >
          Send invitation
        </Button>
      </div>
    </form>
  );
}
