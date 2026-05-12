"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { useUpdatePermissions } from "@/hooks/realty/useRealtyGroups";
import { ALL_PERMISSIONS } from "@/lib/realty/permissions";
import type { AgentCardDto, RealtyGroupPermission } from "@/types/realty";
import { CommissionRateInput } from "./CommissionRateInput";
import { PermissionToggleRow } from "./PermissionToggleRow";

export interface EditPermissionsFormProps {
  groupPublicId: string;
  member: AgentCardDto;
  onComplete?: () => void;
}

/**
 * Leader-only form rendered inside the members-tab "Edit permissions"
 * modal. Pre-populates from the member's current permission set, then
 * PATCHes the full flag set on submit.
 *
 * Member.permissions may be null when the caller cannot see the
 * fine-grained flag set (anonymous, non-member). The leader always sees
 * the full set so we treat null as empty defensively.
 *
 * Realty Groups: E adds an {@code agentCommissionRate} percentage input
 * — the leader's per-member slice of group-listing earnings. The backend
 * treats {@code null} as "leave unchanged", so submitting an empty input
 * preserves the prior value; explicit {@code 0} is a legal "no
 * commission" assignment.
 */
export function EditPermissionsForm({
  groupPublicId,
  member,
  onComplete,
}: EditPermissionsFormProps) {
  const mutate = useUpdatePermissions();
  const [permissions, setPermissions] = useState<Set<RealtyGroupPermission>>(
    () => new Set(member.permissions ?? []),
  );
  // Commission rate held as a percentage string ("10", "12.5", "") so the
  // user can type freely; converted to a 0..1 decimal on submit. Blank →
  // no field sent (leaves backend value unchanged); explicit "0" → 0.
  const [rateInput, setRateInput] = useState<string>(() =>
    member.agentCommissionRate != null
      ? (member.agentCommissionRate * 100).toString()
      : "",
  );

  // The parent modal re-mounts this form whenever a different member is
  // selected (see MembersTab.editTarget) — no useEffect-driven reset
  // needed. The form lifetime is scoped to one member at a time.

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
    const trimmed = rateInput.trim();
    // Empty input → omit the field so the backend leaves the rate alone.
    // Numeric input → convert percentage to 0..1 decimal.
    let agentCommissionRate: number | undefined;
    if (trimmed !== "") {
      const parsed = Number(trimmed);
      if (Number.isFinite(parsed)) {
        agentCommissionRate = parsed / 100;
      }
    }
    try {
      await mutate.mutateAsync({
        publicId: groupPublicId,
        memberPublicId: member.memberPublicId,
        body: {
          permissions: Array.from(permissions),
          ...(agentCommissionRate !== undefined
            ? { agentCommissionRate }
            : {}),
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
      aria-label={`Edit permissions for ${member.displayName}`}
    >
      {ALL_PERMISSIONS.map((p) => (
        <PermissionToggleRow
          key={p}
          permission={p}
          checked={permissions.has(p)}
          onChange={(on) => toggle(p, on)}
        />
      ))}
      <CommissionRateInput
        value={rateInput}
        onChange={setRateInput}
        data-testid="edit-permissions-commission-rate"
      />
      <div className="flex justify-end">
        <Button
          type="submit"
          variant="primary"
          loading={mutate.isPending}
          disabled={mutate.isPending}
          data-testid="edit-permissions-submit"
        >
          Save permissions
        </Button>
      </div>
    </form>
  );
}
